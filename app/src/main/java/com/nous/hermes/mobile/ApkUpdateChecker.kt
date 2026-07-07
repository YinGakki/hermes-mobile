package com.nous.hermes.mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APK 更新检测器 — 通过 GitHub Releases API 检查应用自身是否有新版本。
 *
 * 更新判定策略（按优先级）：
 * 1. SHA256 比较（首选）：从 Release body 的"校验值 (SHA256)"区块提取哈希值，
 *    与本地已安装 APK 的 SHA256 比较。不同 → 有更新；相同 → 无更新。
 *    用于解决测试版版本号不变但内容更新的情况。
 * 2. 版本号比较（兜底）：当 Release body 没有 SHA256，或本地 APK
 *    哈希计算失败时，退回语义化版本号比较。
 *
 * 通道过滤逻辑：
 * - 正式版通道：调用 /releases/latest，GitHub 返回最新的非 prerelease Release。
 * - 测试版通道：调用 /releases 列表，过滤 tag_name 含 "beta" 的 Release，取最新一条。
 */

data class ApkUpdateInfo(
    val version: String,       // 版本号，如 "0.0.3"（不含 v 前缀）
    val tagName: String,       // tag，如 "v0.0.3"
    val downloadUrl: String,   // APK 下载地址（GitHub 直链）
    val changelog: String,     // 更新日志（Release body 的 markdown 文本）
    val isBeta: Boolean,       // 是否为测试版（prerelease）
    val releaseName: String,   // Release 标题
    val releaseUrl: String,    // Release 页面 URL
    val fileSize: Long,        // APK 文件大小（字节）
    val releaseSha256: String? = null,  // Release APK 的 SHA256（从 Release body 提取），可能为空
)

object ApkUpdateChecker {

    private const val TAG = "ApkUpdateChecker"

    // ── GitHub 仓库 ──
    private const val GH_REPO = "YinGakki/hermes-mobile"
    private const val GH_API_BASE = "https://api.github.com/repos/$GH_REPO"

    /** GitHub 下载代理列表（按优先级排序，逐个尝试 + 直连兜底）。
     *  仅用于下载 APK 时加速，API 请求不走代理。 */
    private val GH_DOWNLOAD_PROXIES = listOf(
        "https://gh-proxy.com",
        "https://ghproxy.net",
        "https://mirror.ghproxy.com",
    )

    /** 更新通道 */
    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_BETA = "beta"

    // ── 本地 SHA256 缓存（避免每次检查都重算 ~15MB APK 的哈希） ──
    @Volatile private var cachedSha256: String? = null
    @Volatile private var cachedApkPath: String? = null
    @Volatile private var cachedApkMtime: Long = 0L

