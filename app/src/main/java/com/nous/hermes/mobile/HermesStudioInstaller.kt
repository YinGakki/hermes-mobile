package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs and runs the hermes-web-ui npm package — the web dashboard
 * for Hermes Agent (https://github.com/EKKOLearnAI/hermes-studio).
 *
 * Architecture inspired by openclaw-termux's GatewayService.kt:
 *   - Daemon spawns detached (CLI handles its own PID file)
 *   - Watchdog thread monitors /health endpoint
 *   - Auto-restart on crash (max 5, exponential backoff)
 *   - Graceful stop: SIGTERM → 3s → SIGKILL
 *   - Bionic Bypass injected via NODE_OPTIONS
 *
 * License note: hermes-web-ui is BSL-1.1 (Business Source License).
 * The bionic-bypass.js is MIT-licensed (adapted from openclaw-termux).
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
        private const val GRACE_PERIOD_MS = 60000L  // >60s = reset restart count
        private const val WATCHDOG_INTERVAL_MS = 15000L
        private const val WATCHDOG_INITIAL_DELAY_MS = 45000L

        // Health check config (from openclaw-termux gateway_service.dart)
        private const val HEALTH_FIRST_DELAY_MS = 30000L  // 30s before first check
        private const val HEALTH_INTERVAL_MS = 5000L
        private const val HEALTH_GRACE_MS = 120000L  // 120s before declaring dead
        private const val HEALTH_STARTUP_TIMEOUT_MS = 30000L

        // Process stop config
        private const val STOP_GRACE_MS = 3000L  // SIGTERM → 3s → SIGKILL
    }

    private val serverMgr = HermesServerManager(context)
    private var watchdogThread: Thread? = null
    @Volatile private var watchdogRunning = false
    @Volatile private var restartCount = 0
    @Volatile private var serverStartTime = 0L

    // Bionic bypass script path (extracted from assets)
    private var bypassScriptPath: String? = null

    fun isInstalled(): Boolean {
        val prefix = BootstrapInstaller.getPaths(context).prefixDir
        return File(prefix, "bin/hermes-web-ui").exists()
    }

    val isRunning: Boolean
        get() = checkServerHealth()

    /**
     * Run `npm install -g hermes-web-ui` inside the Termux prefix.
     * Downloads ~30MB and compiles native modules (node-pty).
     */
    fun install(onProgress: (String) -> Unit): Boolean {
        onProgress("Installing hermes-web-ui via npm (this may take 1-3 min)…")

        val ok = serverMgr.runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "npm install -g hermes-web-ui",
        ) {
            val cmd = """
                export PATH="${'$'}{PREFIX}/bin:${'$'}PATH" &&
                npm install -g $NPM_PACKAGE 2>&1
            """.trimIndent()
            serverMgr.runInPrefix(cmd, onOutput = { onProgress(it) }) == 0 &&
                isInstalled()
        }
        if (!ok) {
            Log.e(TAG, "npm install -g hermes-web-ui failed after retries")
            return false
        }
        // Extract bionic bypass script to prefix for later use
        extractBionicBypass()
        onProgress("hermes-web-ui installed")
        return true
    }

    /**
     * Extract bionic-bypass.js from assets to $PREFIX/share/bionic-bypass.js
     * so it can be injected via NODE_OPTIONS when starting the server.
     */
    private fun extractBionicBypass() {
        val prefix = BootstrapInstaller.getPaths(context).prefixDir
        val target = File(prefix, "share/bionic-bypass.js")
        target.parentFile?.mkdirs()
        try {
            context.assets.open("bionic-bypass.js").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            bypassScriptPath = target.absolutePath
            Log.i(TAG, "Bionic bypass extracted to $bypassScriptPath")
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract bionic-bypass.js: ${e.message}")
            // Non-fatal — server will run without bypass, may crash later
        }
    }

    /**
     * Start hermes-web-ui daemon + watchdog.
     *
     * Flow:
     *   1. Spawn `hermes-web-ui` (CLI daemonizes itself, writes PID file)
     *   2. Poll /health until 200 or 30s timeout
     *   3. Start watchdog thread (monitors health, auto-restarts on crash)
     *
     * Returns true if server became healthy within timeout.
     */
    fun start(onProgress: (String) -> Unit): Boolean {
        if (checkServerHealth()) {
            onProgress("hermes-web-ui already running")
            startWatchdog(onProgress)
            return true
        }
        if (!isInstalled()) {
            Log.e(TAG, "Cannot start — hermes-web-ui not installed")
            return false
        }

        // Ensure bionic bypass is extracted
        if (bypassScriptPath == null) extractBionicBypass()

        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val homeDir = paths.homeDir

        // Build environment with Bionic Bypass injection.
        // NODE_OPTIONS=--require ensures the bypass runs before any
        // other code, patching os.networkInterfaces() etc.
        val env = serverMgr.buildEnvironment(paths).toMutableMap().apply {
            put("PORT", STUDIO_PORT.toString())
            put("NODE_ENV", "production")
            put("HOME", homeDir)
            put("HERMES_WEB_UI_HOME", "$homeDir/.hermes-web-ui")
            // Bionic Bypass — critical for Android
            bypassScriptPath?.let { path ->
                put("NODE_OPTIONS", "--require $path")
                Log.i(TAG, "Injecting bionic bypass: NODE_OPTIONS=--require $path")
            }
        }

        val shell = "$prefix/bin/sh"
        val pb = ProcessBuilder(
            shell, "-c",
            "nohup hermes-web-ui </dev/null >/dev/null 2>&1 & disown"
        )
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(homeDir))
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
        pb.redirectErrorStream(true)

        return try {
            val proc = pb.start()
            proc.inputStream.close()
            Thread.sleep(500)
            proc.destroyForcibly()
            // Wait for server to be healthy
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
     * Watchdog thread — monitors /health every 15s, auto-restarts on crash.
     *
     * Based on openclaw-termux GatewayService.kt's startWatchdog():
     *   - 45s initial delay (let server warm up)
     *   - 15s poll interval
     *   - Max 5 restarts with exponential backoff (2s → 4s → 8s → 16s)
     *   - If server runs >60s, reset restart count (was a transient crash)
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

                    // Reset restart count if server ran for a while
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
                    val restarted = restartServer()
                    if (restarted) {
                        serverStartTime = System.currentTimeMillis()
                        onProgress("Watchdog: hermes-web-ui restarted successfully")
                    } else {
                        Log.e(TAG, "Watchdog: restart $restartCount failed")
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
     * Restart the server after a crash. Re-spawns the daemon.
     */
    private fun restartServer(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val homeDir = paths.homeDir

        val env = serverMgr.buildEnvironment(paths).toMutableMap().apply {
            put("PORT", STUDIO_PORT.toString())
            put("NODE_ENV", "production")
            put("HOME", homeDir)
            put("HERMES_WEB_UI_HOME", "$homeDir/.hermes-web-ui")
            bypassScriptPath?.let { put("NODE_OPTIONS", "--require $it") }
        }

        val shell = "$prefix/bin/sh"
        val pb = ProcessBuilder(shell, "-c", "nohup hermes-web-ui </dev/null >/dev/null 2>&1 & disown")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(homeDir))
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
        pb.redirectErrorStream(true)

        return try {
            val proc = pb.start()
            proc.inputStream.close()
            Thread.sleep(1000)
            proc.destroyForcibly()
            // Brief health check (don't block watchdog too long)
            val deadline = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < deadline) {
                if (checkServerHealth()) return true
                Thread.sleep(1000)
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "restartServer failed", e)
            false
        }
    }

    /**
     * Stop the server gracefully.
     *
     * Based on openclaw-termux GatewayService.kt:
     *   1. Stop the watchdog (so it doesn't restart during shutdown)
     *   2. Invoke `hermes-web-ui stop` (SIGTERM via CLI)
     *   3. If still alive after STOP_GRACE_MS, force kill
     */
    fun stop() {
        stopWatchdog()
        if (!isInstalled()) return
        try {
            Thread {
                try {
                    // Graceful stop via CLI (reads PID file, sends SIGTERM)
                    val code = serverMgr.runInPrefix(
                        "hermes-web-ui stop 2>&1 || true",
                        onOutput = { Log.d(TAG, "[stop] $it") },
                    )
                    Log.i(TAG, "hermes-web-ui stop exited with code $code")

                    // If still alive after grace period, force kill via port
                    Thread.sleep(STOP_GRACE_MS)
                    if (checkServerHealth()) {
                        Log.w(TAG, "Server still alive after ${STOP_GRACE_MS}ms, force killing")
                        forceKillByPort()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "hermes-web-ui stop failed: ${e.message}")
                    forceKillByPort()
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "stop() failed", e)
        }
    }

    /**
     * Last resort: find PID listening on STUDIO_PORT and kill -9 it.
     */
    private fun forceKillByPort() {
        try {
            serverMgr.runInPrefix(
                "kill -9 ${'$'}(lsof -tiTCP:$STUDIO_PORT -sTCP:LISTEN 2>/dev/null) 2>/dev/null || true",
                onOutput = { Log.d(TAG, "[forcekill] $it") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "forceKillByPort failed: ${e.message}")
        }
    }

    // ── Health check ────────────────────────────────────────────────────────

    /**
     * Poll /health until 200 or timeout.
     * Uses generous grace period from openclaw-termux:
     *   - 30s before first check (let Node.js + proot warm up)
     *   - 120s total before declaring dead
     */
    private fun waitForHealth(onProgress: (String) -> Unit): Boolean {
        // Initial delay — don't check too early
        try {
            Thread.sleep(3000)  // 3s initial (shorter than watchdog's 30s)
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

    /**
     * Hit /health endpoint. Returns true if HTTP 200.
     */
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
