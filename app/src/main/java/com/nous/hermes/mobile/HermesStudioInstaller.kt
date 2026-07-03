package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 安装并运行 hermes-web-ui npm 包 —— Hermes Agent 的 web 仪表盘
 * (https://github.com/EKKOLearnAI/hermes-studio)。
 *
 * 新架构（proot + Ubuntu rootfs）下：
 *   - Node.js/npm 通过 apt-get install 装进 rootfs（glibc 版本，非 bionic）
 *   - npm install -g hermes-web-ui 在 proot 里执行
 *   - 服务通过 proot gateway 模式长驻运行
 *   - 不再需要 bionic-bypass.js（rootfs 用 glibc，os.networkInterfaces() 正常）
 *
 * Watchdog / 健康检查逻辑参照 openclaw-termux GatewayService.kt：
 *   - 监控 /health 端点，崩溃自动重启（最多 5 次，指数退避）
 *   - 优雅停止：destroy proot 进程 → hermes-web-ui stop
 */
class HermesStudioInstaller(private val context: Context) {

    companion object {
        private const val TAG = "HermesStudioInstaller"
        const val STUDIO_PORT = 8648
        const val STUDIO_BASE_URL = "http://localhost:$STUDIO_PORT"
        private const val NPM_PACKAGE = "hermes-web-ui"

        // hermes CLI 的 venv bin 路径（proot 内视角）。
        // hermes-web-ui 启动时会 spawn('hermes', ['gateway','run','--replace'])，
        // 必须把这个路径加到 PATH 里，否则 ENOENT。
        private const val HERMES_VENV_BIN = "/root/home/hermes-agent/.venv/bin"

        // Watchdog config (from openclaw-termux GatewayService.kt)
        private const val MAX_RESTARTS = 5
        private const val INITIAL_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 16000L
        private const val GRACE_PERIOD_MS = 60000L
        private const val WATCHDOG_INTERVAL_MS = 15000L
        private const val WATCHDOG_INITIAL_DELAY_MS = 45000L

        // Health check config
        private const val HEALTH_FIRST_DELAY_MS = 30000L
        private const val HEALTH_INTERVAL_MS = 3000L
        private const val HEALTH_GRACE_MS = 120000L
        private const val HEALTH_STARTUP_TIMEOUT_MS = 60000L
    }

    private val serverMgr = HermesServerManager(context)
    private val paths: BootstrapManager.Paths by lazy { BootstrapManager.getPaths(context) }
    private val processManager: ProcessManager by lazy {
        ProcessManager(context, paths.filesDir, paths.nativeLibDir)
    }

    private var watchdogThread: Thread? = null
    @Volatile private var watchdogRunning = false
    @Volatile private var restartCount = 0
    @Volatile private var serverStartTime = 0L
    private var studioProcess: Process? = null

    // 最近的服务器输出（环形缓冲），用于失败时把真实错误展示给用户。
    // onProgress 回调同步追加，spawnStudioServer 的读取线程也追加。
    private val recentOutput = java.util.ArrayDeque<String>(200)
    @Volatile private var lastProgressCallback: ((String) -> Unit)? = null

    private fun recordOutput(line: String) {
        synchronized(recentOutput) {
            if (recentOutput.size >= 200) recentOutput.pollFirst()
            recentOutput.addLast(line)
        }
    }

    /**
     * 返回最近的服务器输出（用于失败诊断）。调用方应在 start() 返回 false
     * 后立即调用以获取崩溃日志。
     */
    fun getRecentOutput(): String = synchronized(recentOutput) {
        recentOutput.joinToString("\n")
    }

    /**
     * 读取 hermes-web-ui 的 server.log（npm 包内部写入的真实错误日志）。
     *
     * hermes-web-ui 启动失败时会提示 "Check log: /root/.hermes-web-ui/server.log"，
     * 这个文件在 proot rootfs 内，对应 host 路径 ${rootfsDir}/root/.hermes-web-ui/server.log。
     * 返回文件内容（最后 200 行），文件不存在时返回 null。
     */
    fun getServerLog(): String? {
        // proot 内 /root/.hermes-web-ui/server.log → host rootfsDir/root/.hermes-web-ui/server.log
        val logFile = File(paths.rootfsDir, "root/.hermes-web-ui/server.log")
        if (!logFile.exists() || !logFile.isFile) return null
        return try {
            logFile.readLines().takeLast(200).joinToString("\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read server.log: ${e.message}")
            null
        }
    }

    val isRunning: Boolean
        get() = checkServerHealth()

    fun isInstalled(): Boolean {
        if (!serverMgr.isProotInstalled()) return false
        // 在 proot 里检查 hermes-web-ui 是否在 PATH
        val code = processManager.runInProotExitCode(
            "command -v hermes-web-ui >/dev/null 2>&1", 30
        )
        return code == 0
    }

    /**
     * 安装 Node.js 23（从 npmmirror 下载二进制 tarball）+ npm install -g hermes-web-ui。
     *
     * Ubuntu 24.04 apt 只提供 Node.js 18.x，但 hermes-web-ui@0.6.23 要求
     * node >=23.0.0。所以必须用 Node.js 23 官方二进制 tarball，解压到
     * /usr/local（PATH 优先级高于 /usr/bin，覆盖 apt 版本）。
     */
    fun install(onProgress: (String) -> Unit): Boolean {
        onProgress("Installing hermes-web-ui (this may take 2-5 min)…")

        // Step 1: 确保 Node.js >=23 已装
        if (!serverMgr.isNodeInstalled()) {
            if (!installNodeJs(onProgress)) {
                onProgress("错误：Node.js 23 安装失败")
                return false
            }
        } else {
            onProgress("Node.js >=23 已安装，跳过")
        }

        // Step 2: npm install -g hermes-web-ui
        val ok = serverMgr.runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "npm install -g hermes-web-ui",
        ) {
            val code = processManager.runInProotExitCode(
                "npm install -g $NPM_PACKAGE 2>&1", 1200
            ) { onProgress(it) }
            code == 0 && isInstalled()
        }
        if (!ok) {
            Log.e(TAG, "npm install -g hermes-web-ui failed after retries")
            return false
        }
        onProgress("✓ hermes-web-ui installed")
        return true
    }

    /**
     * 更新 hermes-web-ui：npm install -g hermes-web-ui@latest。
     * 如果服务正在运行，先停止再更新再重启。
     */
    fun update(onProgress: (String) -> Unit): Boolean {
        if (!isInstalled()) {
            onProgress("错误：hermes-web-ui 未安装，请先安装")
            return false
        }
        // 如果服务在跑，先停掉（否则 npm 全局更新可能因文件占用失败）
        val wasRunning = isRunning
        if (wasRunning) {
            onProgress("webui: 停止运行中的服务以更新…")
            stop()
        }
        onProgress("webui: npm install -g hermes-web-ui@latest…")
        val ok = serverMgr.runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "npm install -g hermes-web-ui@latest",
        ) {
            val code = processManager.runInProotExitCode(
                "npm install -g $NPM_PACKAGE@latest 2>&1", 1200
            ) { onProgress(it) }
            code == 0 && isInstalled()
        }
        if (!ok) {
            onProgress("错误：hermes-web-ui 更新失败")
            return false
        }
        onProgress("✓ hermes-web-ui 更新完成")
        return true
    }

    /**
     * 从 npmmirror 下载 Node.js 23 二进制 tarball，解压到 /usr/local。
     *
     * Ubuntu 24.04 apt 只有 Node.js 18.x，但 hermes-web-ui 要求 >=23。
     * 下载用 Java HttpURLConnection（和 rootfs/hermes tarball 同模式），
     * 解压用 proot 里的 tar -xJf。
     */
    private fun installNodeJs(onProgress: (String) -> Unit): Boolean {
        onProgress("Node.js: 查询最新 v23 版本…")
        val nodeVersion = fetchLatestNodeVersion() ?: "v23.11.0"
        onProgress("Node.js: 目标版本 $nodeVersion")

        val tarballName = "node-$nodeVersion-linux-arm64.tar.xz"
        val tarballUrls = listOf(
            "https://npmmirror.com/mirrors/node/$nodeVersion/$tarballName",
            "https://nodejs.org/dist/$nodeVersion/$tarballName",
        )
        val tarballFile = File(paths.tmpDir, "node.tar.xz")
        var downloaded = false
        for (url in tarballUrls) {
            val host = url.substringAfter("://").substringBefore("/")
            onProgress("Node.js: 下载 from $host…")
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    onProgress("Node.js: HTTP ${conn.responseCode} from $host")
                    conn.disconnect()
                    continue
                }
                conn.inputStream.use { inp ->
                    tarballFile.outputStream().use { inp.copyTo(it) }
                }
                conn.disconnect()
                if (tarballFile.length() < 1024) {
                    tarballFile.delete(); continue
                }
                downloaded = true
                onProgress("Node.js: 下载完成 (${tarballFile.length() / 1048576}MB)")
                break
            } catch (e: Exception) {
                onProgress("Node.js: $host 下载失败: ${e.message}")
                tarballFile.delete()
            }
        }
        if (!downloaded) {
            onProgress("Node.js: 所有镜像下载失败")
            return false
        }

        // 解压到 /usr/local（--strip-components=1 去掉顶层 node-vXX.X.X-linux-arm64/ 目录）
        // /usr/local/bin 在 PATH 最前，覆盖 apt 装的 /usr/bin/node
        onProgress("Node.js: 解压到 /usr/local…")
        val code = processManager.runInProotExitCode(
            "tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 && " +
                "rm -f /tmp/node.tar.xz && " +
                "node --version && npm --version",
            120
        ) { onProgress(it) }
        tarballFile.delete()
        if (code != 0) {
            onProgress("Node.js: 解压失败 (exit=$code)")
            return false
        }
        onProgress("✓ Node.js 安装完成")
        return true
    }

    /**
     * 从 npmmirror 的 index.tab 查询最新的 Node.js v23 版本号。
     * 返回 "v23.x.x" 或 null（查询失败时用硬编码 fallback）。
     */
    private fun fetchLatestNodeVersion(): String? {
        return try {
            val url = java.net.URL("https://npmmirror.com/mirrors/node/index.tab")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val content = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // index.tab 格式：version\tdate\t...\n（首行是表头）
            content.lineSequence()
                .drop(1)
                .mapNotNull { line ->
                    val ver = line.substringBefore("\t")
                    if (ver.startsWith("v23.")) ver else null
                }
                .firstOrNull()
        } catch (_: Exception) { null }
    }

    /**
     * 启动 hermes-web-ui 守护进程 + watchdog。
     *
     * 用 proot gateway 模式启动（startProotProcess），保持 Process 引用存活。
     * proot --kill-on-exit 会在 proot 退出时杀子进程，所以必须保持 proot 进程
     * 不退出（由本类持有 studioProcess 引用）。
     */
    fun start(onProgress: (String) -> Unit): Boolean {
        lastProgressCallback = onProgress
        // 重连：如果服务已在跑，直接接管
        if (checkServerHealth()) {
            onProgress("hermes-web-ui already running — reconnecting")
            serverStartTime = System.currentTimeMillis()
            restartCount = 0
            startWatchdog(onProgress)
            return true
        }
        if (!isInstalled()) {
            onProgress("错误：hermes-web-ui 未安装")
            Log.e(TAG, "Cannot start — hermes-web-ui not installed")
            return false
        }

        // 端口占用检测
        if (isPortInUse(STUDIO_PORT)) {
            onProgress("Port $STUDIO_PORT is in use by another process")
            forceKillByPort()
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }

        // 预检：确认 hermes-web-ui + node + hermes CLI 都可用，避免"启动即崩溃"
        // 却看不到错误的盲区。hermes-web-ui 内部会 spawn('hermes', ...)，
        // 所以 hermes CLI 必须在 PATH 里（它在 venv bin 里，不在默认 PATH）。
        onProgress("预检: 验证 hermes-web-ui + hermes CLI 可执行…")
        val preflight = processManager.runInProotExitCode(
            "command -v hermes-web-ui && node --version && npm --version && " +
                "PATH=\"$HERMES_VENV_BIN:\$PATH\" command -v hermes",
            15
        ) { onProgress("[preflight] $it") }
        if (preflight != 0) {
            onProgress("错误：预检失败 — hermes-web-ui / node / hermes CLI 不在 PATH 里 (exit=$preflight)")
            recordOutput("preflight failed: exit=$preflight (hermes CLI missing?)")
            return false
        }

        return try {
            spawnStudioServer(onProgress)
            onProgress("Waiting for hermes-web-ui to be ready…")
            val ready = waitForHealth(onProgress)
            if (ready) {
                serverStartTime = System.currentTimeMillis()
                restartCount = 0
                onProgress("hermes-web-ui started on $STUDIO_BASE_URL")
                startWatchdog(onProgress)
            } else {
                // hermes-web-ui 是 daemonizing CLI：CLI 进程启动服务后自己退出（code 0），
                // 实际服务作为子进程运行。如果 CLI 退出码为 0 且端口在监听，
                // 说明服务已起来，只是 /health 端点可能不存在 —— 视为成功。
                val exitCode = studioProcess?.let {
                    try { if (!it.isAlive) it.exitValue() else null } catch (_: Exception) { null }
                }
                if (exitCode == 0 && isPortInUse(STUDIO_PORT)) {
                    onProgress("hermes-web-ui CLI 已退出 (code=0)，但服务在端口 $STUDIO_PORT 监听中 —— 视为成功")
                    serverStartTime = System.currentTimeMillis()
                    restartCount = 0
                    startWatchdog(onProgress)
                    return true
                }
                onProgress("hermes-web-ui did not become healthy within ${HEALTH_STARTUP_TIMEOUT_MS / 1000}s")
                if (exitCode != null) {
                    onProgress("hermes-web-ui 进程已退出，exit=$exitCode")
                }
            }
            ready
        } catch (e: Exception) {
            onProgress("启动异常: ${e.message}")
            Log.e(TAG, "Failed to start hermes-web-ui", e)
            false
        }
    }

    /**
     * 通过 proot gateway 模式启动 hermes-web-ui，保持进程引用存活。
     * 服务器输出同步转发到 onProgress（进入 app 日志页）+ 环形缓冲（供
     * 失败诊断）+ logcat。
     */
    private fun spawnStudioServer(onProgress: ((String) -> Unit)? = null) {
        stopStudioProcess()
        // hermes-web-ui 监听 PORT 环境变量；在 proot 里运行。
        //
        // 关键 1：hermes-web-ui 内部会 spawn('hermes', ['gateway','run','--replace'])
        // 调用 hermes CLI，但 hermes 装在 venv 里（/root/home/hermes-agent/.venv/bin），
        // 不在默认 PATH。必须把 venv bin 加到 PATH 最前面，否则 ENOENT。
        //
        // 关键 2：hermes-web-ui 是 daemonizing CLI —— CLI 启动服务后自己退出
        // （code 0），实际服务作为子进程运行。如果用 exec hermes-web-ui，
        // CLI 退出后 proot 进程也退出，--kill-on-exit 会杀掉服务子进程。
        // 所以不用 exec，改为 hermes-web-ui; exec sleep infinity —— CLI 退出后
        // bash 继续 sleep infinity，proot 进程保持存活，服务子进程（proot 的
        // 孙子进程）继续运行。停止时 destroy proot 进程，--kill-on-exit 清理。
        val cmd = "PORT=$STUDIO_PORT NODE_ENV=production " +
            "PATH=\"$HERMES_VENV_BIN:\$PATH\" hermes-web-ui 2>&1; " +
            "exec sleep infinity"
        val proc = processManager.startProotProcess(cmd)
        studioProcess = proc
        // 转发 stdout 到 logcat + 环形缓冲 + onProgress 回调
        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[studio] $line")
                recordOutput(line)
                onProgress?.invoke("[studio] $line")
                line = reader.readLine()
            }
            val code = proc.waitFor()
            val exitLine = "hermes-web-ui exited with code: $code"
            Log.i(TAG, exitLine)
            recordOutput(exitLine)
            onProgress?.invoke("[studio] $exitLine")
        }.start()
    }

    private fun stopStudioProcess() {
        studioProcess?.let {
            it.destroy()
            try {
                it.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
            if (it.isAlive) it.destroyForcibly()
        }
        studioProcess = null
    }

    /**
     * Watchdog 线程 —— 每 15s 检查 /health，崩溃自动重启。
     */
    private fun startWatchdog(onProgress: (String) -> Unit) {
        stopWatchdog()
        watchdogRunning = true
        watchdogThread = Thread {
            Log.i(TAG, "Watchdog started (initial delay ${WATCHDOG_INITIAL_DELAY_MS / 1000}s)")
            try {
                Thread.sleep(WATCHDOG_INITIAL_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }

            while (watchdogRunning) {
                val healthy = checkServerHealth()
                if (!healthy && watchdogRunning) {
                    val uptime = System.currentTimeMillis() - serverStartTime
                    Log.w(TAG, "Watchdog: server unhealthy (uptime=${uptime / 1000}s)")

                    if (uptime > GRACE_PERIOD_MS) {
                        Log.i(TAG, "Watchdog: server ran >${GRACE_PERIOD_MS / 1000}s, resetting restart count")
                        restartCount = 0
                    }

                    if (restartCount >= MAX_RESTARTS) {
                        Log.e(TAG, "Watchdog: max restarts ($MAX_RESTARTS) reached, giving up")
                        break
                    }

                    val backoff = minOf(
                        INITIAL_BACKOFF_MS * (1L shl restartCount),
                        MAX_BACKOFF_MS
                    )
                    restartCount++
                    Log.w(TAG, "Watchdog: restart $restartCount/$MAX_RESTARTS after ${backoff / 1000}s backoff")
                    try {
                        Thread.sleep(backoff)
                    } catch (_: InterruptedException) {
                        break
                    }

                    if (!watchdogRunning) break
                    onProgress("Watchdog: restarting hermes-web-ui (attempt $restartCount/$MAX_RESTARTS)…")
                    try {
                        spawnStudioServer(lastProgressCallback)
                        val deadline = System.currentTimeMillis() + 10000
                        var restarted = false
                        while (System.currentTimeMillis() < deadline) {
                            if (checkServerHealth()) { restarted = true; break }
                            Thread.sleep(1000)
                        }
                        if (restarted) {
                            serverStartTime = System.currentTimeMillis()
                            onProgress("Watchdog: hermes-web-ui restarted successfully")
                        } else {
                            Log.e(TAG, "Watchdog: restart $restartCount failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog restart failed", e)
                    }
                }

                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "Watchdog stopped")
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /**
     * 优雅停止：停 watchdog → 停 proot 进程 → 必要时按端口强杀。
     */
    fun stop() {
        stopWatchdog()
        stopStudioProcess()
        if (!isInstalled()) return
        try {
            // 尝试 CLI 优雅停止（读 PID 文件发 SIGTERM）
            serverMgr.runInPrefix(
                "hermes-web-ui stop 2>&1 || true",
                onOutput = { Log.d(TAG, "[stop] $it") },
            )
            Thread.sleep(1000)
            if (checkServerHealth()) {
                Log.w(TAG, "Server still alive, force killing")
                forceKillByPort()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop() failed: ${e.message}")
            forceKillByPort()
        }
    }

    /**
     * 兜底：在 proot 里按端口找 PID 并 kill -9。
     */
    private fun forceKillByPort() {
        try {
            serverMgr.runInPrefix(
                "kill -9 \$(lsof -tiTCP:$STUDIO_PORT -sTCP:LISTEN 2>/dev/null) 2>/dev/null || " +
                    "fuser -k $STUDIO_PORT/tcp 2>/dev/null || true",
                onOutput = { Log.d(TAG, "[forcekill] $it") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "forceKillByPort failed: ${e.message}")
        }
    }

    // ── Health check ────────────────────────────────────────────────────────

    private fun isPortInUse(port: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("localhost", port), 500)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun waitForHealth(onProgress: (String) -> Unit): Boolean {
        // hermes-web-ui 是 daemonizing CLI：CLI 进程启动服务后自己退出（code 0），
        // 实际服务作为子进程运行。初始等待 5s 让服务有时间开始监听端口。
        try {
            Thread.sleep(5000)
        } catch (_: InterruptedException) {
            return false
        }
        val deadline = System.currentTimeMillis() + HEALTH_STARTUP_TIMEOUT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (checkServerHealth()) {
                onProgress("Health check passed on attempt $attempt")
                return true
            }
            try {
                Thread.sleep(HEALTH_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun checkServerHealth(): Boolean {
        // 优先尝试 /health 端点（如果存在，返回 200 表示完全就绪）
        try {
            val url = URL("$STUDIO_BASE_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            conn.useCaches = false
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) return true
        } catch (_: Exception) {}
        // 回退：检查端口是否能建立 TCP 连接（hermes-web-ui 可能没有 /health
        // 端点，但只要端口在监听就说明服务起来了）
        return isPortInUse(STUDIO_PORT)
    }
}