    /**
     * 检查 APK 是否有更新。
     *
     * @param currentVersion 当前版本号（如 "0.0.2-beta-lite"）
     * @param channel 更新通道（CHANNEL_STABLE 或 CHANNEL_BETA）
     * @param localApkPath 本地已安装 APK 的文件路径（用于 SHA256 比较），
     *   传 null 则跳过 SHA256 比较、仅用版本号兜底。
     * @return 更新信息（如果有新版本），否则返回 null
     */
    fun checkUpdate(
        currentVersion: String,
        channel: String,
        localApkPath: String? = null,
    ): ApkUpdateInfo? {
        return try {
            val release = when (channel) {
                CHANNEL_STABLE -> fetchLatestStable()
                else -> fetchLatestBeta()
            } ?: run {
                Log.w(TAG, "No release found from GitHub")
                return null
            }

            val tagName = release.optString("tag_name", "")
            val latestVersion = tagName.removePrefix("v").trim()
            if (latestVersion.isEmpty()) return null

            // 清理当前版本号：剥离 flavor 后缀（-lite / -full），保留 beta 标识。
            // versionName "0.0.2-beta" + flavor suffix "-lite" → "0.0.2-beta-lite"
            // 清理后 → "0.0.2-beta"，与 Release tag（不含 v 前缀）格式一致。
            val cleanCurrent = currentVersion
                .removeSuffix("-lite")
                .removeSuffix("-full")

            Log.i(TAG, "[GitHub] Current: $currentVersion (clean=$cleanCurrent), Latest: $latestVersion (tag=$tagName, prerelease=${release.optBoolean("prerelease")})")

            // 解析 APK 资产
            val apkAsset = findApkAsset(release)
            if (apkAsset == null) {
                Log.w(TAG, "No APK asset found in release $tagName")
                return null
            }

            // 从 Release body 提取 SHA256
            val releaseBody = release.optString("body", "").trim()
            val releaseSha256 = extractSha256FromBody(releaseBody)
            if (releaseSha256 != null) {
                Log.i(TAG, "Release SHA256 (from body): $releaseSha256")
            } else {
                Log.i(TAG, "No SHA256 found in Release body — will fall back to version comparison")
            }

            // ── 首选：SHA256 比较（解决版本号不变但内容更新的情况） ──
            if (releaseSha256 != null && localApkPath != null) {
                val localSha256 = computeLocalApkSha256(localApkPath)
                if (localSha256 != null) {
                    Log.i(TAG, "Local SHA256:  $localSha256")
                    Log.i(TAG, "Release SHA256: $releaseSha256")
                    if (localSha256.equals(releaseSha256, ignoreCase = true)) {
                        Log.i(TAG, "SHA256 matches — already up to date")
                        return null
                    }
                    Log.i(TAG, "SHA256 differs — update available")
                    return buildUpdateInfo(release, apkAsset, latestVersion, tagName, releaseSha256)
                }
                // 本地 SHA256 计算失败，继续走版本号兜底
                Log.w(TAG, "Local SHA256 computation failed — falling back to version comparison")
            }

            // ── 兜底：版本号比较 ──
            val cmp = compareVersions(latestVersion, cleanCurrent)
            if (cmp < 0) {
                Log.i(TAG, "Already up to date (version comparison)")
                return null
            }
            // 版本号相同（cmp == 0）：版本号不变但内容可能更新了（测试版常见）
            // 用 Release published_at 时间与本地 APK 安装时间比较
            if (cmp == 0) {
                Log.i(TAG, "Version same — checking release time vs install time")
                val publishedAt = release.optString("published_at", "")
                val installTime = getApkInstallTime(localApkPath)
                if (publishedAt.isNotEmpty() && installTime > 0) {
                    val releaseTime = parseIsoTime(publishedAt)
                    if (releaseTime > 0 && releaseTime > installTime) {
                        Log.i(TAG, "Release is newer than install — update available")
                        return buildUpdateInfo(release, apkAsset, latestVersion, tagName, releaseSha256)
                    }
                    Log.i(TAG, "Install is same or newer than release — up to date")
                    return null
                }
                // 无法比较时间，版本号相同 → 不提示更新
                Log.i(TAG, "Version same, cannot compare time — up to date")
                return null
            }

            buildUpdateInfo(release, apkAsset, latestVersion, tagName, releaseSha256)
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            null
        }
    }

    private fun buildUpdateInfo(
        release: JSONObject,
        apkAsset: JSONObject,
        version: String,
        tagName: String,
        releaseSha256: String?,
    ): ApkUpdateInfo {
        return ApkUpdateInfo(
            version = version,
            tagName = tagName,
            downloadUrl = apkAsset.optString("browser_download_url", ""),
            changelog = release.optString("body", "").trim(),
            isBeta = release.optBoolean("prerelease", false),
            releaseName = release.optString("name", tagName),
            releaseUrl = release.optString("html_url", ""),
            fileSize = apkAsset.optLong("size", 0L),
            releaseSha256 = releaseSha256,
        )
    }

    /** 获取最新的正式版 Release（非 prerelease） */
    private fun fetchLatestStable(): JSONObject? {
        val url = "$GH_API_BASE/releases/latest"
        val response = httpGet(url)
        return if (response != null) JSONObject(response) else null
    }

    /**
     * 获取最新的测试版 Release。
     * 从 /releases 列表中过滤 tag_name 含 "beta" 的 Release，取第一条（最新的）。
     */
    private fun fetchLatestBeta(): JSONObject? {
        val url = "$GH_API_BASE/releases?per_page=20"
        val response = httpGet(url) ?: return null
        val arr = JSONArray(response)
        for (i in 0 until arr.length()) {
            val release = arr.getJSONObject(i)
            val tagName = release.optString("tag_name", "")
            if (tagName.contains("beta", ignoreCase = true)) {
                Log.i(TAG, "Found latest beta release: $tagName")
                return release
            }
        }
        Log.w(TAG, "No beta release found in latest 20 releases")
        return null
    }

