package com.nous.hermes.mobile

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesMainActivity"
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
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var doneLayout: View
    private lateinit var openShellButton: Button
    private lateinit var chatButton: Button
    private lateinit var retryButton: Button

    // Managers
    private lateinit var serverManager: HermesServerManager
    private lateinit var studioInstaller: HermesStudioInstaller

    // Log capture for error dialogs
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

        // Step buttons
        btnProot.setOnClickListener { runStep("proot") }
        btnPython.setOnClickListener { runStep("python") }
        btnBuildDeps.setOnClickListener { runStep("buildDeps") }
        btnHermes.setOnClickListener { runStep("hermes") }
        btnInstallAll.setOnClickListener { runInstallAll() }

        // Done-screen buttons
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

        // Start only bootstrap extraction; steps are manual after that.
        extractBootstrap()
    }

    override fun onDestroy() {
        super.onDestroy()
        serverManager.stopHermes()
        studioInstaller.stop()
        stopService(Intent(this, HermesForegroundService::class.java))
    }

    // ── Bootstrap ───────────────────────────────────────────────────────────

    private fun extractBootstrap() {
        logView.text = ""
        Thread {
            try {
                if (!BootstrapInstaller.isBootstrapInstalled(this)) {
                    runOnUiThread { setStatus(getString(R.string.status_extracting_bootstrap)) }
                    BootstrapInstaller.install(this) { msg ->
                        runOnUiThread { statusText.text = msg }
                    }
                }
                // Stage bundled debs (if APK ships them)
                serverManager.extractDebBundleIfPresent { msg ->
                    runOnUiThread { appendLog(msg) }
                }
                runOnUiThread { showSteps() }
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                runOnUiThread {
                    showError("Bootstrap failed: ${e.message ?: "Unknown error"}")
                }
            }
        }.start()
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

    // ── Step runner (individual or all-in-one) ──────────────────────────────

    private fun runStep(step: String) {
        setStepButtonState(step, true)
        Thread {
            try {
                when (step) {
                    "proot" -> installProot()
                    "python" -> installPython()
                    "buildDeps" -> installBuildDeps()
                    "hermes" -> installHermes()
                }
                runOnUiThread {
                    refreshStepButtons()
                    if (allStepsDone()) showDoneScreen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Step $step failed", e)
                val tail = synchronized(recentLog) { recentLog.joinToString("\n") }
                runOnUiThread {
                    setStepButtonState(step, false)
                    showStepError(step, e.message ?: "Unknown error", tail)
                }
            }
        }.start()
    }

    private fun runInstallAll() {
        btnInstallAll.isEnabled = false
        btnInstallAll.text = getString(R.string.step_installing)
        logView.text = ""
        Thread {
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
                    refreshStepButtons()
                    if (allStepsDone()) showDoneScreen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Install-all failed", e)
                val tail = synchronized(recentLog) { recentLog.joinToString("\n") }
                runOnUiThread {
                    btnInstallAll.isEnabled = true
                    btnInstallAll.text = getString(R.string.step_install_all)
                    showStepError("install-all", e.message ?: "Unknown error", tail)
                }
            }
        }.start()
    }

    // ── Individual install helpers ──────────────────────────────────────────

    @Throws(Exception::class)
    private fun installProot() {
        val ok = serverManager.installProot { msg -> runOnUiThread { appendLog(msg) } }
        if (!ok) throw RuntimeException("Failed to install proot")
    }

    @Throws(Exception::class)
    private fun installPython() {
        val ok = serverManager.installPython { msg -> runOnUiThread { appendLog(msg) } }
        if (!ok) throw RuntimeException("Failed to install Python")
    }

    @Throws(Exception::class)
    private fun installBuildDeps() {
        val ok = serverManager.installHermesBuildDeps { msg -> runOnUiThread { appendLog(msg) } }
        if (!ok) throw RuntimeException("Failed to install build dependencies")
    }

    @Throws(Exception::class)
    private fun installHermes() {
        val ok = serverManager.installHermes(
            onProgress = { msg -> runOnUiThread { appendLog(msg) } },
            onNeedCompile = { runOnUiThread { askUserAboutCompile() } },
        )
        if (!ok) throw RuntimeException("Failed to install Hermes Agent")
        // Skeleton config + health check (best-effort)
        serverManager.configureHermesSkeleton()
        serverManager.healthCheck { msg -> runOnUiThread { appendLog(msg) } }
    }

    // ── Button state helpers ────────────────────────────────────────────────

    private fun refreshStepButtons() {
        val prootDone = serverManager.isProotInstalled()
        btnProot.text = if (prootDone) getString(R.string.step_done) else getString(R.string.step_proot)
        btnProot.isEnabled = !prootDone

        val pythonDone = serverManager.isPythonInstalled()
        btnPython.text = if (pythonDone) getString(R.string.step_done) else getString(R.string.step_python)
        btnPython.isEnabled = !pythonDone

        val depsDone = isBuildDepsInstalled()
        btnBuildDeps.text = if (depsDone) getString(R.string.step_done) else getString(R.string.step_build_deps)
        btnBuildDeps.isEnabled = !depsDone

        val hermesDone = serverManager.isHermesInstalled()
        btnHermes.text = if (hermesDone) getString(R.string.step_done) else getString(R.string.step_hermes)
        btnHermes.isEnabled = !hermesDone

        val allDone = prootDone && pythonDone && depsDone && hermesDone
        btnInstallAll.isEnabled = !allDone
        btnInstallAll.text = if (allDone) getString(R.string.step_done) else getString(R.string.step_install_all)
    }

    private fun setStepButtonState(step: String, installing: Boolean) {
        val btn = when (step) {
            "proot" -> btnProot
            "python" -> btnPython
            "buildDeps" -> btnBuildDeps
            "hermes" -> btnHermes
            else -> return
        }
        btn.isEnabled = !installing
        // Only change text while installing; on success/failure
        // refreshStepButtons() restores the correct label based on
        // is*Installed() state.
        if (installing) {
            btn.text = getString(R.string.step_installing)
        }
    }

    private fun isBuildDepsInstalled(): Boolean {
        val prefix = BootstrapInstaller.getPaths(this).prefixDir
        return File(prefix, "var/.hermes-deps-installed").exists()
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

    // ── Chat UI (hermes-web-ui) ─────────────────────────────────────────────

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
        Thread {
            val ok = studioInstaller.install { msg ->
                runOnUiThread {
                    dialog.setMessage(msg)
                    appendLog(msg)
                }
            }
            runOnUiThread {
                dialog.dismiss()
                if (ok) {
                    updateChatButtonLabel()
                    startChatServerAndOpen()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error_title)
                        .setMessage(getString(R.string.chat_install_failed))
                        .setPositiveButton(R.string.retry) { _, _ -> installChatUi() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }.start()
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
        Thread {
            val ok = studioInstaller.start { msg -> runOnUiThread { dialog.setMessage(msg) } }
            runOnUiThread {
                dialog.dismiss()
                if (ok) openChatWebView() else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error_title)
                        .setMessage(getString(R.string.chat_start_failed))
                        .setPositiveButton(R.string.retry) { _, _ -> startChatServerAndOpen() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }.start()
    }

    private fun openChatWebView() {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_BASE_URL, HermesStudioInstaller.STUDIO_BASE_URL)
        }
        startActivity(intent)
    }

    // ── Dialogs & UI helpers ────────────────────────────────────────────────

    private fun showShellInstructions() {
        val paths = BootstrapInstaller.getPaths(this)
        val msg = """
            |Hermes Agent is installed at:
            |${paths.prefixDir}
            |
            |To start chatting, install Termux from F-Droid, then run:
            |
            |    export PATH=${paths.prefixDir}/bin:${'$'}PATH
            |    export HOME=${paths.homeDir}
            |    cd ${paths.homeDir}/hermes-agent
            |    . .venv/bin/activate
            |    hermes setup
            |    hermes
            |
            |Or run `hermes setup --portal` to use Nous Portal (free OAuth).
        """.trimMargin()
        AlertDialog.Builder(this)
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
            else -> step
        }
        val msg = buildString {
            append("步骤 [$stepName] 失败\n\n")
            append("错误: $error\n\n")
            append("最后日志:\n")
            if (tail.isBlank()) {
                append("  (无日志)\n")
            } else {
                tail.lines().forEach { append("  ").append(it).append('\n') }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(msg)
            .setPositiveButton(R.string.retry) { _, _ -> runStep(step) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> restartFromBootstrap() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun askUserAboutCompile(): Boolean {
        val latch = CountDownLatch(1)
        var approved = false
        runOnUiThread {
            AlertDialog.Builder(this)
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
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Log.w(TAG, "askUserAboutCompile interrupted — defaulting to abort")
            return false
        }
        return approved
    }

    // ── Progress / log helpers ──────────────────────────────────────────────

    private fun setStatus(text: String) {
        runOnUiThread {
            statusText.text = text
            statusText.visibility = View.VISIBLE
        }
    }

    private fun appendLog(text: String) {
        synchronized(recentLog) {
            recentLog.addLast(text)
            while (recentLog.size > 30) recentLog.pollFirst()
        }
        runOnUiThread {
            logView.append("$text\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
