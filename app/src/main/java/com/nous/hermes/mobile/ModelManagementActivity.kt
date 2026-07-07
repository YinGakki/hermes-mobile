package com.nous.hermes.mobile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 模型管理页面 — 管理 Hermes Agent 的模型 / Provider 配置。
 *
 * 通过 hermes-web-ui（端口 8648）提供的 REST API 完成：
 *   - GET    /api/hermes/available-models            获取所有可用模型 / provider
 *   - PUT    /api/hermes/config/model                设置默认模型
 *   - POST   /api/hermes/config/providers            添加自定义 provider
 *   - DELETE /api/hermes/config/providers/:poolKey   删除自定义 provider
 *
 * UI 完全用代码构建（无 XML layout），视觉风格与 [SubSettingsActivity] 一致：
 * 深色背景、圆角卡片、StateListDrawable 点击反馈、@ 文字图标方块。
 *
 * 交互：
 *   - 点击 Provider 卡片 → 弹出模型选择对话框（设为默认）
 *   - 点击「添加 Provider」→ 弹出表单对话框
 *   - 长按 Provider 卡片 → 删除确认对话框（仅自定义 provider）
 */
class ModelManagementActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelManagementActivity"
        private const val BASE_URL = "http://127.0.0.1:8648"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // ── 颜色常量（与 SubSettingsActivity / 主题一致） ─────────────────────
    private val colorBg = 0xFF020617.toInt()
    private val colorCard = 0xFF0f172a.toInt()
    private val colorClickable = 0xFF1e293b.toInt()
    private val colorStroke = 0xFF334155.toInt()
    private val colorTitle = 0xFFe2e8f0.toInt()
    private val colorSubtitle = 0xFF94a3b8.toInt()
    private val colorDim = 0xFF64748b.toInt()
    private val colorAccent = 0xFF818cf8.toInt()
    private val colorCyan = 0xFF22d3ee.toInt()

    // badge 配色（文字色 + 暗底）
    private val badgeDefault = 0xFF10b981.toInt() to 0xFF064e3b.toInt()   // 绿 默认
    private val badgeBuiltin = 0xFF6366f1.toInt() to 0xFF1e1b4b.toInt()   // 紫 内置
    private val badgeCustom = 0xFFf59e0b.toInt() to 0xFF451a03.toInt()    // 橙 自定义

    // ── 数据模型 ─────────────────────────────────────────────────────────
    /** 一个 provider 分组（对应 available-models.groups[] 的一项）。 */
    private data class ProviderGroup(
        val provider: String,
        val label: String,
        val baseUrl: String,
        val models: List<String>,
        val apiKey: String,
        val builtin: Boolean,
        /** 保留原始 JSON，删除时从中读取 pool_key / source / provider_key 等字段。 */
        val raw: JSONObject,
    )

    private var defaultModel: String = ""
    private var defaultProvider: String = ""
    private var groups: List<ProviderGroup> = emptyList()
    private var allProvidersRaw: JSONArray? = null

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
            background = getClickableBackground()
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
                background = getClickableBackground()
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
            background = getClickableBackground()
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
                background = getIconTileBackground()
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

    /** Provider 卡片：图标方块 + 名称/badge + base_url + 默认模型 + 模型数量/箭头。 */
    private fun makeProviderCard(group: ProviderGroup): LinearLayout {
        val isDefaultProvider =
            defaultProvider.isNotEmpty() && group.provider == defaultProvider
        val displayName = group.label.ifEmpty { group.provider.ifEmpty { "未命名 Provider" } }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getClickableBackground()
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
                background = getIconTileBackground()
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
                row1.addView(makeBadge("默认", badgeDefault.first, badgeDefault.second))
            }
            if (group.builtin) {
                row1.addView(makeBadge("内置", badgeBuiltin.first, badgeBuiltin.second))
            } else {
                row1.addView(makeBadge("自定义", badgeCustom.first, badgeCustom.second))
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

            // 交互：点击选模型，长按删除
            setOnClickListener { showModelSelectorDialog(group) }
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

    /** 创建一个 badge 标签。 */
    private fun makeBadge(text: String, textColor: Int, darkBg: Int): TextView {
        // 绿色默认 badge 复用与 SubSettingsActivity 完全一致的 getBadgeBackground()
        val isGreenDefault =
            textColor == badgeDefault.first && darkBg == badgeDefault.second
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            background = if (isGreenDefault) getBadgeBackground()
            else getBadgeBackground(darkBg, textColor)
            setPadding(
                (8 * density).toInt(), (3 * density).toInt(),
                (8 * density).toInt(), (3 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (6 * density).toInt() }
        }
    }

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

    /** 点击 Provider 卡片：弹出该 provider 的模型列表，可选择设为默认。 */
    private fun showModelSelectorDialog(group: ProviderGroup) {
        if (group.models.isEmpty()) {
            Toast.makeText(this, "该 Provider 暂无可用模型", Toast.LENGTH_SHORT).show()
            return
        }
        val models = group.models.toTypedArray()
        // 若该 provider 正是默认 provider，预选当前默认模型
        val checkedItem = if (group.provider == defaultProvider) {
            group.models.indexOf(defaultModel)
        } else -1

        MaterialAlertDialogBuilder(this)
            .setTitle("${group.label} · 选择默认模型")
            .setSingleChoiceItems(models, checkedItem) { dialog, which ->
                val selected = group.models[which]
                dialog.dismiss()
                setDefaultModel(selected, group.provider)
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
            background = getPlainBackground()
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
                val (poolKey, source, providerKey) = resolveDeleteKeys(group)
                deleteProvider(poolKey, source, providerKey)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 解析删除所需的 poolKey / source / providerKey。
     *
     * DELETE /api/hermes/config/providers/:poolKey?source=...&providerKey=...
     *
     * 优先从 provider 分组的原始 JSON 读取 pool_key / source / provider_key
     * 等字段；缺失时回退到 allProviders 数组中匹配项；最终回退到 provider
     * 标识与 "custom_providers"。
     */
    private fun resolveDeleteKeys(group: ProviderGroup): Triple<String, String, String> {
        val raw = group.raw
        var poolKey = firstNonEmpty(
            raw.optString("pool_key", null),
            raw.optString("poolKey", null),
        ) ?: group.provider
        var source = firstNonEmpty(raw.optString("source", null)) ?: "custom_providers"
        var providerKey = firstNonEmpty(
            raw.optString("provider_key", null),
            raw.optString("providerKey", null),
            raw.optString("key", null),
        ) ?: group.provider

        // 从 allProviders 补全缺失字段
        allProvidersRaw?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val matches = p.optString("provider", null) == group.provider ||
                    p.optString("provider_key", null) == group.provider ||
                    p.optString("name", null) == group.label
                if (matches) {
                    if (poolKey == group.provider) {
                        poolKey = firstNonEmpty(
                            p.optString("pool_key", null),
                            p.optString("poolKey", null),
                        ) ?: poolKey
                    }
                    if (source == "custom_providers") {
                        source = firstNonEmpty(p.optString("source", null)) ?: source
                    }
                    if (providerKey == group.provider) {
                        providerKey = firstNonEmpty(
                            p.optString("provider_key", null),
                            p.optString("providerKey", null),
                            p.optString("key", null),
                        ) ?: providerKey
                    }
                    break
                }
            }
        }
        return Triple(poolKey, source, providerKey)
    }

    private fun firstNonEmpty(vararg candidates: String?): String? {
        return candidates.firstOrNull { !it.isNullOrEmpty() }
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

    // ── API 调用 ─────────────────────────────────────────────────────────

    /** GET /api/hermes/available-models → 解析并渲染卡片。 */
    private fun loadModels() {
        showLoading()
        Thread {
            try {
                val resp = httpRequest("GET", "/api/hermes/available-models")
                val json = JSONObject(resp)
                val def = json.optString("default", "")
                val defProv = json.optString("default_provider", "")
                val allProviders = json.optJSONArray("allProviders")
                val groupsArr = json.optJSONArray("groups")

                val parsed = ArrayList<ProviderGroup>()
                if (groupsArr != null) {
                    for (i in 0 until groupsArr.length()) {
                        val g = groupsArr.optJSONObject(i) ?: continue
                        val modelsArr = g.optJSONArray("models")
                        val modelsList = ArrayList<String>()
                        if (modelsArr != null) {
                            for (j in 0 until modelsArr.length()) {
                                modelsList.add(modelsArr.optString(j))
                            }
                        }
                        parsed.add(
                            ProviderGroup(
                                provider = g.optString("provider", ""),
                                label = g.optString("label", g.optString("provider", "未命名")),
                                baseUrl = g.optString("base_url", ""),
                                models = modelsList,
                                apiKey = g.optString("api_key", ""),
                                builtin = g.optBoolean("builtin", false),
                                raw = g,
                            )
                        )
                    }
                }

                handler.post {
                    defaultModel = def
                    defaultProvider = defProv
                    allProvidersRaw = allProviders
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

    /** PUT /api/hermes/config/model — 设置默认模型。 */
    private fun setDefaultModel(model: String, provider: String) {
        val dialog = showProgressDialog("正在设置默认模型…")
        Thread {
            try {
                val body = JSONObject().apply {
                    put("default", model)
                    put("provider", provider)
                }.toString()
                httpRequest("PUT", "/api/hermes/config/model", body)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "已设为默认模型", Toast.LENGTH_SHORT).show()
                    refreshModels()
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

    /** POST /api/hermes/config/providers — 添加自定义 provider。 */
    private fun addProvider(name: String, baseUrl: String, apiKey: String, model: String) {
        val dialog = showProgressDialog("正在添加 Provider…")
        Thread {
            try {
                val body = JSONObject().apply {
                    put("name", name)
                    put("base_url", baseUrl)
                    put("api_key", apiKey)
                    put("model", model)
                }.toString()
                httpRequest("POST", "/api/hermes/config/providers", body)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "Provider 已添加", Toast.LENGTH_SHORT).show()
                    refreshModels()
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

    /** DELETE /api/hermes/config/providers/:poolKey?source=...&providerKey=... — 删除 provider。 */
    private fun deleteProvider(poolKey: String, source: String, providerKey: String) {
        val dialog = showProgressDialog("正在删除 Provider…")
        Thread {
            try {
                val path = "/api/hermes/config/providers/" +
                    URLEncoder.encode(poolKey, "UTF-8") +
                    "?source=" + URLEncoder.encode(source, "UTF-8") +
                    "&providerKey=" + URLEncoder.encode(providerKey, "UTF-8")
                httpRequest("DELETE", path)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "Provider 已删除", Toast.LENGTH_SHORT).show()
                    refreshModels()
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

    /** 重新加载模型列表。 */
    private fun refreshModels() {
        loadModels()
    }

    // ── HTTP 工具 ────────────────────────────────────────────────────────

    /**
     * 执行一次 HTTP 请求，返回响应体字符串。
     * 仅在后台线程调用；非 2xx 响应抛出 [IOException]。
     */
    @Throws(IOException::class)
    private fun httpRequest(method: String, path: String, body: String? = null): String {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ── Drawable 工厂（与 SubSettingsActivity 一致的视觉效果） ───────────

    private fun getClickableBackground(): Drawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(colorClickable)
                    cornerRadius = 12 * density
                }
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    setColor(colorCard)
                    cornerRadius = 12 * density
                    setStroke(1, colorStroke)
                }
            )
        }
    }

    private fun getPlainBackground(): Drawable {
        return GradientDrawable().apply {
            setColor(colorCard)
            cornerRadius = 12 * density
            setStroke(1, colorStroke)
        }
    }

    private fun getIconTileBackground(): Drawable {
        return GradientDrawable().apply {
            setColor(colorClickable)
            cornerRadius = 10 * density
        }
    }

    /** 默认（绿色）badge 背景，与 SubSettingsActivity 完全一致。 */
    private fun getBadgeBackground(): Drawable {
        return GradientDrawable().apply {
            setColor(badgeDefault.second)
            cornerRadius = 6 * density
            setStroke(1, badgeDefault.first)
        }
    }

    /** 通用 badge 背景：暗底填充 + 强调色描边。 */
    private fun getBadgeBackground(darkBg: Int, accentColor: Int): Drawable {
        return GradientDrawable().apply {
            setColor(darkBg)
            cornerRadius = 6 * density
            setStroke(1, accentColor)
        }
    }
}