    /** 从 Release 的 assets 中找到 APK 文件 */
    private fun findApkAsset(release: JSONObject): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) {
                return asset
            }
        }
        return null
    }

    /** 获取本地 APK 的安装时间，用文件修改时间近似，返回毫秒时间戳 */
    private fun getApkInstallTime(apkPath: String?): Long {
        return try {
            if (apkPath != null) {
                val file = File(apkPath)
                if (file.exists()) file.lastModified() else 0L
            } else 0L
        } catch (e: Exception) {
            Log.w(TAG, "getApkInstallTime failed", e)
            0L
        }
    }

    /** 解析 ISO 8601 时间字符串（如 "2026-07-07T12:00:00Z"）为毫秒时间戳 */
    private fun parseIsoTime(iso: String): Long {
        return try {
            val cleaned = iso.replace(Regex("\\.\\d+"), "").trimEnd('Z')
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(cleaned)?.time ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "parseIsoTime failed: $iso", e)
            0L
        }
    }

    /**
     * 从 Release body 中提取 SHA256 哈希值。
     *
     * Release body 格式：
     * ```
     * ### 校验值 (SHA256)
     *
     * a1b2c3d4e5f6...  hermes-v0.0.2-beta-arm64-lite.apk
     * ```
     *
     * 提取 64 位十六进制字符串。
     */
    private fun extractSha256FromBody(body: String): String? {
        if (body.isEmpty()) return null
        // 匹配 64 位十六进制字符串（SHA256 哈希值）
        val match = Regex("[0-9a-fA-F]{64}").find(body)
        return match?.value?.lowercase()
    }

    /**
     * 计算本地已安装 APK 的 SHA256。
     * 使用 mtime 缓存：如果 APK 文件未修改，复用上次结果，避免重复哈希文件。
     */
    private fun computeLocalApkSha256(apkPath: String): String? {
        return try {
            val file = File(apkPath)
            if (!file.exists() || !file.isFile) return null
            val mtime = file.lastModified()

            // 缓存命中：同一路径 + 同一修改时间
            if (apkPath == cachedApkPath && mtime == cachedApkMtime && cachedSha256 != null) {
                Log.i(TAG, "Using cached local SHA256 (path=$apkPath, mtime=$mtime)")
                return cachedSha256
            }

            Log.i(TAG, "Computing SHA256 for $apkPath (${file.length()} bytes)…")
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(65536)
                var bytes = fis.read(buffer)
                while (bytes > 0) {
                    digest.update(buffer, 0, bytes)
                    bytes = fis.read(buffer)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            // 更新缓存
            cachedApkPath = apkPath
            cachedApkMtime = mtime
            cachedSha256 = hash

            hash
        } catch (e: Exception) {
            Log.e(TAG, "computeLocalApkSha256 failed for $apkPath", e)
            null
        }
    }

    /** HTTP GET 请求（GitHub API），返回响应体字符串 */
    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "HermesAndroid/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "HTTP $code for $urlStr")
                val errStream = conn.errorStream
                if (errStream != null) {
                    val errText = BufferedReader(InputStreamReader(errStream)).readText()
                    Log.e(TAG, "Error body: $errText")
                }
                return null
            }

            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } catch (e: Exception) {
            Log.e(TAG, "httpGet failed: $urlStr", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 语义化版本号比较。
     * 支持 "0.0.2"、"0.1.0"、"1.2.3-beta" 等格式。
     * @return >0 如果 v1 > v2, 0 如果相等, <0 如果 v1 < v2
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.removePrefix("v").split("-")[0].split(".")
        val parts2 = v2.removePrefix("v").split("-")[0].split(".")

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val p2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (p1 != p2) return p1 - p2
        }

        val suffix1 = if (v1.contains("-")) v1.substringAfter("-") else ""
        val suffix2 = if (v2.contains("-")) v2.substringAfter("-") else ""

        if (suffix1.isEmpty() && suffix2.isNotEmpty()) return 1
        if (suffix1.isNotEmpty() && suffix2.isEmpty()) return -1

        return suffix1.compareTo(suffix2)
    }

    /**
     * 构建下载 URL 列表（逐个尝试直到成功）。
     * ghproxy 代理加速链接（多个镜像逐个尝试）+ GitHub 直连兜底。
     *
     * @param directUrl Release 资产的原始 browser_download_url
     * @return 下载 URL 列表，按优先级排序
     */
    fun getDownloadUrls(directUrl: String): List<String> {
        return GH_DOWNLOAD_PROXIES.map { "$it/$directUrl" } + directUrl
    }
}
