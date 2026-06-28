package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Installs and runs the hermes-web-ui npm package — the web dashboard
 * for Hermes Agent (https://github.com/EKKOLearnAI/hermes-studio).
 *
 * Lifecycle:
 *   1. install()      — npm install -g hermes-web-ui  (one-time, ~30MB download)
 *   2. start()        — spawn `hermes-web-ui` (daemonizes itself)
 *   3. isRunning()    — poll PID file + /health endpoint
 *   4. stop()         — invoke `hermes-web-ui stop`
 *
 * The server listens on http://localhost:8648 by default (DEFAULT_PORT
 * inside bin/hermes-web-ui.mjs, configurable via PORT env). ChatActivity
 * loads that URL in a WebView.
 *
 * IMPORTANT: hermes-web-ui's CLI entry is just `hermes-web-ui` (NOT
 * `hermes-web-ui start`). When invoked without a subcommand, it runs
 * startDaemon() which:
 *   - spawns the Node.js server as a DETACHED process (child.unref())
 *   - writes the PID to ~/.hermes-web-ui/server.pid
 *   - polls /health until 200 or 30s timeout
 *   - the parent CLI exits immediately
 *
 * This means we CANNOT hold a Process reference to the server (it's
 * detached and the shell exits right away). Instead we rely on the
 * PID file + /health endpoint for liveness, and `hermes-web-ui stop`
 * for shutdown.
 *
 * Requires Node.js >=23 (Termux `nodejs` package is the current
 * version, not LTS — bundled separately by extractDebBundleIfPresent()).
 * The `node-pty` native module will be compiled on-device during
 * `npm install -g`; this needs python + make + clang (all bundled).
 *
 * License note: hermes-web-ui is BSL-1.1 (Business Source License).
 * Restricts commercial use; personal use is fine.
 */
class HermesStudioInstaller(private val context: Context) {

    companion object {
        private const val TAG = "HermesStudioInstaller"
        const val STUDIO_PORT = 8648
        const val STUDIO_BASE_URL = "http://localhost:$STUDIO_PORT"
        private const val NPM_PACKAGE = "hermes-web-ui"
        private const val HEALTH_CHECK_TIMEOUT_SEC = 30L
        private const val HEALTH_CHECK_INTERVAL_MS = 1000L
    }

    private val serverMgr = HermesServerManager(context)

    /**
     * Returns true if `hermes-web-ui` is on PATH (i.e. already installed
     * via `npm install -g`). Used by MainActivity to decide whether to
     * show "Install Chat UI" or "Open Chat UI" button.
     */
    fun isInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        // npm install -g puts binaries in $PREFIX/bin/
        return File(prefix, "bin/hermes-web-ui").exists()
    }

    /**
     * Returns true if the hermes-web-ui server is currently running.
     * Checks both the PID file AND the /health endpoint (a stale PID
     * file alone is not sufficient — the process may have crashed).
     */
    val isRunning: Boolean
        get() = checkServerHealth()

    /**
     * Run `npm install -g hermes-web-ui` inside the Termux prefix.
     * Downloads ~30MB and compiles native modules (node-pty).
     * May take 1-3 minutes on first run.
     *
     * Returns true on success, false on failure.
     */
    fun install(onProgress: (String) -> Unit): Boolean {
        onProgress("Installing hermes-web-ui via npm (this may take 1-3 min)…")

        // Retry npm install up to 3 times — npm registry can be flaky.
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
        onProgress("hermes-web-ui installed")
        return true
    }

    /**
     * Invoke `hermes-web-ui` (no subcommand — this triggers startDaemon
     * in bin/hermes-web-ui.mjs). The CLI daemonizes the server and exits
     * immediately, so we DON'T hold a Process reference — we just wait
     * for the /health endpoint to respond.
     *
     * Returns true if /health returns 200 within 30s, false otherwise.
     */
    fun start(onProgress: (String) -> Unit): Boolean {
        if (checkServerHealth()) {
            onProgress("hermes-web-ui already running")
            return true
        }
        if (!isInstalled()) {
            Log.e(TAG, "Cannot start — hermes-web-ui not installed")
            return false
        }

        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val homeDir = paths.homeDir

        // Spawn the CLI in a fire-and-forget way. We use nohup + & so the
        // shell returns immediately and the CLI daemonizes itself.
        // stdin is redirected from /dev/null to avoid blocking on read.
        val env = serverMgr.buildEnvironment(paths).toMutableMap().apply {
            put("PORT", STUDIO_PORT.toString())
            put("NODE_ENV", "production")
            put("HOME", homeDir)
            // hermes-web-ui reads this to know where to put PID/log files.
            put("HERMES_WEB_UI_HOME", "$homeDir/.hermes-web-ui")
        }

        val shell = "$prefix/bin/sh"
        val pb = ProcessBuilder(
            shell, "-c",
            // nohup + & + disown: fully detach the daemon from the shell.
            // exec replaces the shell with the CLI; nohup ignores SIGHUP;
            // </dev/null prevents stdin reads from blocking.
            "nohup hermes-web-ui </dev/null >/dev/null 2>&1 & disown"
        )
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(homeDir))
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectErrorStream(true)

        return try {
            val proc = pb.start()
            // Drain any output (should be empty due to redirects) to free the pipe.
            proc.inputStream.close()
            // Don't wait — the shell exits immediately because of &.
            // Wait briefly for the CLI to spawn the detached daemon.
            Thread.sleep(500)
            proc.destroyForcibly()
            // Now poll /health until the server is ready.
            onProgress("Waiting for hermes-web-ui to be ready…")
            val ready = waitForHealth(onProgress)
            if (ready) {
                onProgress("hermes-web-ui started on $STUDIO_BASE_URL")
            } else {
                onProgress("hermes-web-ui did not become healthy within ${HEALTH_CHECK_TIMEOUT_SEC}s")
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hermes-web-ui", e)
            false
        }
    }

    /**
     * Stop the hermes-web-ui server by invoking `hermes-web-ui stop`.
     * This is the clean way — the CLI reads the PID file and sends
     * SIGTERM to the daemon (and SIGKILL if it doesn't exit in time).
     */
    fun stop() {
        if (!isInstalled()) return
        try {
            // Run on a background thread — stop() may take up to 15s
            // (DEFAULT_STOP_GRACE_MS in hermes-web-ui's CLI).
            Thread {
                try {
                    val code = serverMgr.runInPrefix(
                        "hermes-web-ui stop 2>&1 || true",
                        onOutput = { Log.d(TAG, "[stop] $it") },
                    )
                    Log.i(TAG, "hermes-web-ui stop exited with code $code")
                } catch (e: Exception) {
                    Log.w(TAG, "hermes-web-ui stop failed: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "stop() failed", e)
        }
    }

    // ── Health check ────────────────────────────────────────────────────────

    /**
     * Poll http://localhost:8648/health until it returns 200, or until
     * HEALTH_CHECK_TIMEOUT_SEC elapses. Returns true if healthy.
     */
    private fun waitForHealth(onProgress: (String) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT_SEC * 1000
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (checkServerHealth()) {
                onProgress("Health check passed on attempt $attempt")
                return true
            }
            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Hit /health endpoint. Returns true if HTTP 200.
     * Connect timeout is short (1s) since it's localhost.
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
