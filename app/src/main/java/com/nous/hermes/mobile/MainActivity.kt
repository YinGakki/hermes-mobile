package com.nous.hermes.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesMainActivity"
        private const val COMPILE_DIALOG_TIMEOUT_SEC = 120L
    }

    // Views
    private lateinit var loadingOverlay: View
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var stepsContainer: LinearLayout
    private lateinit var btnProot: Button
    private lateinit var btnPython: Button
    private lateinit var btnBuildDeps: Button
    private lateinit var btnHermes: Button
    private lateinit var btnInstallAll: Button
    private lateinit var spinnerProot: ProgressBar
    private lateinit var spinnerPython: ProgressBar
    private lateinit var spinnerBuildDeps: ProgressBar
    private lateinit var spinnerHermes: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var doneLayout: View
    private lateinit var openShellButton: Button
    private lateinit var chatButton: Button
    private lateinit var retryButton: Button

    // Managers
    private lateinit var serverManager: HermesServerManager
    private lateinit var studioInstaller: HermesStudioInstaller

    @Volatile private var isInstallInProgress = false
    @Volatile private var activeThread: Thread? = null
    private var activeProgressDialog: android.app.ProgressDialog? = null
    private val recentLog = java.util.ArrayDeque<String>(30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        stepsContainer = findViewById(R.id.stepsContainer)
        btnProot = findViewById(R.id.btnProot)
        btnPython = findViewById(R.id.btnPython)
        btnBuildDeps = findViewById(R.id.btnBuildDeps)
        btnHermes = findViewById(R.id.btnHermes)
        btnInstallAll = findViewById(R.id.btnInstallAll)
        spinnerProot = findViewById(R.id.spinnerProot)
        spinnerPython = findViewById(R.id.spinnerPython)
        spinnerBuildDeps = findViewById(R.id.spinnerBuildDeps)
        spinnerHermes = findViewById(R.id.spinnerHermes)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        doneLayout = findViewById(R.id.doneLayout)
        openShellButton = findViewById(R.id.openShellButton)
        chatButton = findViewById(R.id.chatButton)
        retryButton = findViewById(R.id.retryButton)

        serverManager = HermesServerManager(this)
        studioInstaller = HermesStudioInstaller(this)

        requestBatteryOptimizationExemption()
        startForegroundService()

        btnProot.setOnClickListener { runStep("proot") }
        btnPython.setOnClickListener { runStep("python") }
        btnBuildDeps.setOnClickListener { runStep("buildDeps") }
        btnHermes.setOnClickListener { runStep("hermes") }
        btnInstallAll.setOnClickListener { runInstallAll() }

        openShellButton.setOnClickListener {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.termux")
                if (intent != null) startActivity(intent) else showShellInstructions()
            } catch (e: Exception) {
                showShellInstructions()
            }
        }
        chatButton.setOnClickListener { onChatButtonClicked() }
        retryButton.setOnClickListener { restartFromBootstrap() }

        extractBootstrap()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeThread?.interrupt()
        activeThread = null
        activeProgressDialog?.dismiss()
        activeProgressDialog = null
        serverManager.stopHermes()
        studioInstaller.stop()
        stopService(Intent(this, HermesForegroundService::class.java))
    }

    @Deprecated("Suppress deprecation warning for onBackPressed")
    override fun onBackPressed() {
        if (isInstallInProgress) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_exit_title)
                .setMessage(R.string.confirm_exit_msg)
                .setPositiveButton(R.string.cancel, null)
                .setNegativeButton(R.string.action_exit) { _, _ ->
                    activeThread?.interrupt()
                    activeProgressDialog?.dismiss()
                    super.onBackPressed()
                }
                .setCancelable(false)
                .show()
            return
        }
        super.onBackPressed()
    }

    // ── Bootstrap ───────────────────────────────────────────────────────────

    private fun extractBootstrap() {
        logView.text = ""
        activeThread = Thread {
            try {
                if (!BootstrapInstaller.isBootstrapInstalled(this)) {
                    runOnUiThread { setStatus(getString(R.string.status_extracting_bootstrap)) }
                    BootstrapInstaller.install(this) { msg ->
                        runOnUiThread { statusText.text = msg }
                    }
                }
                // Always refresh system config (resolv.conf, passwd, timezone)
                // even if bootstrap was already installed — Android may have
                // cleared these files between launches.
                BootstrapInstaller.ensureSystemConfig(this)
                serverManager.extractDebBundleIfPresent { msg -> appendLog(msg) }
                runOnUiThread { showSteps() }
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                runOnUiThread {
                    showError("Bootstrap failed: ${e.message ?: "Unknown error"}")
                }
            }
        }.also { it.start() }
    }

    private fun restartFromBootstrap() {
        doneLayout.visibility = View.GONE
        stepsContainer.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.status_initializing)
        logView.text = ""
        extractBootstrap()
    }

    private fun showSteps() {
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        statusDetail.visibility = View.GONE
        stepsContainer.visibility = View.VISIBLE
        refreshStepButtons()
    }

    // ── Step runner ─────────────────────────────────────────────────────────

    private fun tryAcquireInstallLock(): Boolean {
        if (isInstallInProgress) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.install_in_progress_title)
                .setMessage(R.string.install_in_progress_msg)
                .setPositiveButton(R.string.ok, null)
                .setCancelable(false)
                .show()
            return false
        }
        isInstallInProgress = true
        lastProgressTime = System.currentTimeMillis()
        startHeartbeat()
        // Dim ALL buttons (alpha, NOT isEnabled=false, so they stay visible)
        btnProot.alpha = 0.35f
        btnPython.alpha = 0.35f
        btnBuildDeps.alpha = 0.35f
        btnHermes.alpha = 0.35f
        btnInstallAll.alpha = 0.35f
        btnProot.isEnabled = false
        btnPython.isEnabled = false
        btnBuildDeps.isEnabled = false
        btnHermes.isEnabled = false
        btnInstallAll.isEnabled = false
        return true
    }

    private fun releaseInstallLock() {
        isInstallInProgress = false
        stopHeartbeat()
        // Flush any remaining pending logs
        logUpdateHandler.removeCallbacks(logFlushRunnable)
        logUpdateHandler.post(logFlushRunnable)
        refreshStepButtons()
    }

    private fun runStep(step: String) {
        if (!tryAcquireInstallLock()) return
        setStepButtonState(step, true)
        activeThread = Thread {
            try {
                when (step) {
                    "proot" -> installProot()
                    "python" -> installPython()
                    "buildDeps" -> installBuildDeps()
                    "hermes" -> installHermes()
                }
                runOnUiThread {
                    releaseInstallLock()
                    if (allStepsDone()) showDoneScreen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Step $step failed", e)
                val tail = synchronized(recentLog) { recentLog.joinToString("\n") }
                runOnUiThread {
                    releaseInstallLock()
                    showStepError(step, e.message ?: "Unknown error", tail)
                }
            }
        }.also { it.start() }
    }

    private fun runInstallAll() {
        if (!tryAcquireInstallLock()) return
        btnInstallAll.text = getString(R.string.step_installing)
        logView.text = ""
        activeThread = Thread {
            try {
                if (!serverManager.isProotInstalled()) {
                    runOnUiThread { setStatus(getString(R.string.step_proot)) }
                    installProot()
                }
                if (!serverManager.isPythonInstalled()) {
                    runOnUiThread { setStatus(getString(R.string.step_python)) }
                    installPython()
                }
                if (!isBuildDepsInstalled()) {
                    runOnUiThread { setStatus(getString(R.string.step_build_deps)) }
                    installBuildDeps()
                }
                if (!serverManager.isHermesInstalled()) {
                    runOnUiThread { setStatus(getString(R.string.step_hermes)) }
                    installHermes()
                }
                runOnUiThread {
                    releaseInstallLock()
                    if (allStepsDone()) showDoneScreen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Install-all failed", e)
                val tail = synchronized(recentLog) { recentLog.joinToString("\n") }
                runOnUiThread {
                    releaseInstallLock()
                    showStepError("install-all", e.message ?: "Unknown error", tail)
                }
            }
        }.also { it.start() }
    }

    // ── Individual install helpers ──────────────────────────────────────────

    @Throws(Exception::class)
    private fun installProot() {
        val ok = serverManager.installProot { msg -> appendLog(msg) }
        if (!ok) throw RuntimeException("Failed to install proot")
        appendLog("✓ proot 已安装")
    }

    @Throws(Exception::class)
    private fun installPython() {
        val ok = serverManager.installPython { msg -> appendLog(msg) }
        if (!ok) throw RuntimeException("Failed to install Python")
        appendLog("✓ Python 已安装")
    }

    @Throws(Exception::class)
    private fun installBuildDeps() {
        val ok = serverManager.installHermesBuildDeps { msg -> appendLog(msg) }
        if (!ok) throw RuntimeException("Failed to install build dependencies")
        appendLog("✓ build deps 已安装")
    }

    @Throws(Exception::class)
    private fun installHermes() {
        val ok = serverManager.installHermes(
            onProgress = { msg -> appendLog(msg) },
            onNeedCompile = {
                var approved = false
                runOnUiThread { approved = askUserAboutCompile() }
                approved
            },
        )
        if (!ok) throw RuntimeException("Failed to install Hermes Agent")
        serverManager.configureHermesSkeleton()
        serverManager.healthCheck { msg -> appendLog(msg) }
        appendLog("✓ Hermes Agent 已安装")
    }

    // ── Button state helpers ────────────────────────────────────────────────

    private fun refreshStepButtons() {
        val prootDone = serverManager.isProotInstalled()
        btnProot.text = if (prootDone) getString(R.string.step_done) else getString(R.string.step_proot)
        btnProot.isEnabled = !prootDone && !isInstallInProgress
        btnProot.alpha = when {
            prootDone -> 0.6f
            isInstallInProgress -> 0.35f
            else -> 1f
        }
        spinnerProot.visibility = View.GONE

        val pythonDone = serverManager.isPythonInstalled()
        btnPython.text = if (pythonDone) getString(R.string.step_done) else getString(R.string.step_python)
        btnPython.isEnabled = !pythonDone && !isInstallInProgress
        btnPython.alpha = when {
            pythonDone -> 0.6f
            isInstallInProgress -> 0.35f
            else -> 1f
        }
        spinnerPython.visibility = View.GONE

        val depsDone = isBuildDepsInstalled()
        btnBuildDeps.text = if (depsDone) getString(R.string.step_done) else getString(R.string.step_build_deps)
        btnBuildDeps.isEnabled = !depsDone && !isInstallInProgress
        btnBuildDeps.alpha = when {
            depsDone -> 0.6f
            isInstallInProgress -> 0.35f
            else -> 1f
        }
        spinnerBuildDeps.visibility = View.GONE

        val hermesDone = serverManager.isHermesInstalled()
        btnHermes.text = if (hermesDone) getString(R.string.step_done) else getString(R.string.step_hermes)
        btnHermes.isEnabled = !hermesDone && !isInstallInProgress
        btnHermes.alpha = when {
            hermesDone -> 0.6f
            isInstallInProgress -> 0.35f
            else -> 1f
        }
        spinnerHermes.visibility = View.GONE

        val allDone = prootDone && pythonDone && depsDone && hermesDone
        btnInstallAll.isEnabled = !allDone && !isInstallInProgress
        btnInstallAll.text = if (allDone) getString(R.string.step_done) else getString(R.string.step_install_all)
        btnInstallAll.alpha = when {
            allDone -> 0.6f
            isInstallInProgress -> 0.35f
            else -> 1f
        }
    }

    private fun setStepButtonState(step: String, installing: Boolean) {
        val (btn, spinner) = when (step) {
            "proot" -> btnProot to spinnerProot
            "python" -> btnPython to spinnerPython
            "buildDeps" -> btnBuildDeps to spinnerBuildDeps
            "hermes" -> btnHermes to spinnerHermes
            else -> return
        }
        btn.isEnabled = !installing
        if (installing) {
            btn.text = getString(R.string.step_installing)
            btn.alpha = 1f  // keep fully visible
            spinner.visibility = View.VISIBLE
        }
    }

    private fun isBuildDepsInstalled(): Boolean {
        val prefix = BootstrapInstaller.getPaths(this).prefixDir
        val marker = File(prefix, "var/.hermes-deps-installed")
        // Must exist AND match current version — old markers from previous
        // APK versions (which had different package lists) are stale.
        return marker.exists() && marker.readText().trim() == "v2"
    }

    private fun allStepsDone(): Boolean {
        return serverManager.isProotInstalled()
                && serverManager.isPythonInstalled()
                && isBuildDepsInstalled()
                && serverManager.isHermesInstalled()
    }

    // ── Screen transitions ──────────────────────────────────────────────────

    private fun showDoneScreen() {
        stepsContainer.visibility = View.GONE
        doneLayout.visibility = View.VISIBLE
        updateChatButtonLabel()
    }

    // ── Chat UI ─────────────────────────────────────────────────────────────

    private fun updateChatButtonLabel() {
        chatButton.text = if (studioInstaller.isInstalled()) {
            getString(R.string.action_open_chat)
        } else {
            getString(R.string.action_install_chat)
        }
    }

    private fun onChatButtonClicked() {
        if (!studioInstaller.isInstalled()) {
            installChatUi()
            return
        }
        startChatServerAndOpen()
    }

    private fun installChatUi() {
        val dialog = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.chat_installing))
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        activeProgressDialog = dialog
        Thread {
            val ok = studioInstaller.install { msg ->
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.setMessage(msg)
                        appendLog(msg)
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing) {
                    dialog.dismiss()
                    activeProgressDialog = null
                    if (ok) {
                        updateChatButtonLabel()
                        startChatServerAndOpen()
                    } else {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.error_title)
                            .setMessage(getString(R.string.chat_install_failed))
                            .setPositiveButton(R.string.retry) { _, _ -> installChatUi() }
                            .setNegativeButton(R.string.cancel, null)
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }.also { activeThread = it; it.start() }
    }

    private fun startChatServerAndOpen() {
        if (studioInstaller.isRunning) {
            openChatWebView()
            return
        }
        val dialog = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.chat_starting))
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        activeProgressDialog = dialog
        Thread {
            val ok = studioInstaller.start { msg ->
                runOnUiThread {
                    if (!isFinishing) dialog.setMessage(msg)
                }
            }
            runOnUiThread {
                if (!isFinishing) {
                    dialog.dismiss()
                    activeProgressDialog = null
                    if (ok) openChatWebView() else {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.error_title)
                            .setMessage(getString(R.string.chat_start_failed))
                            .setPositiveButton(R.string.retry) { _, _ -> startChatServerAndOpen() }
                            .setNegativeButton(R.string.cancel, null)
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }.also { activeThread = it; it.start() }
    }

    private fun openChatWebView() {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_BASE_URL, HermesStudioInstaller.STUDIO_BASE_URL)
        }
        startActivity(intent)
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    private fun showShellInstructions() {
        val paths = BootstrapInstaller.getPaths(this)
        val msg = """
            |Hermes Agent is installed at:
            |${paths.prefixDir}
            |
            |To use it, open any terminal app (e.g. Termux from F-Droid)
            |and run:
            |
            |    export PATH=${paths.prefixDir}/bin:${'$'}PATH
            |    export HOME=${paths.homeDir}
            |    export LD_LIBRARY_PATH=${paths.prefixDir}/lib
            |    cd ${paths.homeDir}/hermes-agent
            |    . .venv/bin/activate
            |    hermes setup
            |    hermes
            |
            |Or run `hermes setup --portal` to use Nous Portal (free OAuth).
            |
            |Tip: You can also use the Chat UI button above — no terminal needed.
        """.trimMargin()
        MaterialAlertDialogBuilder(this)
            .setTitle("How to use Hermes")
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showStepError(step: String, error: String, tail: String) {
        val stepName = when (step) {
            "proot" -> "安装 proot"
            "python" -> "安装 Python"
            "buildDeps" -> "安装 build deps"
            "hermes" -> "安装 Hermes Agent"
            "install-all" -> "一键安装"
            else -> step
        }
        val fullError = buildString {
            append("步骤 [$stepName] 失败\n\n")
            append("错误: $error\n\n")
            append("最后日志:\n")
            if (tail.isBlank()) {
                append("  (无日志)\n")
            } else {
                tail.lines().forEach { append("  ").append(it).append('\n') }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(fullError)
            .setPositiveButton(R.string.retry) { _, _ ->
                if (step == "install-all") runInstallAll() else runStep(step)
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.action_copy_error) { _, _ ->
                copyToClipboard(fullError)
            }
            .setCancelable(false)
            .show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> restartFromBootstrap() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("hermes_error", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun askUserAboutCompile(): Boolean {
        val latch = CountDownLatch(1)
        var approved = false
        runOnUiThread {
            if (isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.compile_needed_title)
                .setMessage(R.string.compile_needed_message)
                .setPositiveButton(R.string.compile_continue) { _, _ ->
                    approved = true
                    latch.countDown()
                }
                .setNegativeButton(R.string.compile_abort) { _, _ ->
                    approved = false
                    latch.countDown()
                }
                .setCancelable(false)
                .show()
        }
        return try {
            latch.await(COMPILE_DIALOG_TIMEOUT_SEC, TimeUnit.SECONDS) && approved
        } catch (e: InterruptedException) {
            Log.w(TAG, "askUserAboutCompile interrupted — defaulting to abort")
            false
        }
    }

    // ── Log helpers ────────────────────────────────────────────────────────

    private fun setStatus(text: String) {
        runOnUiThread {
            statusText.text = text
            statusText.visibility = View.VISIBLE
        }
    }

    private val logUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingLogs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val logFlushRunnable = object : Runnable {
        override fun run() {
            if (pendingLogs.isEmpty()) {
                return
            }
            val sb = StringBuilder()
            var line = pendingLogs.poll()
            while (line != null) {
                sb.append(line).append('\n')
                line = pendingLogs.poll()
            }
            val text = sb.toString()
            logView.append(text)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var lastProgressTime = 0L

    private fun startHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (now - lastProgressTime > 5000 && isInstallInProgress) {
                    val secs = (now - lastProgressTime) / 1000
                    logView.append("… 仍在运行中 (${secs}s)\n")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
                heartbeatHandler.postDelayed(this, 5000)
            }
        }
        heartbeatHandler.postDelayed(heartbeatRunnable!!, 5000)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun appendLog(text: String) {
        synchronized(recentLog) {
            recentLog.addLast(text)
            while (recentLog.size > 30) recentLog.pollFirst()
        }
        lastProgressTime = System.currentTimeMillis()
        pendingLogs.add(text)
        // Batch flush every 200ms instead of per-line to avoid
        // flooding the main thread with UI updates
        if (pendingLogs.size >= 5) {
            logUpdateHandler.removeCallbacks(logFlushRunnable)
            logUpdateHandler.post(logFlushRunnable)
        } else {
            logUpdateHandler.removeCallbacks(logFlushRunnable)
            logUpdateHandler.postDelayed(logFlushRunnable, 200)
        }
    }

    // ── System helpers ──────────────────────────────────────────────────────

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, HermesForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
