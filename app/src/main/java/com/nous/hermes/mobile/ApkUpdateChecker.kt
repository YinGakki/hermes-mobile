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
 * 1. SHA256 比较（首选）：从 Release 的 `.apk.sha256` 资产读取校验值，
 *    与本地已安装 APK 的 SHA256 比较。不同 → 有更新；相同 → 无更新。
 *    这能彻底解决 lite/non-lite、同版本重编译等版本号无法区分的情况。
 * 2. 版本号比较（兜底）：当 Release 没有 `.sha256` 资产，或本地 APK
 *    哈希计算失败时，退回语义化版本号比较。
 *
 * GitHub API 端点：
 * - 正式版：GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 * - 测试版：GET https://api.github.com/repos/{owner}/{repo}/releases
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
    val releaseSha256: String? = null,  // Release APK 的 SHA256（来自 .sha256 资产），可能为空
)

object ApkUpdateChecker {

    private const val TAG = "ApkUpdateChecker"

    // ── GitHub 仓库 ──
    private const val GH_REPO = "YinGakki/hermes-mobile"
    private const val GH_API_BASE = "https://api.github.com/repos/$GH_REPO"

    /** GitHub 下载代理列表（按优先级排序，逐个尝试 + 直连兜底）。
     *  仅用于下载 APK / .sha256 时加速，API 请求不走代理。 */
    private val GH_DOWNLOAD_PROXIES = listOf(
        "https://gh-proxy.com",
        "https://ghproxy.net",
        "https://mirror.ghproxy.com",
    )

    /** 更新通道 */
    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_BETA = "beta"

    // ── 本地 SHA256 缓存（避免每次检查都重算 ~100MB APK 的哈希） ──
    @Volatile private var cachedSha256: String? = null
    @Volatile private var cachedApkPath: String? = null
    @Volatile private var cachedApkMtime: Long = 0L

    /**
     * 检查 APK 是否有更新。
     *
     * @param currentVersion 当前版本号（如 "0.0.2-lite"）
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
                else -> fetchLatestRelease()
            } ?: run {
                Log.w(TAG, "No release found from GitHub")
                return null
            }

            val tagName = release.optString("tag_name", "")
            val latestVersion = tagName.removePrefix("v").trim()
            if (latestVersion.isEmpty()) return null

            // 检测当前构建的 flavor（lite / full），用于匹配正确的 APK asset。
            val currentFlavor = when {
                currentVersion.contains("-lite") -> "lite"
                currentVersion.contains("-full") -> "full"
                else -> ""
            }
            val cleanCurrent = currentVersion.substringBefore("-")

            Log.i(TAG, "[GitHub] Current: $currentVersion (clean=$cleanCurrent, flavor=$currentFlavor), Latest: $latestVersion (tag=$tagName, prerelease=${release.optBoolean("prerelease")})")

            // 解析 APK 资产 — 优先选择与当前 flavor 匹配的 APK
            val apkAsset = findApkAsset(release, currentFlavor)
            if (apkAsset == null) {
                Log.w(TAG, "No APK asset found in release $tagName")
                return null
            }
            val apkAssetName = apkAsset.optString("name", "")

            // 查找对应的 .sha256 资产
            val sha256Asset = findSha256Asset(release, apkAssetName)
            val releaseSha256 = if (sha256Asset != null) {
                fetchSha256Content(sha256Asset.optString("browser_download_url", "")).also {
                    Log.i(TAG, "Release SHA256: $it")
                }
            } else {
                Log.i(TAG, "No .sha256 asset in release $tagName — will fall back to version comparison")
                null
            }

            // ── 首选：SHA256 比较 ──
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
            if (compareVersions(latestVersion, cleanCurrent) <= 0) {
                Log.i(TAG, "Already up to date (version comparison)")
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

    /** 获取最新的 Release（包括 prerelease），取列表第一个 */
    private fun fetchLatestRelease(): JSONObject? {
        val url = "$GH_API_BASE/releases?per_page=10"
        val response = httpGet(url) ?: return null
        val arr = JSONArray(response)
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    /**
     * 从 Release 的 assets 中找到 APK 文件。
     * 如果指定了 currentFlavor（"lite" 或 "full"），优先选择文件名匹配该 flavor 的 APK，
     * 避免 lite 版用户下载到 full 版 APK（反之亦然）。
     */
    private fun findApkAsset(release: JSONObject, currentFlavor: String = ""): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null

        // 第一轮：匹配当前 flavor
        if (currentFlavor.isNotEmpty()) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk") && !name.endsWith(".apk.sha256") &&
                    name.contains(currentFlavor, ignoreCase = true)
                ) {
                    Log.i(TAG, "Selected APK asset (flavor=$currentFlavor): $name")
                    return asset
                }
            }
        }

        // 第二轮：取第一个 .apk（兜底，排除 .apk.sha256）
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk") && !name.endsWith(".apk.sha256")) {
                Log.i(TAG, "Selected APK asset (fallback, flavor=$currentFlavor): $name")
                return asset
            }
        }
        return null
    }

    /**
     * 查找与 APK 资产对应的 .sha256 校验文件资产。
     * 优先精确匹配 `<apkName>.sha256`，其次取任意 `.sha256` 资产。
     */
    private fun findSha256Asset(release: JSONObject, apkAssetName: String): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null

        // 第一轮：精确匹配 <apkName>.sha256
        if (apkAssetName.isNotEmpty()) {
            val target = "${apkAssetName}.sha256"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name", "") == target) {
                    Log.i(TAG, "Found SHA256 asset (exact): $target")
                    return asset
                }
            }
        }

        // 第二轮：任意 .sha256 资产
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".sha256")) {
                Log.i(TAG, "Found SHA256 asset (fallback): $name")
                return asset
            }
        }
        return null
    }

    /**
     * 下载并解析 `.sha256` 文件内容，提取 64 位十六进制哈希。
     * 依次尝试 ghproxy 代理 + GitHub 直连，取第一个成功的结果。
     */
    private fun fetchSha256Content(directUrl: String): String? {
        if (directUrl.isEmpty()) return null
        for (url in getDownloadUrls(directUrl)) {
            val content = httpGetRaw(url)
            if (content != null) {
                val hash = parseSha256(content)
                if (hash != null) return hash
            }
        }
        return null
    }

    /** 从 sha256sum 输出或纯哈希文本中提取 64 位十六进制 SHA256 */
    private fun parseSha256(content: String): String? {
        val match = Regex("[0-9a-fA-F]{64}").find(content.trim())
        return match?.value?.lowercase()
    }

    /**
     * 计算本地已安装 APK 的 SHA256。
     * 使用 mtime 缓存：如果 APK 文件未修改，复用上次结果，避免重复哈希 ~100MB 文件。
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

    /** HTTP GET 请求（普通文件下载，非 API），返回响应体字符串 */
    private fun httpGetRaw(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "HermesAndroid/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } catch (e: Exception) {
            Log.w(TAG, "httpGetRaw failed: $urlStr — ${e.message}")
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
