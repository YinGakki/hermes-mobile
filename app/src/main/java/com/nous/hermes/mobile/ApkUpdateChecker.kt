package com.nous.hermes.mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 更新检测器 — 通过 GitHub Releases API 检查应用自身是否有新版本。
 *
 * 更新判定策略：语义化版本号比较。
 *
 * 通道过滤逻辑：
 * - 正式版通道：调用 /releases/latest，GitHub 返回最新的非 prerelease Release。
 * - 测试版通道：调用 /releases 列表，过滤 tag_name 含 "beta" 的 Release，取最新一条。
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

    /**
     * 检查 APK 是否有更新。
     *
     * @param currentVersion 当前版本号（如 "0.0.2-beta-lite"）
     * @param channel 更新通道（CHANNEL_STABLE 或 CHANNEL_BETA）
     * @return 更新信息（如果有新版本），否则返回 null
     */
    fun checkUpdate(
        currentVersion: String,
        channel: String,
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

            // 比较版本号
            if (compareVersions(latestVersion, cleanCurrent) <= 0) {
                Log.i(TAG, "Already up to date")
                return null
            }

            // 解析 APK 资产
            val apkAsset = findApkAsset(release)
            if (apkAsset == null) {
                Log.w(TAG, "No APK asset found in release $tagName")
                return null
            }

            ApkUpdateInfo(
                version = latestVersion,
                tagName = tagName,
                downloadUrl = apkAsset.optString("browser_download_url", ""),
                changelog = release.optString("body", "").trim(),
                isBeta = release.optBoolean("prerelease", false),
                releaseName = release.optString("name", tagName),
                releaseUrl = release.optString("html_url", ""),
                fileSize = apkAsset.optLong("size", 0L),
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            null
        }
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

    /** HTTP GET 请求，返回响应体字符串 */
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
