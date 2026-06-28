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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesMainActivity"
    }

    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var doneLayout: View
    private lateinit var openShellButton: Button
    private lateinit var retryButton: Button
    private lateinit var serverManager: HermesServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        progressBar = findViewById(R.id.progressBar)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        doneLayout = findViewById(R.id.doneLayout)
        openShellButton = findViewById(R.id.openShellButton)
        retryButton = findViewById(R.id.retryButton)

        serverManager = HermesServerManager(this)

        requestBatteryOptimizationExemption()
        startForegroundService()

        openShellButton.setOnClickListener {
            // Open the official Termux app if installed; otherwise show instructions
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.termux")
                if (intent != null) {
                    startActivity(intent)
                } else {
                    showShellInstructions()
                }
            } catch (e: Exception) {
                showShellInstructions()
            }
        }

        retryButton.setOnClickListener {
            startSetupFlow()
        }

        startSetupFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        serverManager.stopHermes()
        stopService(Intent(this, HermesForegroundService::class.java))
    }

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

    private fun startSetupFlow() {
        showLoading(true)
        doneLayout.visibility = View.GONE
        logView.text = ""
        setStatus("Initializing…")

        Thread {
            // Auto-retry the whole setup flow up to 3 times before showing
            // the error dialog. Each attempt leverages the per-step
            // is*Installed() checks in runSetup() to skip already-completed
            // steps, so retries resume from where they left off (step-level
            // resume) rather than restarting from scratch.
            val maxAttempts = 3
            var lastError: Exception? = null
            for (attempt in 1..maxAttempts) {
                try {
                    runSetup()
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    Log.e(TAG, "Setup attempt $attempt/$maxAttempts failed", e)
                    if (attempt < maxAttempts) {
                        val delayMs = 5000L * (1L shl (attempt - 1)) // 5s, 10s
                        runOnUiThread {
                            statusText.text = "Setup failed (attempt $attempt/$maxAttempts)"
                            statusDetail.text = "${e.message ?: "Unknown error"} — retrying in ${delayMs / 1000}s…"
                            statusDetail.visibility = View.VISIBLE
                            logView.append("\n[auto-retry $attempt/$maxAttempts] ${e.message}\n")
                            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                        Thread.sleep(delayMs)
                    }
                }
            }
            val e = lastError!!
            runOnUiThread {
                showError("Failed after $maxAttempts attempts.\n\nLast error: ${e.message ?: "Unknown"}")
            }
        }.start()
    }

    private fun runSetup() {
        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { msg -> updateStatus(msg) }
        }
        updateStatus("Environment ready")

        // Step 1b: Extract bundled deb cache (if present) into $prefix/tmp/.
        // This stages ALL .deb files the APK ships (python + proot + node +
        // build deps + transitive native deps) so every subsequent
        // install*() step can skip apt-get download and run fully offline.
        // If no bundle is bundled (local dev build), each step falls back
        // to apt-get download on its own.
        if (serverManager.extractDebBundleIfPresent { msg -> updateDetail(msg) }) {
            updateStatus("Bundled deps staged", "Skipping apt-get downloads")
        }

        // Step 2: Install proot (needed for dpkg/apt-get path remapping)
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…", "Needed for package management")
            val prootOk = serverManager.installProot { msg -> updateDetail(msg) }
            if (!prootOk) {
                throw RuntimeException("Failed to install proot")
            }
        }
        updateStatus("proot ready")

        // Step 3: Install Python (Hermes core runtime)
        if (!serverManager.isPythonInstalled()) {
            updateStatus("Installing Python…", "This may take a few minutes")
            val pyOk = serverManager.installPython { msg -> updateDetail(msg) }
            if (!pyOk) {
                throw RuntimeException("Failed to install Python")
            }
        }
        updateStatus("Python ready")

        // Step 4: Install build deps (rust, clang, make, libffi, openssl, etc.)
        updateStatus("Installing build dependencies…", "rust, clang, make, openssl, …")
        val depsOk = serverManager.installHermesBuildDeps { msg -> updateDetail(msg) }
        if (!depsOk) {
            Log.w(TAG, "Build deps install had issues — continuing")
        }
        updateStatus("Build dependencies ready")

        // Step 5: Install Hermes Agent
        if (!serverManager.isHermesInstalled()) {
            updateStatus("Installing Hermes Agent…", "git clone + pip install -e .[termux]")
            val hermesOk = serverManager.installHermes(
                onProgress = { msg -> updateDetail(msg) },
                onNeedCompile = {
                    // Called from the setup thread when Phase 1 (wheel cache)
                    // failed and Phase 2 (download rust+clang + source compile)
                    // is needed. Show a confirmation dialog and block until the
                    // user responds. Return true to proceed, false to abort.
                    askUserAboutCompile()
                },
            )
            if (!hermesOk) {
                throw RuntimeException("Failed to install Hermes Agent")
            }
        } else {
            updateStatus("Hermes Agent already installed")
        }
        updateStatus("Hermes Agent installed")

        // Step 6: Write skeleton config
        serverManager.configureHermesSkeleton()

        // Step 7: Health check
        updateStatus("Verifying install…", "hermes --version")
        val healthOk = serverManager.healthCheck { msg -> updateDetail(msg) }
        if (!healthOk) {
            Log.w(TAG, "Health check failed — Hermes may still work, continuing")
        }
        updateStatus("Setup complete")

        // Step 8: Show the done screen
        runOnUiThread {
            showLoading(false)
            doneLayout.visibility = View.VISIBLE
        }
    }

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

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> startSetupFlow() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * Block the calling (setup) thread until the user responds to the
     * "need source compile?" dialog. Returns true to proceed with the
     * ~600MB rust+clang download + 5-10 min compile, false to abort.
     *
     * The dialog is NOT cancelable via tap-outside — the user must
     * explicitly pick Continue or Abort. This is intentional: we're at
     * a fork in the install flow and either choice has consequences.
     */
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

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String, detail: String? = null) {
        statusText.text = text
        if (detail != null) {
            statusDetail.text = detail
            statusDetail.visibility = View.VISIBLE
        } else {
            statusDetail.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, detail: String? = null) {
        runOnUiThread { setStatus(text, detail) }
    }

    private fun updateDetail(text: String) {
        runOnUiThread {
            statusDetail.text = text
            statusDetail.visibility = View.VISIBLE
            // Append to log view too
            logView.append(text + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
