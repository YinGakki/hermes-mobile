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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesMainActivity"
        private const val COMPILE_DIALOG_TIMEOUT_SEC = 120L
    }

    // Views
    private lateinit var installPage: View
    private lateinit var dashboardPage: View
    private lateinit var settingsPage: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var stepsContainer: LinearLayout
    private lateinit var btnProot: Button
    private lateinit var btnPython: Button
    private lateinit var btnBuildDeps: Button
    private lateinit var btnHermes: Button
    private lateinit var btnInstallAll: Button
    private lateinit var btnSaveEnv: View
    private lateinit var btnRestoreEnv: View
    private lateinit var spinnerProot: ProgressBar
    private lateinit var spinnerPython: ProgressBar
    private lateinit var spinnerBuildDeps: ProgressBar
    private lateinit var spinnerHermes: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: View
    private lateinit var logPanelTitle: TextView
    private lateinit var btnToggleLog: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var dashboardReady: View
    private lateinit var dashboardNotReady: View
    private lateinit var openShellButton: View
    private lateinit var serviceToggleButton: View
    private lateinit var serviceToggleTitle: TextView
    private lateinit var serviceToggleSubtitle: TextView
    private lateinit var chatButton: View
    private lateinit var retryButton: View
    private lateinit var chatCardTitle: TextView
    private lateinit var chatCardSubtitle: TextView
    private lateinit var versionFooter: TextView
    private lateinit var settingsVersionValue: TextView
    private lateinit var settingsRerun: View
    private lateinit var settingsBattery: View
    private lateinit var installProgressContainer: View
    private lateinit var installProgressBar: ProgressBar
    private lateinit var progressPercentText: TextView
    private lateinit var progressStepLabel: TextView

    // Managers
    private lateinit var serverManager: HermesServerManager
    private lateinit var studioInstaller: HermesStudioInstaller
    private lateinit var envBackup: HermesEnvBackup

    // SAF launchers — registered before onCreate completes so they can
    // receive callbacks even if the activity is recreated by config change.
    private lateinit var saveEnvLauncher: ActivityResultLauncher<String>
    private lateinit var restoreEnvLauncher: ActivityResultLauncher<Array<String>>

    @Volatile private var isInstallInProgress = false
    @Volatile private var activeThread: Thread? = null
    private var activeProgressDialog: android.app.Dialog? = null
    private val recentLog = java.util.ArrayDeque<String>(30)
    // 完整日志缓冲（不限大小），用于"显示全部"模式
    private val fullLog = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var showAllLogs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installPage = findViewById(R.id.installPage)
        dashboardPage = findViewById(R.id.dashboardPage)
        settingsPage = findViewById(R.id.settingsPage)
        bottomNav = findViewById(R.id.bottomNav)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        stepsContainer = findViewById(R.id.stepsContainer)
        btnProot = findViewById(R.id.btnProot)
        btnPython = findViewById(R.id.btnPython)
        btnBuildDeps = findViewById(R.id.btnBuildDeps)
        btnHermes = findViewById(R.id.btnHermes)
        btnInstallAll = findViewById(R.id.btnInstallAll)
        btnSaveEnv = findViewById(R.id.btnSaveEnv)
        btnRestoreEnv = findViewById(R.id.btnRestoreEnv)
        spinnerProot = findViewById(R.id.spinnerProot)
        spinnerPython = findViewById(R.id.spinnerPython)
        spinnerBuildDeps = findViewById(R.id.spinnerBuildDeps)
        spinnerHermes = findViewById(R.id.spinnerHermes)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        logPanel = findViewById(R.id.logPanel)
        logPanelTitle = findViewById(R.id.logPanelTitle)
        btnToggleLog = findViewById(R.id.btnToggleLog)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        dashboardReady = findViewById(R.id.dashboardReady)
        dashboardNotReady = findViewById(R.id.dashboardNotReady)
        openShellButton = findViewById(R.id.openShellButton)
        serviceToggleButton = findViewById(R.id.serviceToggleButton)
        serviceToggleTitle = findViewById(R.id.serviceToggleTitle)
        serviceToggleSubtitle = findViewById(R.id.serviceToggleSubtitle)
        chatButton = findViewById(R.id.chatButton)
        retryButton = findViewById(R.id.retryButton)
        chatCardTitle = findViewById(R.id.chatCardTitle)
        chatCardSubtitle = findViewById(R.id.chatCardSubtitle)
        versionFooter = findViewById(R.id.versionFooter)
        versionFooter.text = getString(R.string.dashboard_version, getVersionName())
        settingsVersionValue = findViewById(R.id.settingsVersionValue)
        settingsVersionValue.text = getVersionName()
        settingsRerun = findViewById(R.id.settingsRerun)
        settingsBattery = findViewById(R.id.settingsBattery)
        installProgressContainer = findViewById(R.id.installProgressContainer)
        installProgressBar = findViewById(R.id.installProgressBar)
        progressPercentText = findViewById(R.id.progressPercent)
        progressStepLabel = findViewById(R.id.progressStepLabel)

        // Bottom navigation: switch pages by toggling visibility.
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_install -> switchPage(installPage)
                R.id.nav_dashboard -> switchPage(dashboardPage)
                R.id.nav_settings -> switchPage(settingsPage)
            }
            true
        }
        // Show install page by default.
        switchPage(installPage)

        serverManager = HermesServerManager(this)
        studioInstaller = HermesStudioInstaller(this)
        envBackup = HermesEnvBackup(this, serverManager)

        // SAF launcher for "Save environment" — creates a new file at the
        // user-chosen location. Registered before any button click so it
        // survives activity recreation.
        saveEnvLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/gzip"),
        ) { uri: Uri? ->
            if (uri != null) runEnvBackup(uri)
        }
        // SAF launcher for "Restore environment" — opens an existing .tar.gz
        restoreEnvLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) runEnvRestore(uri)
        }

        requestBatteryOptimizationExemption()
        startForegroundService()

        // Grey out tabs that require Hermes to be installed (Dashboard / Settings).
        refreshNavTabs()

        btnProot.setOnClickListener { runStep("proot") }
        btnPython.setOnClickListener { runStep("python") }
        btnBuildDeps.setOnClickListener { runStep("buildDeps") }
        btnHermes.setOnClickListener { runStep("hermes") }
        btnInstallAll.setOnClickListener { runInstallAll() }
        btnSaveEnv.setOnClickListener { onSaveEnvClicked() }
        btnRestoreEnv.setOnClickListener { onRestoreEnvClicked() }

        openShellButton.setOnClickListener {
            // 新架构（proot+rootfs）下 app 自带完整 Linux 环境，
            // 不再需要跳转 Termux。直接显示使用说明。
            showShellInstructions()
        }
        serviceToggleButton.setOnClickListener { onServiceToggleClicked() }
        chatButton.setOnClickListener { onChatButtonClicked() }
        retryButton.setOnClickListener { restartFromBootstrap() }

        // Logs panel actions (embedded in install page)
        btnToggleLog.setOnClickListener {
            showAllLogs = !showAllLogs
            if (showAllLogs) {
                btnToggleLog.text = getString(R.string.log_show_current)
                logPanelTitle.text = getString(R.string.logs_copy_all)
                logScroll.layoutParams.height = (resources.displayMetrics.heightPixels * 2 / 3)
                rebuildFullLogView()
            } else {
                btnToggleLog.text = getString(R.string.log_show_all)
                logPanelTitle.text = getString(R.string.log_current)
                logScroll.layoutParams.height = (160 * resources.displayMetrics.density).toInt()
                rebuildCurrentLogView()
            }
            logScroll.requestLayout()
        }
        btnClearLogs.setOnClickListener {
            synchronized(recentLog) { recentLog.clear() }
            fullLog.clear()
            logView.text = ""
            Toast.makeText(this, R.string.logs_clear, Toast.LENGTH_SHORT).show()
        }
        btnCopyLogs.setOnClickListener {
            val text = if (showAllLogs) {
                fullLog.joinToString("\n")
            } else {
                synchronized(recentLog) { recentLog.joinToString("\n") }
            }
            if (text.isBlank()) {
                Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hermes_logs", text))
                Toast.makeText(this, R.string.settings_copied, Toast.LENGTH_SHORT).show()
            }
        }

        // Settings page actions
        settingsRerun.setOnClickListener { restartFromBootstrap() }
        settingsBattery.setOnClickListener { requestBatteryOptimizationExemption() }

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

    /**
     * Switch the visible page. Called from BottomNavigationView selection.
     * Hides all pages, shows the requested one, and refreshes dashboard
     * state (ready/not-ready placeholder) when navigating to the dashboard.
     */
    private fun switchPage(page: View) {
        installPage.visibility = if (page == installPage) View.VISIBLE else View.GONE
        dashboardPage.visibility = if (page == dashboardPage) View.VISIBLE else View.GONE
        settingsPage.visibility = if (page == settingsPage) View.VISIBLE else View.GONE
        if (page == dashboardPage) refreshDashboardState()
        if (page == settingsPage) refreshStepButtons()
    }

    /**
     * Enable/disable bottom-nav tabs based on install state.
     * Before Hermes is installed, only Dashboard is greyed out
     * (its actions need Hermes installed). Settings is always available:
     * it contains version info, battery opt, and env backup/restore —
     * the latter is meant to skip install entirely, so it must be
     * reachable before Hermes is installed.
     */
    private fun refreshNavTabs() {
        val installed = serverManager.isHermesInstalled()
        val menu = bottomNav.menu
        menu.findItem(R.id.nav_install)?.isEnabled = true
        menu.findItem(R.id.nav_dashboard)?.isEnabled = installed
        menu.findItem(R.id.nav_settings)?.isEnabled = true
    }

    /**
     * Show the "ready" header if Hermes is installed, otherwise the
     * "not ready" placeholder. The quick-action cards are hidden until
     * installation completes so users don't tap actions that can't work.
     * Service toggle card label updates based on whether service is running.
     */
    private fun refreshDashboardState() {
        val installed = serverManager.isHermesInstalled()
        val running = studioInstaller.isRunning
        dashboardReady.visibility = if (installed) View.VISIBLE else View.GONE
        dashboardNotReady.visibility = if (installed) View.GONE else View.VISIBLE
        openShellButton.visibility = if (installed) View.VISIBLE else View.GONE
        serviceToggleButton.visibility = if (installed) View.VISIBLE else View.GONE
        chatButton.visibility = if (installed) View.VISIBLE else View.GONE
        retryButton.visibility = if (installed) View.VISIBLE else View.GONE
        // Service toggle: start ↔ stop
        if (running) {
            serviceToggleTitle.text = getString(R.string.card_service_stop_title)
            serviceToggleSubtitle.text = getString(R.string.card_service_stop_subtitle)
            chatCardSubtitle.text = getString(R.string.card_open_chat_subtitle_running)
        } else {
            serviceToggleTitle.text = getString(R.string.card_service_start_title)
            serviceToggleSubtitle.text = getString(R.string.card_service_start_subtitle)
            chatCardSubtitle.text = getString(R.string.card_open_chat_subtitle_stopped)
        }
    }

    private fun extractBootstrap() {
        // rootfs 下载交给 installProot 步骤（进度条能反映）。
        // 这里只刷新系统配置（resolv.conf/proc_fakes 等），确保即使
        // rootfs 已装但配置被 Android 清理也能恢复。
        activeThread = Thread {
            try {
                BootstrapManager.ensureSystemConfig(this)
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
        // Switch to install page to show bootstrap progress.
        switchPage(installPage)
        bottomNav.selectedItemId = R.id.nav_install
        stepsContainer.visibility = View.GONE
        logPanel.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.status_initializing)
        logView.text = ""
        synchronized(recentLog) { recentLog.clear() }
        fullLog.clear()
        // Disable Dashboard/Settings tabs again until install completes.
        refreshNavTabs()
        extractBootstrap()
    }

    private fun showSteps() {
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        statusDetail.visibility = View.GONE
        stepsContainer.visibility = View.VISIBLE
        logPanel.visibility = View.VISIBLE
        refreshStepButtons()
        refreshNavTabs()
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
        // Switch to install page so the progress bar is visible.
        switchPage(installPage)
        bottomNav.selectedItemId = R.id.nav_install
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
        // Show the overall progress bar, seeded with however many of the 4
        // steps are already complete before this run begins.
        installProgressContainer.visibility = View.VISIBLE
        installProgressBar.isIndeterminate = false
        installProgressBar.max = 100
        val initialPct = computeOverallProgress() * 100 / 4
        installProgressBar.progress = initialPct
        progressPercentText.text = "$initialPct%"
        progressStepLabel.text = getString(R.string.progress_starting)
        return true
    }

    private fun releaseInstallLock() {
        isInstallInProgress = false
        stopHeartbeat()
        // Flush any remaining pending logs
        logUpdateHandler.removeCallbacks(logFlushRunnable)
        logUpdateHandler.post(logFlushRunnable)
        installProgressContainer.visibility = View.GONE
        refreshStepButtons()
        // A step may have just completed (e.g. Hermes installed) — refresh
        // the enabled state of the Dashboard/Settings nav tabs accordingly.
        refreshNavTabs()
    }

    private fun runStep(step: String) {
        if (!tryAcquireInstallLock()) return
        setStepButtonState(step, true)
        activeThread = Thread {
            try {
                beginStepProgress(step)
                when (step) {
                    "proot" -> installProot()
                    "python" -> installPython()
                    "buildDeps" -> installBuildDeps()
                    "hermes" -> installHermes()
                }
                completeStepProgress(step)
                if (allStepsDone()) applyProgressUi(100, getString(R.string.progress_done))
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
        synchronized(recentLog) { recentLog.clear() }
        fullLog.clear()
        activeThread = Thread {
            try {
                if (!serverManager.isProotInstalled()) {
                    beginStepProgress("proot")
                    installProot()
                    completeStepProgress("proot")
                }
                if (!serverManager.isPythonInstalled()) {
                    beginStepProgress("python")
                    installPython()
                    completeStepProgress("python")
                }
                if (!isBuildDepsInstalled()) {
                    beginStepProgress("buildDeps")
                    installBuildDeps()
                    completeStepProgress("buildDeps")
                }
                if (!serverManager.isHermesInstalled()) {
                    beginStepProgress("hermes")
                    installHermes()
                    completeStepProgress("hermes")
                }
                applyProgressUi(100, getString(R.string.progress_done))
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
                // Called on the background install thread. Log so the user
                // sees WHY the install paused, then show the dialog.
                // askUserAboutCompile() internally uses runOnUiThread to
                // show the dialog and a CountDownLatch to block THIS thread
                // until the user clicks. Do NOT wrap askUserAboutCompile in
                // another runOnUiThread — runOnUiThread is async (it just
                // posts to the main looper and returns immediately), so the
                // outer block returns before the dialog is even shown, and
                // the latch never gets counted down → 120s hang → ANR.
                appendLog("⏳ Phase 1 失败，等待你确认是否下载工具链并从源码编译…")
                val approved = askUserAboutCompile()
                if (approved) appendLog("已确认编译，开始下载工具链…") else appendLog("已取消编译")
                approved
            },
        )
        if (!ok) throw RuntimeException("Failed to install Hermes Agent")
        serverManager.configureHermesSkeleton()
        // 不再调 healthCheck —— installHermes 内部已用 `hermes --version`
        // 验证过，重复跑只浪费时间和日志行。
        appendLog("✓ Hermes Agent 已安装")
    }

    // ── Environment backup / restore ───────────────────────────────────────

    private fun onSaveEnvClicked() {
        if (isInstallInProgress) {
            Toast.makeText(this, R.string.install_in_progress_msg, Toast.LENGTH_SHORT).show()
            return
        }
        // Build a status summary of which steps are done so the user knows
        // exactly what they're about to back up. Each line: "✓ step" or "✗ step".
        val prootDone = serverManager.isProotInstalled()
        val pythonDone = serverManager.isPythonInstalled()
        val depsDone = isBuildDepsInstalled()
        val hermesDone = serverManager.isHermesInstalled()
        if (!prootDone && !pythonDone && !depsDone && !hermesDone) {
            Toast.makeText(this, R.string.env_save_empty, Toast.LENGTH_LONG).show()
            return
        }
        val statusLines = buildString {
            append(if (prootDone) "✓" else "✗").append(" proot\n")
            append(if (pythonDone) "✓" else "✗").append(" Python\n")
            append(if (depsDone) "✓" else "✗").append(" build deps\n")
            append(if (hermesDone) "✓" else "✗").append(" Hermes Agent")
        }
        // If Hermes is done → full backup (directly save, no extra dialog).
        // Otherwise → confirm partial backup so user understands they'll
        // need to continue from the breakpoint after restore.
        if (hermesDone) {
            launchSaveEnv()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.env_save_partial_title)
                .setMessage(getString(R.string.env_save_partial_msg, statusLines))
                .setPositiveButton(R.string.env_save_action) { _, _ -> launchSaveEnv() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun launchSaveEnv() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val filename = getString(R.string.env_save_filename_template, timestamp)
        saveEnvLauncher.launch(filename)
    }

    private fun onRestoreEnvClicked() {
        if (isInstallInProgress) {
            Toast.makeText(this, R.string.install_in_progress_msg, Toast.LENGTH_SHORT).show()
            return
        }
        // 还原会清空当前环境，需要二次确认
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.env_confirm_restore_title)
            .setMessage(R.string.env_confirm_restore_msg)
            .setPositiveButton(R.string.action_exit) { _, _ ->
                // 用户确认 → 打开 SAF 选择 .tar.gz 备份文件
                restoreEnvLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar"))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 在后台线程执行环境备份。进度通过 indeterminate ProgressDialog +
     * 日志页同步显示。完成后弹 toast 告知结果。
     */
    private fun runEnvBackup(targetUri: Uri) {
        if (!tryAcquireInstallLock()) return
        val styled = showStyledProgressDialog(
            title = getString(R.string.env_save_progress_title),
            message = "正在打包环境…",
            onCancel = { activeThread?.interrupt() },
        )

        activeThread = Thread {
            try {
                val ok = envBackup.backup(targetUri) { msg ->
                    runOnUiThread { styled.messageView.text = msg }
                    appendLog("[backup] $msg")
                }
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    if (ok) {
                        Toast.makeText(this, "✓ 环境已保存", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "保存失败，详见日志", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Env backup failed", e)
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.start() }
    }

    /**
     * 在后台线程执行环境还原。还原成功后停掉 hermes 服务（如果正在
     * 跑），因为还原替换了它的二进制和 venv。
     */
    private fun runEnvRestore(sourceUri: Uri) {
        if (!tryAcquireInstallLock()) return
        val styled = showStyledProgressDialog(
            title = getString(R.string.env_restore_progress_title),
            message = "正在还原环境…",
            onCancel = { activeThread?.interrupt() },
        )

        activeThread = Thread {
            try {
                // 还原前停掉 hermes / studio — 否则文件被占用
                try {
                    studioInstaller.stop()
                    serverManager.stopHermes()
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-restore stop failed: ${e.message}")
                }

                val ok = envBackup.restore(sourceUri) { msg ->
                    runOnUiThread { styled.messageView.text = msg }
                    appendLog("[restore] $msg")
                }
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    if (ok) {
                        // 还原完成后刷新所有按钮 / nav tabs 状态
                        refreshStepButtons()
                        refreshNavTabs()
                        refreshDashboardState()
                        Toast.makeText(this, "✓ 环境已还原", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "还原失败，详见日志", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Env restore failed", e)
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    Toast.makeText(this, "还原失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.start() }
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

        // Save env: enabled as soon as ANY install step is done (proot /
        // python / buildDeps / hermes). This lets users checkpoint progress
        // — e.g. save after buildDeps completes (the slowest step) so a
        // later failure doesn't force re-downloading 570MB of rust/clang.
        // Restore env: always available — user may want to restore to skip
        // the install entirely.
        val anyStepDone = prootDone || pythonDone || depsDone || hermesDone
        btnSaveEnv.isEnabled = anyStepDone && !isInstallInProgress
        btnSaveEnv.alpha = when {
            !anyStepDone -> 0.35f
            isInstallInProgress -> 0.35f
            else -> 1f
        }
        btnRestoreEnv.isEnabled = !isInstallInProgress
        btnRestoreEnv.alpha = if (isInstallInProgress) 0.35f else 1f
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
        // 新架构 marker 由 HermesServerManager.installHermesBuildDeps 写到
        // configDir/.build-deps-v1（rootfs 模型，不再用 prefix/var）。
        val configDir = BootstrapManager.getPaths(this).configDir
        val marker = File(configDir, ".build-deps-v1")
        return marker.exists()
    }

    private fun allStepsDone(): Boolean {
        return serverManager.isProotInstalled()
                && serverManager.isPythonInstalled()
                && isBuildDepsInstalled()
                && serverManager.isHermesInstalled()
    }

    // ── Screen transitions ──────────────────────────────────────────────────

    private fun showDoneScreen() {
        // 安装完成：保持安装页步骤按钮可见（显示"✓ 已安装"）+ 解锁导航。
        // 不再自动跳转 dashboard —— 用户可手动切到仪表盘使用 Hermes。
        showSteps()
        refreshDashboardState()
        updateChatButtonLabel()
        refreshNavTabs()
        appendLog("✓ 全部安装完成！可切到「仪表盘」开始使用")
    }

    // ── Chat UI ─────────────────────────────────────────────────────────────

    private fun updateChatButtonLabel() {
        if (studioInstaller.isInstalled()) {
            chatCardTitle.text = getString(R.string.action_open_chat)
            chatCardSubtitle.text = getString(R.string.card_open_chat_subtitle_open)
        } else {
            chatCardTitle.text = getString(R.string.card_open_chat_title)
            chatCardSubtitle.text = getString(R.string.card_open_chat_subtitle_install)
        }
    }

    private fun getVersionName(): String {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.versionName ?: "0.1.0"
        } catch (e: Exception) {
            "0.1.0"
        }
    }

    private fun onChatButtonClicked() {
        if (!studioInstaller.isInstalled()) {
            installChatUi()
            return
        }
        if (!studioInstaller.isRunning) {
            Toast.makeText(this, R.string.card_open_chat_subtitle_stopped, Toast.LENGTH_SHORT).show()
            return
        }
        openChatWebView()
    }

    /**
     * 服务启停切换：运行中→停止，已停止→启动。
     * hermes-web-ui 必须已安装才能启动（否则提示先安装）。
     */
    private fun onServiceToggleClicked() {
        if (studioInstaller.isRunning) {
            stopChatServer()
        } else {
            if (!studioInstaller.isInstalled()) {
                installChatUi()
                return
            }
            startChatServer()
        }
    }

    /**
     * 启动服务（不自动打开 WebView）。用户可之后点"聊天界面"打开。
     */
    private fun startChatServer() {
        val styled = showStyledProgressDialog(
            title = getString(R.string.card_service_start_title),
            message = getString(R.string.chat_starting),
        )
        Thread {
            val ok = studioInstaller.start { msg ->
                runOnUiThread {
                    if (!isFinishing) {
                        styled.messageView.text = msg
                        appendLog(msg)
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing) {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    refreshDashboardState()
                    if (ok) {
                        Toast.makeText(this, "✓ 服务已启动", Toast.LENGTH_SHORT).show()
                    } else {
                        // 显示真实的服务器输出 + server.log
                        val serverLog = studioInstaller.getServerLog()?.trim()
                        val raw = studioInstaller.getRecentOutput().trim()
                        val detail = buildString {
                            append(getString(R.string.chat_start_failed))
                            if (!serverLog.isNullOrEmpty()) {
                                append("\n\n═══ server.log ═══\n")
                                append(serverLog)
                            }
                            if (raw.isNotEmpty()) {
                                append("\n\n═══ 进程输出 ═══\n")
                                append(raw)
                            }
                        }
                        appendLog("[error] hermes-web-ui 启动失败")
                        if (!serverLog.isNullOrEmpty()) {
                            appendLog("[error] server.log:\n$serverLog")
                        }
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.error_title)
                            .setMessage(detail)
                            .setPositiveButton(R.string.retry) { _, _ -> startChatServer() }
                            .setNegativeButton(R.string.action_copy_error) { _, _ ->
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("hermes_error", detail))
                                Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            }
                            .setNeutralButton(R.string.cancel, null)
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }.also { activeThread = it; it.start() }
    }

    /**
     * 停止服务。
     */
    private fun stopChatServer() {
        val styled = showStyledProgressDialog(
            title = getString(R.string.card_service_stop_title),
            message = getString(R.string.card_service_stopping),
        )
        Thread {
            studioInstaller.stop()
            runOnUiThread {
                if (!isFinishing) {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    refreshDashboardState()
                    Toast.makeText(this, "✓ 服务已停止", Toast.LENGTH_SHORT).show()
                }
            }
        }.also { activeThread = it; it.start() }
    }

    private fun installChatUi() {
        val styled = showStyledProgressDialog(
            title = getString(R.string.action_install_chat),
            message = getString(R.string.chat_installing),
        )
        Thread {
            val ok = studioInstaller.install { msg ->
                runOnUiThread {
                    if (!isFinishing) {
                        styled.messageView.text = msg
                        appendLog(msg)
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing) {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    if (ok) {
                        updateChatButtonLabel()
                        refreshDashboardState()
                        // 安装完成后自动启动服务
                        startChatServer()
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


    private fun openChatWebView() {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_BASE_URL, HermesStudioInstaller.STUDIO_BASE_URL)
        }
        startActivity(intent)
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    /**
     * 创建美化进度对话框（深色卡片样式，替换系统 ProgressDialog）。
     * 返回 dialog 和 message TextView，调用方可通过 setMessage 更新消息。
     */
    private data class StyledProgressDialog(
        val dialog: android.app.Dialog,
        val messageView: TextView,
        val titleView: TextView,
    )

    private fun showStyledProgressDialog(
        title: String,
        message: String,
        onCancel: (() -> Unit)? = null,
    ): StyledProgressDialog {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_progress)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        val titleView = dialog.findViewById<TextView>(R.id.progressDialogTitle)!!
        val msgView = dialog.findViewById<TextView>(R.id.progressDialogMessage)!!
        titleView.text = title
        msgView.text = message
        val cancelBtn = dialog.findViewById<Button>(R.id.progressDialogCancel)!!
        if (onCancel != null) {
            cancelBtn.setOnClickListener {
                onCancel()
                dialog.dismiss()
            }
        } else {
            cancelBtn.visibility = View.GONE
        }
        dialog.show()
        activeProgressDialog = dialog
        return StyledProgressDialog(dialog, msgView, titleView)
    }

    private fun showShellInstructions() {
        val paths = BootstrapManager.getPaths(this)
        val msg = """
            |Hermes Agent 运行在 app 自带的 proot + Ubuntu rootfs 环境里，
            |无需安装 Termux。
            |
            |最简单的用法：点上面的「聊天」按钮，直接和 Hermes 对话。
            |
            |代码位置（host 文件系统）：
            |${paths.homeDir}/hermes-agent
            |
            |rootfs 位置：
            |${paths.rootfsDir}
            |
            |如需命令行，可用 adb shell 进入：
            |  cd ${paths.nativeLibDir}
            |  ./libproot.so -r ${paths.rootfsDir} -b /dev -b /proc \
            |    -b ${paths.tmpDir}:/tmp -w /root/home \
            |    /usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin bash
            |
            |首次使用需配置模型，在 proot 里运行：
            |  cd /root/home/hermes-agent && . .venv/bin/activate
            |  hermes setup --portal   # 用 Nous Portal（免费 OAuth）
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

    // ── Install progress ────────────────────────────────────────────────────

    /** Count how many of the 4 install steps are complete (0..4). */
    private fun computeOverallProgress(): Int {
        var done = 0
        if (serverManager.isProotInstalled()) done++
        if (serverManager.isPythonInstalled()) done++
        if (isBuildDepsInstalled()) done++
        if (serverManager.isHermesInstalled()) done++
        return done
    }

    private fun stepLabel(step: String): String = when (step) {
        "proot" -> getString(R.string.step_proot)
        "python" -> getString(R.string.step_python)
        "buildDeps" -> getString(R.string.step_build_deps)
        "hermes" -> getString(R.string.step_hermes)
        else -> getString(R.string.progress_starting)
    }

    private val logUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingLogs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val logFlushRunnable = object : Runnable {
        override fun run() {
            if (pendingLogs.isEmpty()) {
                return
            }
            val newLines = mutableListOf<String>()
            var line = pendingLogs.poll()
            while (line != null) {
                newLines.add(line)
                line = pendingLogs.poll()
            }
            if (showAllLogs) {
                // 全部模式：直接追加
                val sb = StringBuilder()
                newLines.forEach { sb.append(it).append('\n') }
                logView.append(sb.toString())
            } else {
                // 当前模式：用最近 30 行重建，避免无限增长
                rebuildCurrentLogView()
            }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 重建"当前操作"视图：只显示 recentLog 里的最近 30 行。 */
    private fun rebuildCurrentLogView() {
        val text = synchronized(recentLog) { recentLog.joinToString("\n") }
        logView.text = if (text.isEmpty()) "" else text + "\n"
    }

    /** 重建"全部日志"视图：从 fullLog 重建。 */
    private fun rebuildFullLogView() {
        logView.text = fullLog.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" }
    }

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var lastProgressTime = 0L

    // ── Sub-step progress (live progress bar within each step) ──────────────
    // Band allocation is proportional to ACTUAL measured install time under
    // the new proot+rootfs architecture:
    //   proot     ~1-2min   → 0–30%   (Ubuntu rootfs 28MB download + extract)
    //   python    ~1-2min   → 30–45%  (apt-get update + install python3)
    //   buildDeps ~2-3min   → 45–60%  (apt-get install build-essential + libs)
    //   hermes    5-15min   → 60–100% (git clone + venv + pip install)
    // On step start, the bar jumps to bandStart + 1 (visible "started"
    // movement). The heartbeat tick below nudges it +1 every 5s, capping at
    // bandEnd - 1 so the bar never reaches the step's full band until the
    // step actually completes (avoiding the "100% but still installing"
    // lie). On completion, completeStepProgress() snaps the bar to bandEnd.
    @Volatile private var currentStepBandStart = 0
    @Volatile private var currentStepBandEnd = 30
    @Volatile private var currentStepPct = 0

    private fun stepBand(step: String): Pair<Int, Int> = when (step) {
        "proot" -> 0 to 30
        "python" -> 30 to 45
        "buildDeps" -> 45 to 60
        "hermes" -> 60 to 100
        else -> 0 to 100
    }

    /**
     * Mark the start of an install step. Jumps the bar to the step's band
     * start + 1 (proot/python/buildDeps) or bandStart (hermes, since its
     * band is huge — sub-phase progress will drive it forward) so the
     * user immediately sees movement when a step begins.
     */
    private fun beginStepProgress(step: String) {
        val (start, end) = stepBand(step)
        currentStepBandStart = start
        currentStepBandEnd = end
        // For narrow bands (proot/python/buildDeps), jump +1 to show we
        // started. For hermes (70-wide band), the sub-phase progress
        // callback will push it forward — starting at bandStart is fine.
        currentStepPct = if (end - start > 30) start else start + 1
        applyProgressUi(currentStepPct, stepLabel(step))
    }

    /**
     * Mark a step as complete. Snaps the bar to the step's band end.
     */
    private fun completeStepProgress(step: String) {
        val (_, end) = stepBand(step)
        currentStepPct = end
        applyProgressUi(currentStepPct, stepLabel(step))
    }

    private fun applyProgressUi(pct: Int, label: String) {
        runOnUiThread {
            if (!isInstallInProgress) return@runOnUiThread
            installProgressContainer.visibility = View.VISIBLE
            installProgressBar.isIndeterminate = false
            installProgressBar.max = 100
            installProgressBar.progress = pct
            progressPercentText.text = "$pct%"
            progressStepLabel.text = label
        }
    }

    private fun startHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (isInstallInProgress) {
                    // Nudge the sub-step progress bar forward every tick.
                    // Tick interval = 5s; we size the per-tick nudge so the
                    // band fills in roughly the step's expected runtime:
                    //   band 3 wide  (proot)    → +1/tick = 15s to fill (fast step)
                    //   band 7 wide  (python)   → +1/tick = 35s to fill
                    //   band 20 wide (buildDeps) → +1/tick = 100s (~1.5min)
                    //   band 70 wide (hermes)    → +1/tick = 350s (~6min, capped at bandEnd-1)
                    // The cap at bandEnd - 1 reserves the last percent for
                    // actual completion (no "100% but still installing" lie).
                    val bandWidth = currentStepBandEnd - currentStepBandStart
                    val nudge = if (bandWidth <= 20) 1 else (bandWidth / 70).coerceAtLeast(1)
                    val cap = currentStepBandEnd - 1
                    if (currentStepPct < cap) {
                        currentStepPct = (currentStepPct + nudge).coerceAtMost(cap)
                        applyProgressUi(currentStepPct, progressStepLabel.text?.toString() ?: "")
                    }
                    // Log a heartbeat only when no pip/apt output has
                    // arrived for >5s, so the user can tell a hang from
                    // a merely-quiet phase.
                    if (now - lastProgressTime > 5000) {
                        val secs = (now - lastProgressTime) / 1000
                        appendLog("… 仍在运行中 (${secs}s)")
                    }
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
        fullLog.add(text)
        // 限制 fullLog 大小，避免内存爆炸（保留最近 5000 行）
        while (fullLog.size > 5000) fullLog.poll()
        lastProgressTime = System.currentTimeMillis()
        pendingLogs.add(text)
        // Drive progress bar by matching real install log output.
        applyLogBasedProgress(text)
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

    /**
     * Map real install log lines to progress bar percentages.
     *
     * 新架构（proot + Ubuntu rootfs）的日志匹配。每个步骤的 onProgress
     * 输出带步骤前缀（"proot:" / "Python:" / "build deps:" / "Hermes:"），
     * 让这里能明确区分。
     *
     * Band allocation (proot+rootfs architecture):
     *   proot     0–30%   rootfs 下载 + 解压 + 验证
     *   python    30–45%  apt update + install python3
     *   buildDeps 45–60%  apt install build-essential + libs
     *   hermes    60–100% git clone + venv + pip install
     */
    private fun applyLogBasedProgress(line: String) {
        if (!isInstallInProgress) return
        val l = line.trim()

        // proot 步骤 (0-30) — rootfs 下载 + 解压 + 验证
        when {
            l.contains("proot: 检查环境") -> setProgress(1, "proot")
            l.contains("下载 Ubuntu rootfs") -> setProgress(2, "proot")
            l.contains("尝试下载") -> setProgress(3, "proot")
            l.contains("下载中…") -> {
                // "下载中… 45% (12.3MB)" → 映射到 proot band 2-10
                val pct = Regex("(\\d+)%").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                setProgress(2 + pct * 8 / 100, "proot")
            }
            l.contains("✓ 下载完成") -> setProgress(10, "proot")
            l.contains("提取中…") -> {
                // "提取中… 4000 个条目" → 映射到 12-22
                val n = Regex("(\\d+) 个条目").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                setProgress((12 + n / 1000).coerceAtMost(22), "proot")
            }
            l.contains("创建符号链接") -> setProgress(24, "proot")
            l.contains("✓ rootfs 提取完成") -> setProgress(26, "proot")
            l.contains("Configuring rootfs") -> setProgress(28, "proot")
            l.contains("proot: 验证可执行") -> setProgress(29, "proot")
            l.contains("✓ proot 可用") -> setProgress(30, "proot")
        }

        // python 步骤 (30-45)
        when {
            l.contains("Python: apt-get update") -> setProgress(32, "python")
            l.contains("Python: apt-get install") -> setProgress(35, "python")
            l.contains("✓ Python 已安装") -> setProgress(45, "python")
        }

        // buildDeps 步骤 (45-60)
        when {
            l.contains("build deps: apt-get update") -> setProgress(47, "buildDeps")
            l.contains("build deps: apt-get install") -> setProgress(50, "buildDeps")
            l.contains("✓ build deps 已安装") -> setProgress(60, "buildDeps")
        }

        // hermes 步骤 (60-100) — tarball 优先 + venv + pip
        when {
            l.contains("尝试 tarball 下载") -> setProgress(62, "hermes")
            l.contains("✓ tarball 解压成功") -> setProgress(65, "hermes")
            l.contains("tarball 全部失败") -> setProgress(62, "hermes")
            l.contains("git clone from") -> setProgress(63, "hermes")
            l.contains("✓ 克隆成功") -> setProgress(65, "hermes")
            l.contains("创建 Python venv") -> setProgress(70, "hermes")
            l.contains("venv 已存在") -> setProgress(70, "hermes")
            l.contains("pip install -e") -> setProgress(72, "hermes")
            l.contains("Collecting ") && l.contains("from hermes-agent") -> setProgress(74, "hermes")
            l.contains("Building wheels") || l.contains("Building editable") -> setProgress(78, "hermes")
            l.contains("Installing collected packages") -> setProgress(85, "hermes")
            l.contains("Successfully installed") -> setProgress(92, "hermes")
            l.contains("Hermes Agent v") -> setProgress(96, "hermes")
            l.contains("✓ Hermes Agent") -> setProgress(100, "hermes")
        }
    }

    /**
     * Set the progress bar to a specific percentage, but only if it's
     * higher than the current value (progress is monotonic — we never
     * let it go backwards, even if log lines arrive out of order).
     */
    private fun setProgress(pct: Int, step: String) {
        if (pct <= currentStepPct) return
        currentStepPct = pct
        applyProgressUi(pct, stepLabel(step))
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
