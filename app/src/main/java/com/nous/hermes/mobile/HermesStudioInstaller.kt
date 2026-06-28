package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Installs and runs the hermes-web-ui npm package — the web dashboard
 * for Hermes Agent (https://github.com/EKKOLearnAI/hermes-studio).
 *
 * Lifecycle:
 *   1. install()      — npm install -g hermes-web-ui  (one-time, ~30MB download)
 *   2. start()        — spawn `hermes-web-ui start` as a background process
 *   3. isRunning()    — poll whether the process is still alive
 *   4. stop()         — kill the process
 *
 * The server listens on http://localhost:8648 by default (port from
 * hermes-web-ui's bin/hermes-web-ui.mjs, configurable via PORT env).
 * ChatActivity loads that URL in a WebView.
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
    }

    private var serverProcess: Process? = null

    val isRunning: Boolean
        get() = serverProcess?.let {
            try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        } ?: false

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
     * Run `npm install -g hermes-web-ui` inside the Termux prefix.
     * Downloads ~30MB and compiles native modules (node-pty).
     * May take 1-3 minutes on first run.
     *
     * Returns true on success, false on failure.
     */
    fun install(onProgress: (String) -> Unit): Boolean {
        val serverMgr = HermesServerManager(context)
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
     * Spawn `hermes-web-ui start` as a background process. The server
     * listens on http://localhost:8648 by default. Must be called
     * before ChatActivity loads the URL.
     *
     * Returns true if the process started (does NOT verify it's
     * serving — that's the caller's responsibility).
     */
    fun start(onProgress: (String) -> Unit): Boolean {
        if (isRunning) {
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

        val env = HermesServerManager.buildEnvMap(context, paths).toMutableMap()
        // hermes-web-ui reads PORT from env (default 8648)
        env["PORT"] = STUDIO_PORT.toString()
        env["NODE_ENV"] = "production"
        env["HOME"] = homeDir

        val shell = "$prefix/bin/sh"
        val pb = ProcessBuilder(shell, "-c", "exec hermes-web-ui start 2>&1")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(homeDir))
        pb.redirectErrorStream(true)

        return try {
            serverProcess = pb.start()
            // Drain stdout in a background thread so the process doesn't
            // block on pipe-full. Log lines for debugging.
            Thread {
                val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    Log.d(TAG, "[hermes-web-ui] $line")
                    line = reader.readLine()
                }
            }.start()
            // Give it a moment to bind the port
            Thread.sleep(2000)
            onProgress("hermes-web-ui started on $STUDIO_BASE_URL")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hermes-web-ui", e)
            false
        }
    }

    /**
     * Kill the hermes-web-ui server process. Called when ChatActivity
     * is destroyed (to save memory) or when the user explicitly stops.
     */
    fun stop() {
        serverProcess?.let {
            it.destroy()
            serverProcess = null
            Log.i(TAG, "hermes-web-ui stopped")
        }
    }
}
