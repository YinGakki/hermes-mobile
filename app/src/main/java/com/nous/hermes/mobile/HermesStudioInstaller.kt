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

        // Watchdog config (from openclaw-termux GatewayService.kt)
        private const val MAX_RESTARTS = 5
        private const val INITIAL_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 16000L
        private const val GRACE_PERIOD_MS = 60000L
        private const val WATCHDOG_INTERVAL_MS = 15000L
        private const val WATCHDOG_INITIAL_DELAY_MS = 45000L

        // Health check config
        private const val HEALTH_FIRST_DELAY_MS = 30000L
        private const val HEALTH_INTERVAL_MS = 5000L
        private const val HEALTH_GRACE_MS = 120000L
        private const val HEALTH_STARTUP_TIMEOUT_MS = 30000L
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
     * 安装 nodejs + npm（apt-get），然后 npm install -g hermes-web-ui。
     * 全程在 proot install 模式里执行。
     */
    fun install(onProgress: (String) -> Unit): Boolean {
        onProgress("Installing hermes-web-ui via npm (this may take 1-3 min)…")

        // 先确保 nodejs + npm 已装
        if (!serverMgr.isNodeInstalled()) {
            onProgress("Installing nodejs + npm via apt-get…")
            try {
                processManager.runInProotSync(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm 2>&1 | tail -20",
                    1800
                ) { onProgress(it) }
            } catch (e: Exception) {
                onProgress("错误：nodejs/npm 安装失败 — ${e.message}")
                return false
            }
        }

        val ok = serverMgr.runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "npm install -g hermes-web-ui",
        ) {
            // npm install -g 在 proot 里执行（rootfs 环境，PATH 已含 /usr/bin）
            val code = processManager.runInProotExitCode(
                "npm install -g $NPM_PACKAGE 2>&1", 1200
            ) { onProgress(it) }
            code == 0 && isInstalled()
        }
        if (!ok) {
            Log.e(TAG, "npm install -g hermes-web-ui failed after retries")
            return false
        }
        onProgress("hermes-web-ui installed")
        return true
    }

    /**
     * 启动 hermes-web-ui 守护进程 + watchdog。
     *
     * 用 proot gateway 模式启动（startProotProcess），保持 Process 引用存活。
     * proot --kill-on-exit 会在 proot 退出时杀子进程，所以必须保持 proot 进程
     * 不退出（由本类持有 studioProcess 引用）。
     */
    fun start(onProgress: (String) -> Unit): Boolean {
        // 重连：如果服务已在跑，直接接管
        if (checkServerHealth()) {
            onProgress("hermes-web-ui already running — reconnecting")
            serverStartTime = System.currentTimeMillis()
            restartCount = 0
            startWatchdog(onProgress)
            return true
        }
        if (!isInstalled()) {
            Log.e(TAG, "Cannot start — hermes-web-ui not installed")
            return false
        }

        // 端口占用检测
        if (isPortInUse(STUDIO_PORT)) {
            onProgress("Port $STUDIO_PORT is in use by another process")
            forceKillByPort()
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }

        return try {
            spawnStudioServer()
            onProgress("Waiting for hermes-web-ui to be ready…")
            val ready = waitForHealth(onProgress)
            if (ready) {
                serverStartTime = System.currentTimeMillis()
                restartCount = 0
                onProgress("hermes-web-ui started on $STUDIO_BASE_URL")
                startWatchdog(onProgress)
            } else {
                onProgress("hermes-web-ui did not become healthy within ${HEALTH_STARTUP_TIMEOUT_MS / 1000}s")
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hermes-web-ui", e)
            false
        }
    }

    /**
     * 通过 proot gateway 模式启动 hermes-web-ui，保持进程引用存活。
     */
    private fun spawnStudioServer() {
        stopStudioProcess()
        // hermes-web-ui 监听 PORT 环境变量；在 proot 里前台运行（proot 进程
        // 保持存活，--kill-on-exit 确保停止时清理子进程）。
        val cmd = "PORT=$STUDIO_PORT NODE_ENV=production exec hermes-web-ui 2>&1"
        val proc = processManager.startProotProcess(cmd)
        studioProcess = proc
        // 转发 stdout 到 logcat
        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[studio] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "hermes-web-ui exited with code: ${proc.waitFor()}")
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
                        spawnStudioServer()
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
        try {
            Thread.sleep(3000)
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
        return try {
            val url = URL("$STUDIO_BASE_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            conn.useCaches = false
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }
}
