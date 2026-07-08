package com.nous.hermes.mobile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * 模型管理页面 — 管理 Hermes Agent 的模型 / Provider 配置。
 *
 * 不再依赖 hermes-webui 的 REST API，改为通过 [HermesConfigManager] 直接读写
 * `~/.hermes/config.yaml` 与 `~/.hermes/.env`：
 *   - [HermesConfigManager.readConfig]            读取当前配置（默认模型 / API Key / 自定义 provider）
 *   - [HermesConfigManager.setDefaultModel]       设置默认模型
 *   - [HermesConfigManager.setApiKey]             写入 / 更新 API Key（.env）
 *   - [HermesConfigManager.addCustomProvider]     添加自定义 provider（config.yaml）
 *   - [HermesConfigManager.removeCustomProvider]  删除自定义 provider（config.yaml）
 *
 * Provider 列表由 [HermesConfigManager.BUILTIN_PROVIDERS]（内置）与配置文件中的
 * custom_providers（自定义）合并而来。每个内置 provider 会检查 .env 中是否已设置
 * 对应的 API Key，未设置时可在卡片点击对话框中补填。
 *
 * UI 完全用代码构建（无 XML layout），视觉风格与 [SubSettingsActivity] 一致：
 * 深色背景、圆角卡片、StateListDrawable 点击反馈、@ 文字图标方块。
 *
 * 交互：
 *   - 点击 Provider 卡片 → 弹出模型选择 + API Key 编辑对话框
 *   - 点击「添加 Provider」→ 弹出表单对话框
 *   - 长按 Provider 卡片 → 删除确认对话框（仅自定义 provider）
 */
class ModelManagementActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelManagementActivity"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    /** 直接读写 ~/.hermes 配置文件，不经过 WebUI。 */
    private val configManager by lazy { HermesConfigManager(this) }

    // ── 颜色常量（主题色引用 [UiUtils]，保持与 SubSettingsActivity 一致） ─
    private val colorBg = UiUtils.BG
    private val colorClickable = UiUtils.CARD_CLICKABLE
    private val colorTitle = UiUtils.TEXT_PRIMARY
    private val colorSubtitle = UiUtils.TEXT_SECONDARY
    private val colorDim = 0xFF64748b.toInt()
    private val colorAccent = UiUtils.ACCENT
    private val colorCyan = 0xFF22d3ee.toInt()

    // badge 配色（文字色 + 暗底）
    private val badgeDefault = UiUtils.SUCCESS to UiUtils.BADGE_DEFAULT_BG   // 绿 默认
    private val badgeBuiltin = UiUtils.INFO to 0xFF1e1b4b.toInt()           // 紫 内置
    private val badgeCustom = UiUtils.WARNING to 0xFF451a03.toInt()         // 橙 自定义

    // ── 数据模型 ─────────────────────────────────────────────────────────
    /** 一个 provider 分组。 */
    private data class ProviderGroup(
        val provider: String,
        val label: String,
        val baseUrl: String,
        val models: List<String>,
        val apiKey: String,
        val builtin: Boolean,
        /**
         * 附加元数据。内置 / 自定义 provider 均会写入：
         *   - api_key_env   API Key 对应的环境变量名（如 OPENROUTER_API_KEY）
         *   - api_key_label API Key 的展示名（如 "OpenRouter API Key"）
         */
        val raw: JSONObject,
    )

    private var defaultModel: String = ""
    private var defaultProvider: String = ""
    private var groups: List<ProviderGroup> = emptyList()
    /** 最近一次读取的配置，用于查询 API Key 是否已设置。 */
    private var currentConfig: HermesConfigManager.Config? = null

    // ── UI 引用 ──────────────────────────────────────────────────────────
    private lateinit var contentScrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadModels()
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        // --- 标题栏（与 SubSettingsActivity 完全一致） ---
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorClickable)
            setPadding(
                (8 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
            elevation = 4 * density
        }
        val backBtn = TextView(this).apply {
            text = "‹"
            setTextColor(colorTitle)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                (12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            background = UiUtils.getClickableBackground(this@ModelManagementActivity)
            setOnClickListener { finish() }
        }
        val titleText = TextView(this).apply {
            text = "模型管理"
            setTextColor(colorTitle)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (8 * density).toInt() }
        }
        titleBar.addView(backBtn)
        titleBar.addView(titleText)
        root.addView(titleBar)

        // --- 内容区（FrameLayout 叠加：ScrollView / 加载 / 错误） ---
        val body = FrameLayout(this).apply {
            setBackgroundColor(colorBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // 内容滚动区
        contentScrollView = ScrollView(this).apply {
            setBackgroundColor(colorBg)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (16 * density).toInt(),
                (20 * density).toInt(), (20 * density).toInt()
            )
        }
        contentScrollView.addView(contentContainer)
        body.addView(contentScrollView)

        // 加载视图
        loadingView = makeLoadingView()
        body.addView(loadingView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 错误视图
        errorView = makeErrorView()
        body.addView(errorView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        root.addView(body)
        setContentView(root)

        showLoading()
    }

    /** 加载中视图：居中 ProgressBar。 */
    private fun makeLoadingView(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(colorBg)
            addView(ProgressBar(this@ModelManagementActivity).apply {
                indeterminateTintList = ColorStateList.valueOf(colorAccent)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
    }

    /** 错误视图：错误标题 + 详情 + 重试按钮。 */
    private fun makeErrorView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colorBg)
            setPadding(
                (40 * density).toInt(), (40 * density).toInt(),
                (40 * density).toInt(), (40 * density).toInt()
            )
            addView(TextView(this@ModelManagementActivity).apply {
                text = "加载失败"
                setTextColor(colorTitle)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            errorText = TextView(this@ModelManagementActivity).apply {
                setTextColor(colorSubtitle)
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * density).toInt() }
            }
            addView(errorText)
            addView(TextView(this@ModelManagementActivity).apply {
                text = "重试"
                setTextColor(colorAccent)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                background = UiUtils.getClickableBackground(this@ModelManagementActivity)
                isClickable = true
                isFocusable = true
                setPadding(
                    (24 * density).toInt(), (10 * density).toInt(),
                    (24 * density).toInt(), (10 * density).toInt()
                )
                setOnClickListener { loadModels() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (20 * density).toInt() }
            })
        }
    }

    /** 渲染卡片列表：添加按钮 + section 标题 + provider 卡片。 */
    private fun renderCards() {
        contentContainer.removeAllViews()

        // 顶部「添加 Provider」按钮
        contentContainer.addView(makeAddProviderButton())

        // section 标题
        contentContainer.addView(makeSectionLabel("已配置的 Provider（${groups.size}）"))

        if (groups.isEmpty()) {
            contentContainer.addView(TextView(this).apply {
                text = "暂无已配置的 Provider\n点击上方按钮添加"
                setTextColor(colorDim)
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (24 * density).toInt() }
            })
            return
        }

        for (group in groups) {
            contentContainer.addView(makeProviderCard(group))
        }
    }

    /** 「添加 Provider」按钮卡片（带 + 图标）。 */
    private fun makeAddProviderButton(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiUtils.getClickableBackground(this@ModelManagementActivity)
            setPadding(
                (16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * density).toInt() }
            isClickable = true
            isFocusable = true
            setOnClickListener { showAddProviderDialog() }

            // 图标方块
            val iconTile = LinearLayout(this@ModelManagementActivity).apply {
                gravity = Gravity.CENTER
                background = UiUtils.getIconTileBackground(this@ModelManagementActivity)
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            iconTile.addView(TextView(this@ModelManagementActivity).apply {
                text = "+"
                setTextColor(colorAccent)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(iconTile)

            // 文本
            val textCol = LinearLayout(this@ModelManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (16 * density).toInt() }
            }
            textCol.addView(TextView(this@ModelManagementActivity).apply {
                text = "添加 Provider"
                setTextColor(colorTitle)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(this@ModelManagementActivity).apply {
                text = "添加自定义模型提供商"
                setTextColor(colorSubtitle)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
            })
            addView(textCol)
        }
    }

    /** Provider 卡片：图标方块 + 名称/badge + base_url + API Key 状态 + 默认模型 + 模型数量/箭头。 */
    private fun makeProviderCard(group: ProviderGroup): LinearLayout {
        val isDefaultProvider =
            defaultProvider.isNotEmpty() && group.provider == defaultProvider
        val displayName = group.label.ifEmpty { group.provider.ifEmpty { "未命名 Provider" } }

        // API Key 状态（仅当 provider 关联了环境变量时）
        val apiKeyEnv = group.raw.optString("api_key_env", "")
        val apiKeyLabel = group.raw.optString("api_key_label", "API Key")
        val hasApiKeyEnv = apiKeyEnv.isNotEmpty()
        val isKeySet = hasApiKeyEnv &&
            currentConfig?.apiKeys?.get(apiKeyEnv)?.isNotEmpty() == true

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiUtils.getClickableBackground(this@ModelManagementActivity)
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
            isClickable = true
            isFocusable = true

            // 左侧：@ 图标方块
            val iconTile = LinearLayout(this@ModelManagementActivity).apply {
                gravity = Gravity.CENTER
                background = UiUtils.getIconTileBackground(this@ModelManagementActivity)
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            iconTile.addView(TextView(this@ModelManagementActivity).apply {
                text = "@"
                setTextColor(colorAccent)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(iconTile)

            // 中间文本列（weight=1）
            val textCol = LinearLayout(this@ModelManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (14 * density).toInt() }
            }

            // 第一行：名称 + badge
            val row1 = LinearLayout(this@ModelManagementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(this@ModelManagementActivity).apply {
                text = displayName
                setTextColor(colorTitle)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            if (isDefaultProvider) {
                row1.addView(UiUtils.makeBadge(this@ModelManagementActivity, "默认", badgeDefault.second, badgeDefault.first))
            }
            if (group.builtin) {
                row1.addView(UiUtils.makeBadge(this@ModelManagementActivity, "内置", badgeBuiltin.second, badgeBuiltin.first))
            } else {
                row1.addView(UiUtils.makeBadge(this@ModelManagementActivity, "自定义", badgeCustom.second, badgeCustom.first))
            }
            textCol.addView(row1)

            // 第二行：base_url（mono 灰色）
            if (group.baseUrl.isNotEmpty()) {
                textCol.addView(TextView(this@ModelManagementActivity).apply {
                    text = group.baseUrl
                    setTextColor(colorSubtitle)
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (3 * density).toInt() }
                })
            }

            // API Key 状态行：已设置（绿）/ 未设置（橙）
            if (hasApiKeyEnv) {
                textCol.addView(TextView(this@ModelManagementActivity).apply {
                    text = if (isKeySet) "● API Key 已设置" else "○ 未设置 API Key"
                    setTextColor(if (isKeySet) UiUtils.SUCCESS else UiUtils.WARNING)
                    textSize = 11f
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (3 * density).toInt() }
                })
            }

            // 第三行：当前默认模型（青色，仅默认 provider 显示）
            if (isDefaultProvider && defaultModel.isNotEmpty()) {
                textCol.addView(TextView(this@ModelManagementActivity).apply {
                    text = "当前默认：$defaultModel"
                    setTextColor(colorCyan)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (3 * density).toInt() }
                })
            }
            addView(textCol)

            // 右侧：模型数量 + › 箭头
            val rightCol = LinearLayout(this@ModelManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (10 * density).toInt() }
            }
            rightCol.addView(TextView(this@ModelManagementActivity).apply {
                text = "${group.models.size} 个模型"
                setTextColor(colorSubtitle)
                textSize = 11f
                gravity = Gravity.CENTER
            })
            rightCol.addView(TextView(this@ModelManagementActivity).apply {
                text = "›"
                setTextColor(colorDim)
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
            })
            addView(rightCol)

            // 交互：点击选模型 / 编辑 API Key，长按删除
            setOnClickListener {
                showProviderDetailDialog(group, apiKeyEnv, apiKeyLabel, hasApiKeyEnv, isKeySet)
            }
            setOnLongClickListener {
                if (group.builtin) {
                    Toast.makeText(
                        this@ModelManagementActivity,
                        "内置 Provider 不可删除",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showDeleteConfirmDialog(group)
                }
                true
            }
        }
    }

    /** badge 标签已提取至 [UiUtils.makeBadge]。 */

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(colorDim)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (2 * density).toInt()
                marginStart = (4 * density).toInt()
            }
        }
    }

    // ── 状态切换 ─────────────────────────────────────────────────────────

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        contentScrollView.visibility = View.GONE
    }

    private fun showContent() {
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        contentScrollView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        contentScrollView.visibility = View.GONE
        errorText.text = message
    }

    // ── 对话框 ───────────────────────────────────────────────────────────

    /**
     * 点击 Provider 卡片：弹出该 provider 的模型列表（可设为默认），
     * 若该 provider 关联了 API Key 环境变量，附带「设置 / 更换 API Key」入口。
     */
    private fun showProviderDetailDialog(
        group: ProviderGroup,
        apiKeyEnv: String,
        apiKeyLabel: String,
        hasApiKeyEnv: Boolean,
        isKeySet: Boolean,
    ) {
        if (group.models.isEmpty() && !hasApiKeyEnv) {
            Toast.makeText(this, "该 Provider 暂无可用模型", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(group.label)
            .setNegativeButton("关闭", null)

        // 顶部展示 API Key 状态
        if (hasApiKeyEnv) {
            val status = if (isKeySet) "已设置" else "未设置"
            builder.setMessage("$apiKeyLabel：$status")
        }

        // 模型单选列表（设为默认）
        if (group.models.isNotEmpty()) {
            val models = group.models.toTypedArray()
            // 若该 provider 正是默认 provider，预选当前默认模型
            val checkedItem = if (group.provider == defaultProvider) {
                group.models.indexOf(defaultModel)
            } else -1
            builder.setSingleChoiceItems(models, checkedItem) { dialog, which ->
                val selected = group.models[which]
                dialog.dismiss()
                setDefaultModel(selected, group.provider)
            }
        }

        // API Key 编辑入口
        if (hasApiKeyEnv) {
            val btnText = if (isKeySet) "更换 API Key" else "设置 API Key"
            builder.setNeutralButton(btnText) { _, _ ->
                showApiKeyEditDialog(apiKeyEnv, apiKeyLabel)
            }
        }

        builder.show()
    }

    /** 设置 / 更换某个环境变量对应的 API Key（写入 .env）。 */
    private fun showApiKeyEditDialog(apiKeyEnv: String, apiKeyLabel: String) {
        val et = EditText(this).apply {
            hint = "输入 $apiKeyLabel"
            setTextColor(colorTitle)
            setHintTextColor(colorDim)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = UiUtils.getPlainBackground(this@ModelManagementActivity)
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
        }
        val container = LinearLayout(this).apply {
            setPadding(
                (20 * density).toInt(), (8 * density).toInt(),
                (20 * density).toInt(), (4 * density).toInt()
            )
            addView(et, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(apiKeyLabel)
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = et.text.toString().trim()
                if (value.isEmpty()) {
                    Toast.makeText(this, "API Key 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                setApiKey(apiKeyEnv, value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点击「添加 Provider」：弹出表单（name / base_url / api_key / model）。 */
    private fun showAddProviderDialog() {
        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (4 * density).toInt(), (4 * density).toInt(),
                (4 * density).toInt(), (8 * density).toInt()
            )
        }

        val nameEt = addFormField(form, "Provider 名称", "如 OpenAI Compatible", false, false)
        val baseUrlEt = addFormField(form, "Base URL", "https://api.example.com/v1", false, true)
        val apiKeyEt = addFormField(form, "API Key", "sk-...", true, true)
        val modelEt = addFormField(form, "默认模型", "如 gpt-4o", false, false)

        scroll.addView(form)

        MaterialAlertDialogBuilder(this)
            .setTitle("添加 Provider")
            .setView(scroll)
            .setPositiveButton("添加") { _, _ ->
                val name = nameEt.text.toString().trim()
                val baseUrl = baseUrlEt.text.toString().trim()
                val apiKey = apiKeyEt.text.toString().trim()
                val model = modelEt.text.toString().trim()
                if (name.isEmpty() || baseUrl.isEmpty()) {
                    Toast.makeText(this, "名称和 Base URL 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addProvider(name, baseUrl, apiKey, model)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 向表单容器追加一个「标签 + 输入框」字段，返回该 EditText。 */
    private fun addFormField(
        container: LinearLayout,
        label: String,
        hint: String,
        isPassword: Boolean,
        mono: Boolean,
    ): EditText {
        container.addView(TextView(this).apply {
            text = label
            setTextColor(colorSubtitle)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        })
        val et = EditText(this).apply {
            this.hint = hint
            setTextColor(colorTitle)
            setHintTextColor(colorDim)
            textSize = 14f
            if (mono) typeface = Typeface.MONOSPACE
            inputType = if (isPassword) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            background = UiUtils.getPlainBackground(this@ModelManagementActivity)
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
        }
        container.addView(et)
        return et
    }

    /** 长按自定义 Provider：删除确认对话框。 */
    private fun showDeleteConfirmDialog(group: ProviderGroup) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 Provider")
            .setMessage("确定要删除「${group.label}」吗？\n此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                deleteProvider(group.provider)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 非阻塞进度对话框（用于写操作期间）。 */
    private fun showProgressDialog(message: String): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt()
            )
        }
        container.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(colorAccent)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(TextView(this).apply {
            text = message
            setTextColor(colorTitle)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * density).toInt() }
        })
        // .show() 同时创建并显示对话框，返回 AlertDialog 供完成后 dismiss()
        return MaterialAlertDialogBuilder(this)
            .setView(container)
            .setCancelable(false)
            .show()
    }

    // ── 配置读写（直接操作 ~/.hermes，不依赖 WebUI） ──────────────────────

    /** 读取 config.yaml / .env，合并内置与自定义 provider，渲染卡片。 */
    private fun loadModels() {
        showLoading()
        Thread {
            try {
                if (!configManager.isConfigAvailable()) {
                    handler.post {
                        showError("未找到 Hermes 配置目录\n请先安装并初始化 Hermes Agent")
                    }
                    return@Thread
                }

                val config = configManager.readConfig()
                val parsed = ArrayList<ProviderGroup>()

                // 内置 provider：始终列出，并标记 API Key 是否已配置
                for (bp in HermesConfigManager.BUILTIN_PROVIDERS) {
                    parsed.add(
                        ProviderGroup(
                            provider = bp.key,
                            label = bp.label,
                            baseUrl = bp.baseUrl,
                            models = bp.models,
                            apiKey = "",
                            builtin = true,
                            raw = JSONObject().apply {
                                put("api_key_env", bp.apiKeyEnv)
                                put("api_key_label", bp.apiKeyLabel)
                            },
                        )
                    )
                }

                // 自定义 provider：来自 config.yaml 的 custom_providers
                for (cp in config.customProviders) {
                    parsed.add(
                        ProviderGroup(
                            provider = cp.name,
                            label = cp.name,
                            baseUrl = cp.baseUrl,
                            models = cp.models,
                            apiKey = "",
                            builtin = false,
                            raw = JSONObject().apply {
                                put("api_key_env", cp.apiKeyEnv)
                                put("api_key_label", "${cp.name} API Key")
                            },
                        )
                    )
                }

                handler.post {
                    currentConfig = config
                    defaultModel = config.defaultModel
                    defaultProvider = config.defaultProvider
                    groups = parsed
                    renderCards()
                    showContent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModels failed", e)
                handler.post { showError(e.message ?: "未知错误") }
            }
        }.start()
    }

    /** 设置默认模型 / provider（写入 config.yaml）。 */
    private fun setDefaultModel(model: String, provider: String) {
        val dialog = showProgressDialog("正在设置默认模型…")
        Thread {
            try {
                val ok = configManager.setDefaultModel(provider, model)
                handler.post {
                    dialog.dismiss()
                    if (ok) {
                        Toast.makeText(this, "已设为默认模型", Toast.LENGTH_SHORT).show()
                        refreshModels()
                    } else {
                        Toast.makeText(this, "设置失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setDefaultModel failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "设置失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * 添加自定义 provider：先保存 API Key（.env），再写入 provider 定义（config.yaml）。
     * API Key 环境变量名由 provider 名称派生（大写 + 非字母数字转下划线 + _API_KEY）。
     */
    private fun addProvider(name: String, baseUrl: String, apiKey: String, model: String) {
        val dialog = showProgressDialog("正在添加 Provider…")
        Thread {
            try {
                val apiKeyEnv = (name.uppercase()
                    .replace(Regex("[^A-Z0-9]+"), "_")
                    .trim('_')) + "_API_KEY"

                var ok = true
                if (apiKey.isNotEmpty()) {
                    ok = configManager.setApiKey(apiKeyEnv, apiKey)
                }
                if (ok) {
                    val models = if (model.isNotEmpty()) listOf(model) else emptyList()
                    ok = configManager.addCustomProvider(name, baseUrl, apiKeyEnv, models)
                }

                handler.post {
                    dialog.dismiss()
                    if (ok) {
                        Toast.makeText(this, "Provider 已添加", Toast.LENGTH_SHORT).show()
                        refreshModels()
                    } else {
                        Toast.makeText(this, "添加失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "addProvider failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "添加失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 删除自定义 provider（从 config.yaml 移除）。 */
    private fun deleteProvider(name: String) {
        val dialog = showProgressDialog("正在删除 Provider…")
        Thread {
            try {
                val ok = configManager.removeCustomProvider(name)
                handler.post {
                    dialog.dismiss()
                    if (ok) {
                        Toast.makeText(this, "Provider 已删除", Toast.LENGTH_SHORT).show()
                        refreshModels()
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteProvider failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 保存 / 更新 API Key（写入 .env）。 */
    private fun setApiKey(apiKeyEnv: String, value: String) {
        val dialog = showProgressDialog("正在保存 API Key…")
        Thread {
            try {
                val ok = configManager.setApiKey(apiKeyEnv, value)
                handler.post {
                    dialog.dismiss()
                    if (ok) {
                        Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show()
                        refreshModels()
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setApiKey failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 重新加载模型列表。 */
    private fun refreshModels() {
        loadModels()
    }

    // ── Drawable 工厂已提取至 [UiUtils]（保持视觉完全一致） ───────────────
}
