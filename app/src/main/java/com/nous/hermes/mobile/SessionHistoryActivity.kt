package com.nous.hermes.mobile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 会话历史页面 — 浏览 Hermes Agent 的历史会话。
 *
 * 通过 hermes-web-ui（端口 8648）提供的 REST API 完成：
 *   - GET /api/hermes/sessions                 获取会话列表
 *   - GET /api/hermes/sessions/{id}/messages   获取某会话的消息详情
 *
 * UI 完全用代码构建（无 XML layout），视觉风格与 [SubSettingsActivity] /
 * [ModelManagementActivity] 一致：深色背景、圆角卡片、StateListDrawable 点击反馈、
 * 💬 文字图标方块。
 *
 * 交互：
 *   - 点击会话卡片 → 弹出消息详情对话框（user/assistant 以颜色区分）
 */
class SessionHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SessionHistoryActivity"
        private const val BASE_URL = "http://127.0.0.1:8648"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // ── 颜色常量（与 SubSettingsActivity / ModelManagementActivity 一致） ──
    private val colorBg = 0xFF020617.toInt()
    private val colorCard = 0xFF0f172a.toInt()
    private val colorClickable = 0xFF1e293b.toInt()
    private val colorStroke = 0xFF334155.toInt()
    private val colorTitle = 0xFFe2e8f0.toInt()
    private val colorSubtitle = 0xFF94a3b8.toInt()
    private val colorDim = 0xFF64748b.toInt()
    private val colorAccent = 0xFF818cf8.toInt()   // user 消息色
    private val colorCyan = 0xFF22d3ee.toInt()      // assistant 消息色

    // ── 数据模型 ─────────────────────────────────────────────────────────
    /** 一条历史会话。 */
    private data class Session(
        val id: String,
        val title: String,
        val createdAt: String,
        val messageCount: Int,
        val provider: String,
        val model: String,
    )

    /** 一条消息。 */
    private data class Message(
        val role: String,
        val content: String,
        val timestamp: String,
    )

    private var sessions: List<Session> = emptyList()

    // ── UI 引用 ──────────────────────────────────────────────────────────
    private lateinit var contentScrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var emptyView: View

    // ── 日期格式化 ───────────────────────────────────────────────────────
    private val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadSessions()
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
            text = "会话历史"
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

        // --- 内容区（FrameLayout 叠加：ScrollView / 加载 / 错误 / 空状态） ---
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

        // 空状态视图
        emptyView = makeEmptyView()
        body.addView(emptyView, FrameLayout.LayoutParams(
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
            addView(ProgressBar(this@SessionHistoryActivity).apply {
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
            addView(TextView(this@SessionHistoryActivity).apply {
                text = "加载失败"
                setTextColor(colorTitle)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            errorText = TextView(this@SessionHistoryActivity).apply {
                setTextColor(colorSubtitle)
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * density).toInt() }
            }
            addView(errorText)
            addView(TextView(this@SessionHistoryActivity).apply {
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
                setOnClickListener { loadSessions() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (20 * density).toInt() }
            })
        }
    }

    /** 空状态视图：居中提示"暂无会话历史"。 */
    private fun makeEmptyView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colorBg)
            setPadding(
                (40 * density).toInt(), (40 * density).toInt(),
                (40 * density).toInt(), (40 * density).toInt()
            )
            addView(TextView(this@SessionHistoryActivity).apply {
                text = "💬"
                textSize = 40f
                gravity = Gravity.CENTER
                alpha = 0.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (16 * density).toInt() }
            })
            addView(TextView(this@SessionHistoryActivity).apply {
                text = "暂无会话历史"
                setTextColor(colorSubtitle)
                textSize = 15f
                gravity = Gravity.CENTER
            })
        }
    }

    /** 渲染会话卡片列表。 */
    private fun renderCards() {
        contentContainer.removeAllViews()

        if (sessions.isEmpty()) {
            showEmpty()
            return
        }

        // section 标题
        contentContainer.addView(makeSectionLabel("会话列表（${sessions.size}）"))

        for (session in sessions) {
            contentContainer.addView(makeSessionCard(session))
        }
    }

    /** 会话卡片：💬 图标方块 + 标题/时间/消息数模型 + › 箭头。 */
    private fun makeSessionCard(session: Session): LinearLayout {
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

            // 左侧：💬 图标方块
            val iconTile = LinearLayout(this@SessionHistoryActivity).apply {
                gravity = Gravity.CENTER
                background = getIconTileBackground()
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            iconTile.addView(TextView(this@SessionHistoryActivity).apply {
                text = "💬"
                textSize = 20f
                gravity = Gravity.CENTER
            })
            addView(iconTile)

            // 中间文本列
            val textCol = LinearLayout(this@SessionHistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (14 * density).toInt() }
            }

            // 第一行：标题
            textCol.addView(TextView(this@SessionHistoryActivity).apply {
                text = session.title.ifEmpty { "（未命名会话）" }
                setTextColor(colorTitle)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })

            // 第二行：时间
            textCol.addView(TextView(this@SessionHistoryActivity).apply {
                text = formatTime(session.createdAt)
                setTextColor(colorSubtitle)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (3 * density).toInt() }
            })

            // 第三行：消息数 + 模型
            val metaText = buildString {
                append("${session.messageCount} 条消息")
                if (session.model.isNotEmpty()) {
                    append(" · ${session.model}")
                } else if (session.provider.isNotEmpty()) {
                    append(" · ${session.provider}")
                }
            }
            textCol.addView(TextView(this@SessionHistoryActivity).apply {
                text = metaText
                setTextColor(colorSubtitle)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
            })
            addView(textCol)

            // 右侧：› 箭头
            addView(TextView(this@SessionHistoryActivity).apply {
                text = "›"
                setTextColor(colorDim)
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(), (20 * density).toInt()
                ).apply { marginStart = (8 * density).toInt() }
            })

            // 交互：点击查看消息
            setOnClickListener { showMessagesDialog(session) }
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
        emptyView.visibility = View.GONE
        contentScrollView.visibility = View.GONE
    }

    private fun showContent() {
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        emptyView.visibility = View.GONE
        contentScrollView.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        contentScrollView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        contentScrollView.visibility = View.GONE
        errorText.text = message
    }

    // ── 对话框 ───────────────────────────────────────────────────────────

    /** 点击会话卡片：加载消息并弹出消息详情对话框。 */
    private fun showMessagesDialog(session: Session) {
        val progressDialog = showProgressDialog("加载消息中…")
        Thread {
            try {
                val resp = httpRequest(
                    "GET",
                    "/api/hermes/sessions/" +
                        URLEncoder.encode(session.id, "UTF-8") + "/messages"
                )
                val arr = JSONArray(resp)
                val messages = ArrayList<Message>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    messages.add(
                        Message(
                            role = m.optString("role", "unknown"),
                            content = extractContent(m),
                            timestamp = m.optString("timestamp", ""),
                        )
                    )
                }
                handler.post {
                    progressDialog.dismiss()
                    if (messages.isEmpty()) {
                        Toast.makeText(this, "该会话暂无消息", Toast.LENGTH_SHORT).show()
                    } else {
                        showMessageListDialog(session, messages)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessages failed", e)
                handler.post {
                    progressDialog.dismiss()
                    Toast.makeText(this, "加载消息失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 弹出消息列表对话框。 */
    private fun showMessageListDialog(session: Session, messages: List<Message>) {
        val messagesView = makeMessagesView(messages)
        MaterialAlertDialogBuilder(this)
            .setTitle(session.title.ifEmpty { "会话消息" })
            .setView(messagesView)
            .setNegativeButton("关闭", null)
            .setCancelable(true)
            .show()
    }

    /** 构建消息列表视图：高度上限 60% 屏幕高，超出滚动。 */
    private fun makeMessagesView(messages: List<Message>): View {
        // 高度受限的 ScrollView — 内容短时自适应，长时内部滚动
        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.6).toInt()
                val h = View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, h)
            }
        }
        scrollView.setBackgroundColor(colorBg)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (4 * density).toInt(), (4 * density).toInt(),
                (4 * density).toInt(), (4 * density).toInt()
            )
        }

        for (msg in messages) {
            container.addView(makeMessageItem(msg))
        }

        scrollView.addView(container)
        return scrollView
    }

    /** 单条消息视图：角色标签（彩色） + 内容。 */
    private fun makeMessageItem(msg: Message): View {
        val isUser = msg.role == "user"
        val roleColor = if (isUser) colorAccent else colorCyan
        val roleLabel = when (msg.role) {
            "user" -> "用户"
            "assistant" -> "助手"
            "system" -> "系统"
            else -> msg.role
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getPlainBackground()
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }

            // 角色标签行
            val labelRow = LinearLayout(this@SessionHistoryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 彩色小圆点
            labelRow.addView(View(this@SessionHistoryActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(roleColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    (8 * density).toInt(), (8 * density).toInt()
                ).apply { marginEnd = (6 * density).toInt() }
            })
            labelRow.addView(TextView(this@SessionHistoryActivity).apply {
                text = roleLabel
                setTextColor(roleColor)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            })
            // 时间戳（右侧）
            if (msg.timestamp.isNotEmpty()) {
                labelRow.addView(TextView(this@SessionHistoryActivity).apply {
                    text = formatTime(msg.timestamp)
                    setTextColor(colorDim)
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = (8 * density).toInt() }
                    gravity = Gravity.END
                })
            }
            addView(labelRow)

            // 消息内容
            addView(TextView(this@SessionHistoryActivity).apply {
                text = msg.content.ifEmpty { "（空消息）" }
                setTextColor(colorTitle)
                textSize = 13f
                lineHeight = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            })
        }
    }

    /** 非阻塞进度对话框（用于加载消息期间）。 */
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
        return MaterialAlertDialogBuilder(this)
            .setView(container)
            .setCancelable(false)
            .show()
    }

    // ── API 调用 ─────────────────────────────────────────────────────────

    /** GET /api/hermes/sessions → 解析并渲染卡片。 */
    private fun loadSessions() {
        showLoading()
        Thread {
            try {
                val resp = httpRequest("GET", "/api/hermes/sessions")
                val arr = JSONArray(resp)
                val parsed = ArrayList<Session>()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    parsed.add(
                        Session(
                            id = s.optString("id", ""),
                            title = s.optString("title", ""),
                            createdAt = s.optString("created_at", ""),
                            messageCount = s.optInt("message_count", 0),
                            provider = s.optString("provider", ""),
                            model = s.optString("model", ""),
                        )
                    )
                }
                // 按创建时间倒序（最新在前）
                parsed.sortByDescending { it.createdAt }
                handler.post {
                    sessions = parsed
                    renderCards()
                    if (parsed.isEmpty()) showEmpty() else showContent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadSessions failed", e)
                handler.post { showError(e.message ?: "未知错误") }
            }
        }.start()
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

    // ── 内容解析 ─────────────────────────────────────────────────────────

    /**
     * 从消息 JSON 提取内容文本。
     * content 可能是字符串，也可能是内容块数组（Anthropic / OpenAI 格式）。
     */
    private fun extractContent(obj: JSONObject): String {
        val content = obj.opt("content") ?: return ""
        return when (content) {
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val type = item.optString("type", "")
                    if (type == "text" || item.has("text")) {
                        val t = item.optString("text", "")
                        if (t.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(t)
                        }
                    } else if (type == "tool_use" || type == "tool_result") {
                        // 工具调用/结果 — 简要标注
                        val name = item.optString("name", type)
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append("[$name]")
                    }
                }
                sb.toString()
            }
            else -> content.toString()
        }
    }

    // ── 时间格式化 ───────────────────────────────────────────────────────

    /**
     * 将 ISO 8601 时间字符串格式化为 "yyyy-MM-dd HH:mm"。
     * 兼容带/不带时区的形式；解析失败时返回原字符串。
     */
    private fun formatTime(iso: String): String {
        if (iso.isBlank()) return "—"
        return try {
            // 优先尝试带时区的解析（如 2026-01-01T12:00:00Z 或 +08:00）
            val zdt = ZonedDateTime.parse(iso)
            zdt.withZoneSameInstant(ZoneId.systemDefault())
                .format(displayFormatter)
        } catch (e: DateTimeParseException) {
            try {
                // 回退：不带时区的本地时间（如 2026-01-01T12:00:00）
                LocalDateTime.parse(iso).format(displayFormatter)
            } catch (e2: DateTimeParseException) {
                try {
                    // 再回退： epoch 毫秒
                    Instant.ofEpochMilli(iso.toLong())
                        .atZone(ZoneId.systemDefault())
                        .format(displayFormatter)
                } catch (e3: Exception) {
                    iso
                }
            }
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
}
