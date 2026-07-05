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
import android.view.WindowManager
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
        const val PREF_UPDATE_SOURCE = "update_source"
        const val PREF_UPDATE_CHANNEL = "update_channel"
        const val NOTIF_CHANNEL_UPDATES = "hermes_update_notifications"
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
    private lateinit var btnProot: TextView
    private lateinit var btnDeps: TextView
    private lateinit var btnHermes: TextView
    private lateinit var btnWebUI: TextView
    private lateinit var btnInstallAll: Button
    private lateinit var btnSaveEnv: View
    private lateinit var btnRestoreEnv: View
    private lateinit var btnUpdateHermes: View
    private lateinit var btnUpdateWebUI: View
    private lateinit var hermesVersionText: TextView
    private lateinit var webuiVersionText: TextView
    private lateinit var hermesUpdateBadge: TextView
    private lateinit var webuiUpdateBadge: TextView
    private lateinit var btnUpdateApk: View
    private lateinit var apkVersionText: TextView
    private lateinit var apkUpdateBadge: TextView
    private lateinit var btnSourceGithub: TextView
    private lateinit var btnSourceChina: TextView
    private lateinit var btnChannelStable: TextView
    private lateinit var btnChannelBeta: TextView
    private lateinit var spinnerProot: ProgressBar
    private lateinit var spinnerDeps: ProgressBar
    private lateinit var spinnerHermes: ProgressBar
    private lateinit var spinnerWebUI: ProgressBar
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
    /** 当前正在安装的步骤名（"proot"/"deps"/"hermes"/"webui"），null=无 */
    @Volatile private var currentInstallStep: String? = null

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
        btnDeps = findViewById(R.id.btnDeps)
        btnHermes = findViewById(R.id.btnHermes)
        btnWebUI = findViewById(R.id.btnWebUI)
        btnInstallAll = findViewById(R.id.btnInstallAll)
        btnSaveEnv = findViewById(R.id.btnSaveEnv)
        btnRestoreEnv = findViewById(R.id.btnRestoreEnv)
        btnUpdateHermes = findViewById(R.id.btnUpdateHermes)
        btnUpdateWebUI = findViewById(R.id.btnUpdateWebUI)
        hermesVersionText = findViewById(R.id.hermesVersionText)
        webuiVersionText = findViewById(R.id.webuiVersionText)
        hermesUpdateBadge = findViewById(R.id.hermesUpdateBadge)
        webuiUpdateBadge = findViewById(R.id.webuiUpdateBadge)
        btnUpdateApk = findViewById(R.id.btnUpdateApk)
        apkVersionText = findViewById(R.id.apkVersionText)
        apkUpdateBadge = findViewById(R.id.apkUpdateBadge)
        btnSourceGithub = findViewById(R.id.btnSourceGithub)
        btnSourceChina = findViewById(R.id.btnSourceChina)
        btnChannelStable = findViewById(R.id.btnChannelStable)
        btnChannelBeta = findViewById(R.id.btnChannelBeta)
        spinnerProot = findViewById(R.id.spinnerProot)
        spinnerDeps = findViewById(R.id.spinnerDeps)
        spinnerHermes = findViewById(R.id.spinnerHermes)
        spinnerWebUI = findViewById(R.id.spinnerWebUI)
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

        // 启动时自动检测版本 + 检查更新
        checkVersionsAndUpdates()

        // Step indicators are non-clickable TextViews (progress animation only).
        // Only "一键安装" and "还原环境" are action buttons.
        btnInstallAll.setOnClickListener { runInstallAll() }
        btnSaveEnv.setOnClickListener { onSaveEnvClicked() }
        btnRestoreEnv.setOnClickListener { onRestoreEnvClicked() }
        btnUpdateHermes.setOnClickListener { onUpdateHermesClicked() }
        btnUpdateWebUI.setOnClickListener { onUpdateWebUIClicked() }
        btnUpdateApk.setOnClickListener { onApkUpdateClicked() }

        // 更新源 / 通道选择
        initUpdatePreferences()

        openShellButton.setOnClickListener {
            // 打开内置终端，在 proot rootfs 里运行交互式 bash shell
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        serviceToggleButton.setOnClickListener { onServiceToggleClicked() }
        chatButton.setOnClickListener { onChatButtonClicked() }
        retryButton.setOnClickListener { restartFromBootstrap() }

        // Logs panel actions (embedded in install page)
        btnToggleLog.setOnClickListener {
            // 清除 pendingLogs，避免切换后 logFlushRunnable 重复 append
            // （rebuildFullLogView 已包含这些行，因为 appendLog 先写 fullLog）
            pendingLogs.clear()
            logUpdateHandler.removeCallbacks(logFlushRunnable)
            showAllLogs = !showAllLogs
            if (showAllLogs) {
                btnToggleLog.text = getString(R.string.log_show_current)
                logPanelTitle.text = getString(R.string.log_all, fullLog.size)
                logScroll.layoutParams.height = (resources.displayMetrics.heightPixels * 2 / 3)
                rebuildFullLogView()
            } else {
                btnToggleLog.text = getString(R.string.log_show_all)
                logPanelTitle.text = getString(R.string.log_current)
                logScroll.layoutParams.height = (160 * resources.displayMetrics.density).toInt()
                rebuildCurrentLogView()
            }
            logScroll.requestLayout()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
     *
     * NOT installed: only show "安装" tab. Dashboard and Settings are
     * hidden — user must complete installation first.
     *
     * Installed: hide "安装" tab, show Dashboard + Settings. The install
     * tab only reappears when user triggers "重新安装环境" from Settings.
     */
    private fun refreshNavTabs() {
        val installed = allStepsDone()
        val menu = bottomNav.menu
        val installItem = menu.findItem(R.id.nav_install)
        val dashboardItem = menu.findItem(R.id.nav_dashboard)
        val settingsItem = menu.findItem(R.id.nav_settings)

        if (installed) {
            // 环境已安装：隐藏安装 tab，显示仪表盘+设置
            installItem?.isVisible = false
            dashboardItem?.isVisible = true
            dashboardItem?.isEnabled = true
            settingsItem?.isVisible = true
            settingsItem?.isEnabled = true
            // 如果用户还在安装页，自动跳到仪表盘
            if (installPage.visibility == View.VISIBLE) {
                bottomNav.selectedItemId = R.id.nav_dashboard
                switchPage(dashboardPage)
            }
        } else {
            // 环境未安装：只显示安装 tab，隐藏仪表盘+设置
            installItem?.isVisible = true
            dashboardItem?.isVisible = false
            settingsItem?.isVisible = false
        }
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
        // 仪表盘不再提供"重新安装环境"入口，统一由设置页的"重新安装"按钮触发
        retryButton.visibility = View.GONE
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
        // 用户点了"重新安装" — 显示完整的安装界面（4 步骤 + 日志面板），
        // 让用户可以重新点击"一键安装"执行安装。
        // 不调用 tryAcquireInstallLock（那会显示进度条，但还没开始安装），
        // 也不调用 extractBootstrap（那会刷新配置后弹回仪表盘）。

        // 手动设置 tab 可见性：显示安装，隐藏仪表盘+设置
        bottomNav.menu.findItem(R.id.nav_install)?.isVisible = true
        bottomNav.menu.findItem(R.id.nav_dashboard)?.isVisible = false
        bottomNav.menu.findItem(R.id.nav_settings)?.isVisible = false
        // 切换到安装页，显示步骤 + 日志面板（不显示进度条）
        switchPage(installPage)
        bottomNav.selectedItemId = R.id.nav_install
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        statusDetail.visibility = View.GONE
        stepsContainer.visibility = View.VISIBLE
        logPanel.visibility = View.VISIBLE
        // 清空之前的日志
        logView.text = ""
        synchronized(recentLog) { recentLog.clear() }
        fullLog.clear()
        // 刷新步骤按钮显示（反映当前安装状态）
        refreshStepButtons()
        // 确保"一键安装"按钮可用
        btnInstallAll.alpha = 1f
        btnInstallAll.isEnabled = true
        // 隐藏进度条容器（如果之前显示着）
        installProgressContainer.visibility = View.GONE
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
        // Dim the install button (step indicators stay visible as progress animation)
        btnInstallAll.alpha = 0.35f
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
        // 清除当前安装步骤标记，让 refreshStepButtons 能正确显示完成状态
        currentInstallStep = null
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
                    "deps" -> installDeps()
                    "hermes" -> installHermes()
                    "webui" -> installWebUI()
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
                if (!serverManager.isPythonInstalled() || !isBuildDepsInstalled()) {
                    beginStepProgress("deps")
                    installDeps()
                    completeStepProgress("deps")
                }
                if (!serverManager.isHermesInstalled()) {
                    beginStepProgress("hermes")
                    installHermes()
                    completeStepProgress("hermes")
                }
                if (!studioInstaller.isInstalled()) {
                    beginStepProgress("webui")
                    installWebUI()
                    completeStepProgress("webui")
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
    private fun installDeps() {
        val ok = serverManager.installDependencies { msg -> appendLog(msg) }
        if (!ok) throw RuntimeException("Failed to install dependencies")
        appendLog("✓ 依赖已安装（Python + build deps）")
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

    @Throws(Exception::class)
    private fun installWebUI() {
        val ok = studioInstaller.install { msg -> appendLog(msg) }
        if (!ok) throw RuntimeException("Failed to install hermes-web-ui")
        appendLog("✓ WebUI 已安装")
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
        val depsDone = serverManager.isPythonInstalled() && isBuildDepsInstalled()
        val hermesDone = serverManager.isHermesInstalled()
        val webuiDone = studioInstaller.isInstalled()
        if (!prootDone && !depsDone && !hermesDone && !webuiDone) {
            Toast.makeText(this, R.string.env_save_empty, Toast.LENGTH_LONG).show()
            return
        }
        val statusLines = buildString {
            append(if (prootDone) "✓" else "✗").append(" proot\n")
            append(if (depsDone) "✓" else "✗").append(" 依赖 (Python + build deps)\n")
            append(if (hermesDone) "✓" else "✗").append(" Hermes Agent\n")
            append(if (webuiDone) "✓" else "✗").append(" WebUI")
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
     * 更新 Hermes Agent：git pull + pip install。后台执行 + 进度弹窗。
     */
    private fun onUpdateHermesClicked() {
        if (!tryAcquireInstallLock()) return
        val styled = showStyledProgressDialog(
            title = getString(R.string.settings_update_hermes),
            message = "正在更新 Hermes Agent…",
            onCancel = { activeThread?.interrupt() },
        )
        activeThread = Thread {
            try {
                val ok = serverManager.updateHermes { msg ->
                    runOnUiThread { styled.messageView.text = msg }
                    appendLog("[update-hermes] $msg")
                }
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    if (ok) {
                        Toast.makeText(this, "✓ Hermes 已更新", Toast.LENGTH_LONG).show()
                        // 重置更新 badge + 刷新版本
                        hermesUpdateBadge.visibility = View.GONE
                        checkVersionsAndUpdates()
                    } else {
                        Toast.makeText(this, "更新失败，详见日志", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hermes update failed", e)
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    Toast.makeText(this, "更新失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.start() }
    }

    /**
     * 更新 WebUI：npm install -g hermes-web-ui@latest。后台执行 + 进度弹窗。
     * 如果服务在运行，更新后自动重启。
     */
    private fun onUpdateWebUIClicked() {
        if (!tryAcquireInstallLock()) return
        val wasRunning = studioInstaller.isRunning
        val styled = showStyledProgressDialog(
            title = getString(R.string.settings_update_webui),
            message = "正在更新 WebUI…",
            onCancel = { activeThread?.interrupt() },
        )
        activeThread = Thread {
            try {
                val ok = studioInstaller.update { msg ->
                    runOnUiThread { styled.messageView.text = msg }
                    appendLog("[update-webui] $msg")
                }
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    if (ok) {
                        refreshStepButtons()
                        // 如果更新前服务在运行，更新后自动重启
                        if (wasRunning) {
                            appendLog("[update-webui] 服务之前在运行，重启中…")
                            startChatServer()
                        }
                        Toast.makeText(this, "✓ WebUI 已更新", Toast.LENGTH_LONG).show()
                        // 重置更新 badge + 刷新版本
                        webuiUpdateBadge.visibility = View.GONE
                        checkVersionsAndUpdates()
                    } else {
                        Toast.makeText(this, "更新失败，详见日志", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebUI update failed", e)
                runOnUiThread {
                    styled.dialog.dismiss()
                    activeProgressDialog = null
                    releaseInstallLock()
                    Toast.makeText(this, "更新失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.start() }
    }

    /**
     * 在后台线程执行环境备份。进度通过 styled progress dialog +
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
        // 如果当前步骤正在安装，不覆盖其状态
        if (currentInstallStep != "proot") {
            btnProot.text = if (prootDone) getString(R.string.step_done_proot) else getString(R.string.step_proot)
            btnProot.alpha = if (prootDone) 0.6f else 1f
            btnProot.setTextColor(if (prootDone) 0xFF10b981.toInt() else 0xFF94a3b8.toInt())
            spinnerProot.visibility = View.GONE
        }

        val depsDone = serverManager.isPythonInstalled() && isBuildDepsInstalled()
        if (currentInstallStep != "deps") {
            btnDeps.text = if (depsDone) getString(R.string.step_done_deps) else getString(R.string.step_deps)
            btnDeps.alpha = if (depsDone) 0.6f else 1f
            btnDeps.setTextColor(if (depsDone) 0xFF10b981.toInt() else 0xFF94a3b8.toInt())
            spinnerDeps.visibility = View.GONE
        }

        val hermesDone = serverManager.isHermesInstalled()
        if (currentInstallStep != "hermes") {
            btnHermes.text = if (hermesDone) getString(R.string.step_done_hermes) else getString(R.string.step_hermes)
            btnHermes.alpha = if (hermesDone) 0.6f else 1f
            btnHermes.setTextColor(if (hermesDone) 0xFF10b981.toInt() else 0xFF94a3b8.toInt())
            spinnerHermes.visibility = View.GONE
        }

        val webuiDone = studioInstaller.isInstalled()
        if (currentInstallStep != "webui") {
            btnWebUI.text = if (webuiDone) getString(R.string.step_done_webui) else getString(R.string.step_webui)
            btnWebUI.alpha = if (webuiDone) 0.6f else 1f
            btnWebUI.setTextColor(if (webuiDone) 0xFF10b981.toInt() else 0xFF94a3b8.toInt())
            spinnerWebUI.visibility = View.GONE
        }

        val allDone = prootDone && depsDone && hermesDone && webuiDone
        btnInstallAll.isEnabled = !allDone && !isInstallInProgress
        btnInstallAll.text = if (allDone) getString(R.string.step_done_all) else getString(R.string.step_install_all)
        btnInstallAll.alpha = when {
            allDone -> 0.6f
            isInstallInProgress -> 0.35f
            else -> 1f
        }

        // Save env: enabled as soon as ANY install step is done (proot /
        // deps / hermes / webui). This lets users checkpoint progress
        // — e.g. save after hermes completes (the slowest step) so a
        // later failure doesn't force re-downloading 570MB of rust/clang.
        // Restore env: always available — user may want to restore to skip
        // the install entirely.
        val anyStepDone = prootDone || depsDone || hermesDone || webuiDone
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
            "deps" -> btnDeps to spinnerDeps
            "hermes" -> btnHermes to spinnerHermes
            "webui" -> btnWebUI to spinnerWebUI
            else -> return
        }
        if (installing) {
            currentInstallStep = step
            btn.text = getString(R.string.step_installing)
            btn.alpha = 1f
            btn.setTextColor(0xFF818cf8.toInt())
            spinner.visibility = View.VISIBLE
        } else {
            if (currentInstallStep == step) currentInstallStep = null
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
                && studioInstaller.isInstalled()
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

    /**
     * 启动时自动检测 Hermes Agent 和 WebUI 的当前版本，
     * 并在后台检查是否有新版本可用。有更新时亮显 badge。
     *
     * 版本获取和更新检查都在后台线程执行（proot 命令），
     * UI 更新通过 runOnUiThread 回到主线程。
     */
    private fun checkVersionsAndUpdates() {
        // 先显示 "检测中…"
        hermesVersionText.text = "检测中…"
        webuiVersionText.text = "检测中…"
        hermesUpdateBadge.visibility = View.GONE
        webuiUpdateBadge.visibility = View.GONE

        Thread {
            // ── 获取当前版本 ──
            val hermesVer = try { serverManager.getHermesVersion() } catch (e: Exception) {
                Log.e(TAG, "getHermesVersion failed", e); null
            }
            val webuiVer = try { studioInstaller.getWebUIVersion() } catch (e: Exception) {
                Log.e(TAG, "getWebUIVersion failed", e); null
            }

            Log.i(TAG, "checkVersionsAndUpdates: hermes=$hermesVer webui=$webuiVer")

            runOnUiThread {
                hermesVersionText.text = hermesVer ?: "未安装"
                webuiVersionText.text = webuiVer ?: "未安装"
                // 如果已安装但版本获取失败，显示提示
                if (hermesVer == null && serverManager.isHermesInstalled()) {
                    hermesVersionText.text = "已安装（版本获取失败）"
                }
                if (webuiVer == null && studioInstaller.isInstalled()) {
                    webuiVersionText.text = "已安装（版本获取失败）"
                }
            }

            // ── 检查更新（仅在已安装时）──
            if (hermesVer != null) {
                val latestHermes = try { serverManager.checkHermesUpdate() } catch (e: Exception) {
                    Log.e(TAG, "checkHermesUpdate failed", e); null
                }
                runOnUiThread {
                    if (latestHermes != null) {
                        hermesVersionText.text = "$hermesVer → $latestHermes"
                        hermesVersionText.setTextColor(0xFF10b981.toInt())
                        hermesUpdateBadge.visibility = View.VISIBLE
                    }
                }
            }

            if (webuiVer != null) {
                val latestWebUI = try { studioInstaller.checkWebUIUpdate() } catch (e: Exception) {
                    Log.e(TAG, "checkWebUIUpdate failed", e); null
                }
                runOnUiThread {
                    if (latestWebUI != null) {
                        webuiVersionText.text = "$webuiVer → $latestWebUI"
                        webuiVersionText.setTextColor(0xFF10b981.toInt())
                        webuiUpdateBadge.visibility = View.VISIBLE
                    }
                }
            }

            // ── 检查 APK 更新 ──
            val apkVer = getVersionName()
            val updateChannel = getUpdateChannel()
            val updateSource = getUpdateSource()
            val apkUpdate = try {
                ApkUpdateChecker.checkUpdate(apkVer, updateChannel, updateSource)
            } catch (e: Exception) {
                Log.e(TAG, "checkApkUpdate failed", e); null
            }
            runOnUiThread {
                if (apkUpdate != null) {
                    apkVersionText.text = "$apkVer → ${apkUpdate.version}"
                    apkVersionText.setTextColor(0xFF10b981.toInt())
                    apkUpdateBadge.visibility = View.VISIBLE
                    // 后台检测到更新时发通知
                    notifyApkUpdateAvailable(apkUpdate)
                } else {
                    apkVersionText.text = apkVer
                }
            }
        }.start()
    }

    // ── APK 更新相关 ────────────────────────────────────────────────────────

    /** 当前 APK 更新信息（检查后缓存，供点击时使用） */
    private var lastApkUpdateInfo: ApkUpdateInfo? = null

    /** 获取更新源设置 */
    private fun getUpdateSource(): String {
        val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_UPDATE_SOURCE, ApkUpdateChecker.SOURCE_GITHUB)
            ?: ApkUpdateChecker.SOURCE_GITHUB
    }

    /** 获取更新通道设置 */
    private fun getUpdateChannel(): String {
        val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_UPDATE_CHANNEL, ApkUpdateChecker.CHANNEL_BETA)
            ?: ApkUpdateChecker.CHANNEL_BETA
    }

    /** 初始化更新源/通道选择按钮，根据偏好设置高亮状态 */
    private fun initUpdatePreferences() {
        updateSourceToggleUI()
        updateChannelToggleUI()

        btnSourceGithub.setOnClickListener {
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_UPDATE_SOURCE, ApkUpdateChecker.SOURCE_GITHUB).apply()
            updateSourceToggleUI()
            // 切换后重新检测
            checkVersionsAndUpdates()
        }
        btnSourceChina.setOnClickListener {
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_UPDATE_SOURCE, ApkUpdateChecker.SOURCE_GITEE).apply()
            updateSourceToggleUI()
            checkVersionsAndUpdates()
        }
        btnChannelStable.setOnClickListener {
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_UPDATE_CHANNEL, ApkUpdateChecker.CHANNEL_STABLE).apply()
            updateChannelToggleUI()
            checkVersionsAndUpdates()
        }
        btnChannelBeta.setOnClickListener {
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_UPDATE_CHANNEL, ApkUpdateChecker.CHANNEL_BETA).apply()
            updateChannelToggleUI()
            checkVersionsAndUpdates()
        }
    }

    /** 更新源按钮高亮切换 */
    private fun updateSourceToggleUI() {
        val source = getUpdateSource()
        if (source == ApkUpdateChecker.SOURCE_GITHUB) {
            btnSourceGithub.setTextColor(0xFF10b981.toInt())
            btnSourceChina.setTextColor(0xFF94a3b8.toInt())
        } else {
            btnSourceGithub.setTextColor(0xFF94a3b8.toInt())
            btnSourceChina.setTextColor(0xFF10b981.toInt())
        }
    }

    /** 通道按钮高亮切换 */
    private fun updateChannelToggleUI() {
        val channel = getUpdateChannel()
        if (channel == ApkUpdateChecker.CHANNEL_STABLE) {
            btnChannelStable.setTextColor(0xFF10b981.toInt())
            btnChannelBeta.setTextColor(0xFF94a3b8.toInt())
        } else {
            btnChannelStable.setTextColor(0xFF94a3b8.toInt())
            btnChannelBeta.setTextColor(0xFF10b981.toInt())
        }
    }

    /** APK 更新卡片点击事件 */
    private fun onApkUpdateClicked() {
        val currentVer = getVersionName()
        val channel = getUpdateChannel()
        val source = getUpdateSource()

        apkVersionText.text = "检测中…"
        apkUpdateBadge.visibility = View.GONE

        Thread {
            val update = ApkUpdateChecker.checkUpdate(currentVer, channel, source)
            lastApkUpdateInfo = update

            runOnUiThread {
                if (update != null) {
                    apkVersionText.text = "$currentVer → ${update.version}"
                    apkVersionText.setTextColor(0xFF10b981.toInt())
                    apkUpdateBadge.visibility = View.VISIBLE
                    showApkUpdateDialog(update)
                } else {
                    apkVersionText.text = currentVer
                    apkVersionText.setTextColor(0xFF64748b.toInt())
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 显示更新日志对话框，用户可选择下载更新 */
    private fun showApkUpdateDialog(update: ApkUpdateInfo) {
        val channelLabel = if (update.isBeta) "测试版" else "正式版"
        val sourceLabel = if (getUpdateSource() == ApkUpdateChecker.SOURCE_GITEE) "Gitee" else "GitHub"

        // 格式化更新日志：去除 markdown 标记符号
        val changelog = formatChangelog(update.changelog)

        val message = buildString {
            append("版本：${update.version}（$channelLabel）\n")
            append("来源：$sourceLabel\n")
            if (update.fileSize > 0) {
                append("大小：${update.fileSize / 1024 / 1024}MB\n")
            }
            append("\n更新日志：\n\n")
            append(changelog)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("发现新版本 ${update.tagName}")
            .setMessage(message)
            .setPositiveButton("下载更新") { _, _ ->
                downloadAndInstallApk(update)
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(true)
            .show()
    }

    /** 格式化 markdown 更新日志为纯文本 */
    private fun formatChangelog(md: String): String {
        return md
            .replace(Regex("^#{1,6}\\s*"), "", RegexOption.MULTILINE)  // 标题标记
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")                  // 粗体
            .replace(Regex("`(.+?)`"), "$1")                            // 行内代码
            .replace("---", "────────────────")                         // 分隔线
            .trim()
            .ifEmpty { "（无更新日志）" }
    }

    /** 下载 APK 并触发安装 */
    private fun downloadAndInstallApk(update: ApkUpdateInfo) {
        val source = getUpdateSource()

        // 根据更新源构建下载 URL 列表：
        // - GitHub 源：ghproxy 代理加速 + GitHub 直连兜底（逐个尝试）
        // - Gitee 源：Gitee 直链（国内可直连，无需代理）
        val urls = ApkUpdateChecker.getDownloadUrls(update.downloadUrl, source)

        // 创建更新目录
        val updateDir = File(externalCacheDir ?: cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "hermes-${update.tagName}.apk")

        // 进度对话框
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("正在下载 ${update.tagName}")
            .setMessage("准备下载…")
            .setCancelable(false)
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        Thread {
            var success = false
            var lastError: String? = null

            for (url in urls) {
                try {
                    runOnUiThread {
                        progressDialog.setMessage("正在下载（${url.substringBefore("://")}）…\n0%")
                    }

                    Log.i(TAG, "Downloading APK from: $url")
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "HermesAndroid/1.0")
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true

                    val code = conn.responseCode
                    if (code != 200) {
                        lastError = "HTTP $code"
                        conn.disconnect()
                        Log.w(TAG, "Download failed from $url: HTTP $code")
                        continue
                    }

                    val totalSize = conn.contentLengthLong
                    var downloaded = 0L
                    val input = conn.inputStream
                    val output = java.io.FileOutputStream(apkFile)
                    val buffer = ByteArray(8192)
                    var lastProgress = -1
                    var bytes = input.read(buffer)

                    while (bytes > 0) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        if (totalSize > 0) {
                            val pct = (downloaded * 100 / totalSize).toInt()
                            if (pct != lastProgress && pct % 5 == 0) {
                                lastProgress = pct
                                runOnUiThread {
                                    progressDialog.setMessage("正在下载…\n$pct%")
                                }
                            }
                        }
                        bytes = input.read(buffer)
                    }

                    output.flush()
                    output.close()
                    input.close()
                    conn.disconnect()

                    if (downloaded > 0) {
                        success = true
                        Log.i(TAG, "APK downloaded: ${apkFile.absolutePath} (${downloaded} bytes)")
                        break
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    Log.w(TAG, "Download failed from $url: ${e.message}")
                }
            }

            runOnUiThread {
                progressDialog.dismiss()
                if (success) {
                    Toast.makeText(this, "下载完成，正在启动安装…", Toast.LENGTH_SHORT).show()
                    installApk(apkFile)
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("下载失败")
                        .setMessage("所有下载源均失败：${lastError ?: "未知错误"}\n\n可尝试切换更新源后重试。")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }.start()

        progressDialog.show()
    }

    /** 触发系统 APK 安装界面 */
    private fun installApk(apkFile: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            Toast.makeText(this, "无法启动安装：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 后台检测到 APK 更新时发通知 */
    private fun notifyApkUpdateAvailable(update: ApkUpdateInfo) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            // 创建通知渠道（如果不存在）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    NOTIF_CHANNEL_UPDATES,
                    "Hermes 应用更新",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "检测到新版本时提醒更新"
                }
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL_UPDATES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("发现新版本 ${update.tagName}")
                .setContentText("点击查看更新日志并下载")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            manager.notify(2001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "notifyApkUpdateAvailable failed", e)
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
        // 自适应宽度：取屏幕宽度的 92%，但在 320dp~720dp 之间。
        // 这样手机竖屏占满大部分宽度，横屏/平板不会过宽。
        val dm = resources.displayMetrics
        val maxWidthPx = (720 * dm.density).toInt()
        val minWidthPx = (320 * dm.density).toInt()
        val targetWidth = (dm.widthPixels * 0.92).toInt().coerceIn(minWidthPx, maxWidthPx)
        dialog.window?.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
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
            "deps" -> "安装依赖"
            "hermes" -> "安装 Hermes Agent"
            "webui" -> "安装 WebUI"
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
        "deps" -> getString(R.string.step_deps)
        "hermes" -> getString(R.string.step_hermes)
        "webui" -> getString(R.string.step_webui)
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
                // 更新标题行数
                logPanelTitle.text = getString(R.string.log_all, fullLog.size)
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

    /** 重建"全部日志"视图：从 fullLog 重建。
     *  如果日志超过 2000 行，只显示最后 2000 行（TextView 渲染限制），
     *  并在顶部注明总行数。 */
    private fun rebuildFullLogView() {
        val total = fullLog.size
        val displayLimit = 2000
        val lines = if (total > displayLimit) {
            val header = "…（显示最近 $displayLimit 行，共 $total 行）\n\n"
            val tail = fullLog.toList().takeLast(displayLimit)
            header + tail.joinToString("\n")
        } else {
            fullLog.joinToString("\n")
        }
        logView.text = if (lines.isEmpty()) "" else lines + "\n"
    }

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var lastProgressTime = 0L

    // ── Sub-step progress (live progress bar within each step) ──────────────
    // Band allocation is proportional to ACTUAL measured install time:
    //   proot  ~1-2min   → 0–25%   (Ubuntu rootfs download + extract)
    //   deps   ~3-5min   → 25–50%  (apt-get install python + build-essential)
    //   hermes ~5-15min  → 50–80%  (git clone + venv + pip install)
    //   webui  ~2-5min   → 80–100% (node.js + npm install hermes-web-ui)
    // On step start, the bar jumps to bandStart + 1 (visible "started"
    // movement). The heartbeat tick below nudges it +1 every 5s, capping at
    // bandEnd - 1 so the bar never reaches the step's full band until the
    // step actually completes (avoiding the "100% but still installing"
    // lie). On completion, completeStepProgress() snaps the bar to bandEnd.
    @Volatile private var currentStepBandStart = 0
    @Volatile private var currentStepBandEnd = 30
    @Volatile private var currentStepPct = 0

    private fun stepBand(step: String): Pair<Int, Int> = when (step) {
        "proot" -> 0 to 25
        "deps" -> 25 to 50
        "hermes" -> 50 to 80
        "webui" -> 80 to 100
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
        // 限制 fullLog 大小，避免内存爆炸（保留最近 50000 行）
        // 5000 太小 — apt-get + pip install + git clone 单次安装就能超过
        while (fullLog.size > 50000) fullLog.poll()
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

        // deps 步骤 (25-50) — python + build deps 合并
        when {
            l.contains("deps: apt-get update") -> setProgress(27, "deps")
            l.contains("deps: apt-get install") -> setProgress(35, "deps")
            l.contains("✓ 依赖已安装") -> setProgress(50, "deps")
            // 兼容旧日志格式（installDependencies 内部可能输出旧格式消息）
            l.contains("Python: apt-get update") -> setProgress(27, "deps")
            l.contains("Python: apt-get install") -> setProgress(35, "deps")
            l.contains("✓ Python 已安装") -> setProgress(40, "deps")
            l.contains("build deps: apt-get update") -> setProgress(42, "deps")
            l.contains("build deps: apt-get install") -> setProgress(45, "deps")
            l.contains("✓ build deps 已安装") -> setProgress(50, "deps")
        }

        // hermes 步骤 (50-80) — tarball 优先 + venv + pip
        when {
            l.contains("尝试 tarball 下载") -> setProgress(52, "hermes")
            l.contains("✓ tarball 解压成功") -> setProgress(55, "hermes")
            l.contains("tarball 全部失败") -> setProgress(52, "hermes")
            l.contains("git clone from") -> setProgress(53, "hermes")
            l.contains("✓ 克隆成功") -> setProgress(55, "hermes")
            l.contains("创建 Python venv") -> setProgress(60, "hermes")
            l.contains("venv 已存在") -> setProgress(60, "hermes")
            l.contains("pip install -e") -> setProgress(62, "hermes")
            l.contains("Collecting ") && l.contains("from hermes-agent") -> setProgress(64, "hermes")
            l.contains("Building wheels") || l.contains("Building editable") -> setProgress(68, "hermes")
            l.contains("Installing collected packages") -> setProgress(72, "hermes")
            l.contains("Successfully installed") -> setProgress(76, "hermes")
            l.contains("Hermes Agent v") -> setProgress(78, "hermes")
            l.contains("✓ Hermes Agent") -> setProgress(80, "hermes")
        }

        // webui 步骤 (80-100) — node.js + npm install
        when {
            l.contains("Installing hermes-web-ui") -> setProgress(82, "webui")
            l.contains("Node.js >=23 已安装") -> setProgress(85, "webui")
            l.contains("npm install -g") -> setProgress(87, "webui")
            l.contains("✓ hermes-web-ui installed") -> setProgress(95, "webui")
            l.contains("✓ WebUI 已安装") -> setProgress(100, "webui")
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
