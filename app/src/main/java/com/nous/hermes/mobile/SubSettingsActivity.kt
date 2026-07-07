package com.nous.hermes.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置二级页面 — 将设置页的"更新"和"维护"类按钮合并到此处展示。
 *
 * 交互流程：
 * 1. MainActivity 设置页显示"更新管理"/"维护工具"两个入口卡片。
 * 2. 点击后启动本 Activity，传入 EXTRA_CATEGORY 指定显示哪类按钮。
 * 3. 用户点击具体操作后，setResult + finish 返回 MainActivity 执行。
 *
 * 版本检测在本 Activity 内独立完成（实例化 HermesServerManager /
 * HermesStudioInstaller），更新执行交回 MainActivity（需要安装锁/日志面板）。
 */
class SubSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SubSettingsActivity"
        const val EXTRA_CATEGORY = "category"
        const val CATEGORY_UPDATES = "updates"
        const val CATEGORY_MAINTENANCE = "maintenance"

        // 返回 MainActivity 的结果码
        const val RESULT_UPDATE_HERMES = 101
        const val RESULT_UPDATE_WEBUI = 102
        const val RESULT_UPDATE_APK = 103
        const val RESULT_RERUN_SETUP = 104

        private const val PREF_UPDATE_CHANNEL = "update_channel"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var serverManager: HermesServerManager
    private lateinit var studioInstaller: HermesStudioInstaller

    private lateinit var hermesVersionText: TextView
    private lateinit var webuiVersionText: TextView
    private lateinit var apkVersionText: TextView
    private lateinit var hermesUpdateBadge: TextView
    private lateinit var webuiUpdateBadge: TextView
    private lateinit var apkUpdateBadge: TextView

    private var btnChannelStable: TextView? = null
    private var btnChannelBeta: TextView? = null

    private val density by lazy { resources.displayMetrics.density }

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serverManager = HermesServerManager(this)
        studioInstaller = HermesStudioInstaller(this)

        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: CATEGORY_UPDATES
        val title = when (category) {
            CATEGORY_UPDATES -> "更新管理"
            CATEGORY_MAINTENANCE -> "维护工具"
            else -> "设置"
        }

        buildUI(title, category)

        if (category == CATEGORY_UPDATES) {
            checkVersionsAndUpdates()
        }
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────────

    private fun buildUI(title: String, category: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF020617.toInt())
        }

        // --- 标题栏 ---
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(
                (8 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
            elevation = 4 * density
        }
        val backBtn = TextView(this).apply {
            text = "‹"
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                (12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            background = getClickableBackground()
            setOnClickListener { finish() }
        }
        val titleText = TextView(this).apply {
            text = title
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        }
        titleBar.addView(backBtn)
        titleBar.addView(titleText)
        root.addView(titleBar)

        // --- 滚动内容区 ---
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF020617.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (20 * density).toInt())
        }

        when (category) {
            CATEGORY_UPDATES -> buildUpdatesSection(content)
            CATEGORY_MAINTENANCE -> buildMaintenanceSection(content)
        }

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)
    }

    /** 构建更新管理页面：Hermes / WebUI / APK 更新卡片 + 通道选择 */
    private fun buildUpdatesSection(container: LinearLayout) {
        // ── Hermes Agent 更新卡片 ──
        val (hermesCard, hermesVer, hermesBadge) = makeUpdateCard(
            title = "更新 Hermes Agent",
            subtitle = "",
        )
        hermesVersionText = hermesVer
        hermesUpdateBadge = hermesBadge
        hermesCard.setOnClickListener {
            setResult(RESULT_UPDATE_HERMES)
            finish()
        }
        container.addView(hermesCard)

        // ── WebUI 更新卡片 ──
        val (webuiCard, webuiVer, webuiBadge) = makeUpdateCard(
            title = "更新 WebUI",
            subtitle = "",
        )
        webuiVersionText = webuiVer
        webuiUpdateBadge = webuiBadge
        webuiCard.setOnClickListener {
            setResult(RESULT_UPDATE_WEBUI)
            finish()
        }
        container.addView(webuiCard)

        // ── APK 更新卡片 ──
        val (apkCard, apkVer, apkBadge) = makeUpdateCard(
            title = "更新应用 (APK)",
            subtitle = "",
        )
        apkVersionText = apkVer
        apkUpdateBadge = apkBadge
        apkCard.setOnClickListener {
            setResult(RESULT_UPDATE_APK)
            finish()
        }
        container.addView(apkCard)

        // ── APK 更新通道选择 ──
        container.addView(makeSectionLabel("APK 更新通道"))
        container.addView(makeHintText("仅适用于上方\"更新应用 (APK)\"的更新检测"))

        val channelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }
        val stable = makeChannelButton("正式版")
        val beta = makeChannelButton("测试版")
        btnChannelStable = stable
        btnChannelBeta = beta
        stable.setOnClickListener {
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_UPDATE_CHANNEL, ApkUpdateChecker.CHANNEL_STABLE).apply()
            updateChannelToggleUI()
            checkVersionsAndUpdates()
        }
        beta.setOnClickListener {
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_UPDATE_CHANNEL, ApkUpdateChecker.CHANNEL_BETA).apply()
            updateChannelToggleUI()
            checkVersionsAndUpdates()
        }
        channelRow.addView(stable)
        channelRow.addView(beta)
        container.addView(channelRow)
        updateChannelToggleUI()
    }

    /** 构建维护工具页面：重新安装 + 电池优化 */
    private fun buildMaintenanceSection(container: LinearLayout) {
        // ── 重新安装环境 ──
        val rerunCard = makeActionCard(
            title = "重新安装环境",
            subtitle = "重新执行 proot → 依赖 → Agent → WebUI 安装",
        ) {
            setResult(RESULT_RERUN_SETUP)
            finish()
        }
        container.addView(rerunCard)

        // ── 电池优化 ──
        val batteryCard = makeActionCard(
            title = "电池优化白名单",
            subtitle = "将应用加入电池优化白名单，避免被系统杀后台",
        ) {
            requestBatteryOptimizationExemption()
        }
        container.addView(batteryCard)
    }

    // ── UI 工具方法 ──────────────────────────────────────────────────────────

    /** 创建一个更新卡片，返回 (card, versionText, badgeText) */
    private fun makeUpdateCard(
        title: String,
        subtitle: String,
    ): Triple<LinearLayout, TextView, TextView> {
        val badge = TextView(this).apply {
            text = "有更新"
            setTextColor(0xFF10b981.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            background = getBadgeBackground()
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            visibility = View.GONE
        }
        val version = TextView(this).apply {
            text = "—"
            setTextColor(0xFF64748b.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }
        val card = makeCardBase(title, subtitle, version, badge, true)
        return Triple(card, version, badge)
    }

    /** 创建一个操作卡片（无版本号/badge） */
    private fun makeActionCard(
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val card = makeCardBase(title, subtitle, null, null, true)
        card.setOnClickListener { onClick() }
        return card
    }

    /** 卡片基础布局 */
    private fun makeCardBase(
        title: String,
        subtitle: String,
        versionView: TextView?,
        badgeView: TextView?,
        clickable: Boolean,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (clickable) getClickableBackground() else getPlainBackground()
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
            if (clickable) {
                isClickable = true
                isFocusable = true
            }

            // 图标方块
            val iconTile = LinearLayout(this@SubSettingsActivity).apply {
                gravity = Gravity.CENTER
                background = getIconTileBackground()
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            val icon = TextView(this@SubSettingsActivity).apply {
                text = "↻"
                setTextColor(0xFF818cf8.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
            }
            iconTile.addView(icon)
            addView(iconTile)

            // 文本区
            val textCol = LinearLayout(this@SubSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * density).toInt()
                }
            }
            textCol.addView(TextView(this@SubSettingsActivity).apply {
                text = title
                setTextColor(0xFFe2e8f0.toInt())
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            if (subtitle.isNotEmpty()) {
                textCol.addView(TextView(this@SubSettingsActivity).apply {
                    text = subtitle
                    setTextColor(0xFF94a3b8.toInt())
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (2 * density).toInt() }
                })
            }
            versionView?.let { textCol.addView(it) }
            addView(textCol)

            // badge + chevron
            badgeView?.let { addView(it) }
            if (badgeView != null) {
                (badgeView.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            addView(TextView(this@SubSettingsActivity).apply {
                text = "›"
                setTextColor(0xFF64748b.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(), (20 * density).toInt()
                )
            })
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFF64748b.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (2 * density).toInt()
                marginStart = (4 * density).toInt()
            }
        }
    }

    private fun makeHintText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFF475569.toInt())
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
                marginStart = (4 * density).toInt()
            }
        }
    }

    private fun makeChannelButton(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            background = getClickableBackground()
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, (36 * density).toInt(), 1f).apply {
                marginEnd = (4 * density).toInt()
                marginStart = (4 * density).toInt()
            }
        }
    }

    // ── Drawable 工厂（代码构建，避免依赖 XML） ────────────────────────────

    private fun getClickableBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1e293b.toInt())
                    cornerRadius = 12 * density
                })
            addState(intArrayOf(),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF0f172a.toInt())
                    cornerRadius = 12 * density
                    setStroke(1, 0xFF334155.toInt())
                })
        }
    }

    private fun getPlainBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0f172a.toInt())
            cornerRadius = 12 * density
            setStroke(1, 0xFF334155.toInt())
        }
    }

    private fun getIconTileBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1e293b.toInt())
            cornerRadius = 10 * density
        }
    }

    private fun getBadgeBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF064e3b.toInt())
            cornerRadius = 6 * density
            setStroke(1, 0xFF10b981.toInt())
        }
    }

    // ── 版本检测 ─────────────────────────────────────────────────────────────

    private fun checkVersionsAndUpdates() {
        val defaultColor = 0xFF64748b.toInt()
        hermesVersionText.text = "检测中…"
        webuiVersionText.text = "检测中…"
        hermesVersionText.setTextColor(defaultColor)
        webuiVersionText.setTextColor(defaultColor)
        apkVersionText.setTextColor(defaultColor)
        hermesUpdateBadge.visibility = View.GONE
        webuiUpdateBadge.visibility = View.GONE
        apkUpdateBadge.visibility = View.GONE

        Thread {
            val hermesVer = try { serverManager.getHermesVersion() } catch (e: Exception) {
                Log.e(TAG, "getHermesVersion failed", e); null
            }
            val webuiVer = try { studioInstaller.getWebUIVersion() } catch (e: Exception) {
                Log.e(TAG, "getWebUIVersion failed", e); null
            }

            handler.post {
                hermesVersionText.text = hermesVer ?: "未安装"
                webuiVersionText.text = webuiVer ?: "未安装"
                if (hermesVer == null && serverManager.isHermesInstalled()) {
                    hermesVersionText.text = "已安装（版本获取失败）"
                }
                if (webuiVer == null && studioInstaller.isInstalled()) {
                    webuiVersionText.text = "已安装（版本获取失败）"
                }
            }

            if (hermesVer != null) {
                val latest = try { serverManager.checkHermesUpdate() } catch (e: Exception) { null }
                handler.post {
                    if (latest != null) {
                        hermesVersionText.text = "$hermesVer → $latest"
                        hermesVersionText.setTextColor(0xFF10b981.toInt())
                        hermesUpdateBadge.visibility = View.VISIBLE
                    }
                }
            }

            if (webuiVer != null) {
                val latest = try { studioInstaller.checkWebUIUpdate() } catch (e: Exception) { null }
                handler.post {
                    if (latest != null) {
                        webuiVersionText.text = "$webuiVer → $latest"
                        webuiVersionText.setTextColor(0xFF10b981.toInt())
                        webuiUpdateBadge.visibility = View.VISIBLE
                    }
                }
            }

            val apkVer = getVersionName()
            val apkVerDisplay = getVersionDisplayText()
            val channel = getUpdateChannel()
            val apkPath = getLocalApkPath()
            val apkUpdate = try { ApkUpdateChecker.checkUpdate(apkVer, channel, apkPath) } catch (e: Exception) { null }
            handler.post {
                if (apkUpdate != null) {
                    apkVersionText.text = "$apkVerDisplay → ${apkUpdate.version}"
                    apkVersionText.setTextColor(0xFF10b981.toInt())
                    apkUpdateBadge.visibility = View.VISIBLE
                } else {
                    apkVersionText.text = apkVerDisplay
                }
            }
        }.start()
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
     * 判断当前是否为测试版构建。
     * 通过版本号是否包含 "beta" 来区分正式版和测试版。
     */
    private fun isBetaBuild(): Boolean {
        return getVersionName().contains("beta", ignoreCase = true)
    }

    /**
     * 获取带通道标识的版本显示文本。
     * 测试版追加 "(测试版)" 后缀，正式版不追加。
     */
    private fun getVersionDisplayText(): String {
        val ver = getVersionName()
        return if (isBetaBuild()) "$ver (测试版)" else ver
    }

    /** 本地已安装 APK 的文件路径，用于 SHA256 比较 */
    private fun getLocalApkPath(): String? {
        return try {
            packageManager.getPackageInfo(packageName, 0).applicationInfo?.sourceDir
        } catch (e: Exception) {
            Log.w(TAG, "getLocalApkPath failed", e); null
        }
    }

    private fun getUpdateChannel(): String {
        return getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            .getString(PREF_UPDATE_CHANNEL, ApkUpdateChecker.CHANNEL_BETA)
            ?: ApkUpdateChecker.CHANNEL_BETA
    }

    private fun updateChannelToggleUI() {
        val channel = getUpdateChannel()
        if (channel == ApkUpdateChecker.CHANNEL_STABLE) {
            btnChannelStable?.setTextColor(0xFF10b981.toInt())
            btnChannelBeta?.setTextColor(0xFF94a3b8.toInt())
        } else {
            btnChannelStable?.setTextColor(0xFF94a3b8.toInt())
            btnChannelBeta?.setTextColor(0xFF10b981.toInt())
        }
    }

    // ── 电池优化 ─────────────────────────────────────────────────────────────

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
            Toast.makeText(this, "无法打开电池设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
