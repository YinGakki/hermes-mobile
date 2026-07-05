package com.nous.hermes.mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 更新检测器 — 通过 GitHub 或 Gitee Releases API 检查应用自身是否有新版本。
 *
 * 功能：
 * - 支持正式版/测试版通道选择（基于 Release 的 prerelease 标志）
 * - 支持 GitHub / Gitee 双更新源（国内用户走 Gitee，速度快无需翻墙）
 * - 获取更新日志（Release body）用于显示
 *
 * GitHub API 端点（海外用户）：
 * - 正式版：GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 * - 测试版：GET https://api.github.com/repos/{owner}/{repo}/releases
 *
 * Gitee API 端点（国内用户）：
 * - 正式版：GET https://gitee.com/api/v5/repos/{owner}/{repo}/releases/latest
 * - 测试版：GET https://gitee.com/api/v5/repos/{owner}/{repo}/releases
 *
 * Gitee 仓库需手动从 GitHub 镜像同步，Release 附件（APK）需通过
 * GitHub Action 或 release2gitee 工具同步到 Gitee。
 */

data class ApkUpdateInfo(
    val version: String,       // 版本号，如 "0.0.3"（不含 v 前缀）
    val tagName: String,       // tag，如 "v0.0.3"
    val downloadUrl: String,   // APK 下载地址（对应平台的直链）
    val changelog: String,     // 更新日志（Release body 的 markdown 文本）
    val isBeta: Boolean,       // 是否为测试版（prerelease）
    val releaseName: String,   // Release 标题
    val releaseUrl: String,    // Release 页面 URL
    val fileSize: Long,        // APK 文件大小（字节）
)

object ApkUpdateChecker {

    private const val TAG = "ApkUpdateChecker"

    // ── GitHub 仓库（主仓库，海外用户）──
    private const val GH_REPO = "YinGakki/hermes-mobile"
    private const val GH_API_BASE = "https://api.github.com/repos/$GH_REPO"

    // ── Gitee 仓库（国内镜像，国内用户）──
    private const val GITEE_REPO = "yingakki/hermes-mobile"
    private const val GITEE_API_BASE = "https://gitee.com/api/v5/repos/$GITEE_REPO"

    /** GitHub 下载代理列表（按优先级排序，逐个尝试 + 直连兜底）。
     *  仅用于 GitHub 源下载 APK 时加速，API 请求不走代理。
     *  Gitee 源下载地址在 gitee.com 上，国内直连无需代理。 */
    private val GH_DOWNLOAD_PROXIES = listOf(
        "https://gh-proxy.com",
        "https://ghproxy.net",
        "https://mirror.ghproxy.com",
    )

    /** 更新通道 */
    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_BETA = "beta"

    /** 更新源 */
    const val SOURCE_GITHUB = "github"
    const val SOURCE_GITEE = "gitee"

    /**
     * 检查 APK 是否有更新。
     *
     * @param currentVersion 当前版本号（如 "0.0.2"，不含 v 前缀）
     * @param channel 更新通道（CHANNEL_STABLE 或 CHANNEL_BETA）
     * @param source 更新源（SOURCE_GITHUB 或 SOURCE_GITEE）
     * @return 更新信息（如果有新版本），否则返回 null
     */
    fun checkUpdate(
        currentVersion: String,
        channel: String,
        source: String,
    ): ApkUpdateInfo? {
        return try {
            val (apiBase, platform) = when (source) {
                SOURCE_GITEE -> GITEE_API_BASE to "Gitee"
                else -> GH_API_BASE to "GitHub"
            }

            val release = when (channel) {
                CHANNEL_STABLE -> fetchLatestStable(apiBase)
                else -> fetchLatestRelease(apiBase)
            } ?: run {
                Log.w(TAG, "No release found from $platform")
                return null
            }

            val tagName = release.optString("tag_name", "")
            val latestVersion = tagName.removePrefix("v").trim()
            if (latestVersion.isEmpty()) return null

            Log.i(TAG, "[$platform] Current: $currentVersion, Latest: $latestVersion (tag=$tagName, prerelease=${release.optBoolean("prerelease")})")

            // 比较版本号
            if (compareVersions(latestVersion, currentVersion) <= 0) {
                Log.i(TAG, "Already up to date")
                return null
            }

            // 解析 APK 资产
            val apkAsset = findApkAsset(release)
            if (apkAsset == null) {
                Log.w(TAG, "No APK asset found in release $tagName on $platform")
                return null
            }

            val downloadUrl = apkAsset.optString("browser_download_url", "")
            val changelog = release.optString("body", "").trim()
            val releaseName = release.optString("name", tagName)
            val releaseUrl = release.optString("html_url", "")
            val fileSize = apkAsset.optLong("size", 0L)
            val isBeta = release.optBoolean("prerelease", false)

            ApkUpdateInfo(
                version = latestVersion,
                tagName = tagName,
                downloadUrl = downloadUrl,
                changelog = changelog,
                isBeta = isBeta,
                releaseName = releaseName,
                releaseUrl = releaseUrl,
                fileSize = fileSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            null
        }
    }

    /** 获取最新的正式版 Release（非 prerelease） */
    private fun fetchLatestStable(apiBase: String): JSONObject? {
        val url = "$apiBase/releases/latest"
        val response = httpGet(url)
        return if (response != null) JSONObject(response) else null
    }

    /** 获取最新的 Release（包括 prerelease），取列表第一个 */
    private fun fetchLatestRelease(apiBase: String): JSONObject? {
        val url = "$apiBase/releases?per_page=10"
        val response = httpGet(url) ?: return null
        val arr = JSONArray(response)
        return if (arr.length() > 0) arr.getJSONObject(0) else null
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
     * 根据更新源构建下载 URL 列表（逐个尝试直到成功）。
     *
     * - GitHub 源：代理加速链接（ghproxy 等）+ GitHub 直连兜底
     * - Gitee 源：直接使用 Gitee 直链（国内可直连）
     *
     * @param directUrl Release 资产的原始 browser_download_url
     * @param source 更新源（SOURCE_GITHUB 或 SOURCE_GITEE）
     * @return 下载 URL 列表，按优先级排序
     */
    fun getDownloadUrls(directUrl: String, source: String): List<String> {
        return when (source) {
            SOURCE_GITHUB -> {
                // GitHub 源：代理加速 + 直连兜底
                GH_DOWNLOAD_PROXIES.map { "$it/$directUrl" } + directUrl
            }
            else -> {
                // Gitee 源：直连即可（Gitee download URL 本身就在 gitee.com 上）
                listOf(directUrl)
            }
        }
    }
}
