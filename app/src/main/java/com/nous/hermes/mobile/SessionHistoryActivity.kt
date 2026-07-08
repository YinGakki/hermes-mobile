package com.nous.hermes.mobile

import android.content.res.ColorStateList
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 会话历史页面 — 浏览 Hermes Agent 的历史会话。
 *
 * 直接读取 Hermes 的本地 SQLite 数据库（`~/.hermes/state.db`，在 Android 端
 * 映射到 `filesDir/home/.hermes/state.db`），**不再依赖 hermes-web-ui 的 REST API**。
 *
 * 由于数据库 schema 可能随版本变化，本类在运行时通过 `sqlite_master` 发现表结构，
 * 按表名/列名关键词推断会话表与消息表，并完成字段映射：
 *   - 会话表：sessions / conversation / thread / chat（名称不含 message）
 *   - 消息表：messages / chat_messages / conversation_messages
 *
 * 若会话表自带 `message_count` 列则直接读取；否则在存在消息表 + 会话外键时，
 * 通过 `LEFT JOIN ... COUNT(*)` 计算消息数；都没有则为 0。
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private val configManager by lazy { HermesConfigManager(this) }

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

    /** 数据库表的列信息（来自 PRAGMA table_info）。 */
    private data class ColumnInfo(
        val name: String,
        val isPk: Boolean,
        val pkOrder: Int,
    )

    /** 会话表 schema 发现结果。 */
    private data class SessionTableInfo(
        val name: String,
        val colId: String?,
        val colTitle: String?,
        val colCreatedAt: String?,
        val colProvider: String?,
        val colModel: String?,
        val colMessageCount: String?,
    )

    /** 消息表 schema 发现结果。 */
    private data class MessageTableInfo(
        val name: String,
        val colId: String?,
        val colRole: String?,
        val colContent: String?,
        val colTimestamp: String?,
        val colSessionFk: String?,
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
            var db: SQLiteDatabase? = null
            try {
                val dbFile = stateDbFile()
                if (!dbFile.exists()) {
                    handler.post {
                        progressDialog.dismiss()
                        Toast.makeText(
                            this,
                            "数据库不存在（Hermes 可能未安装或尚未产生会话）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }
                db = openStateDb(dbFile.absolutePath)

                val tables = listTables(db)
                val sessionInfo = discoverSessionTable(db, tables)
                val messageInfo = discoverMessageTable(db, tables, sessionInfo)
                    ?: throw IllegalStateException("未找到消息表（messages/chat_messages 等）")
                val fk = messageInfo.colSessionFk
                    ?: throw IllegalStateException(
                        "消息表 '${messageInfo.name}' 缺少会话外键列"
                    )

                val messages = queryMessages(db, messageInfo, fk, session.id)
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
            } finally {
                db?.close()
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

    // ── 数据加载（直接读取 SQLite） ──────────────────────────────────────

    /** 打开 state.db → 发现 schema → 查询会话列表 → 渲染卡片。 */
    private fun loadSessions() {
        showLoading()
        Thread {
            var db: SQLiteDatabase? = null
            try {
                val dbFile = stateDbFile()
                if (!dbFile.exists()) {
                    handler.post {
                        showError("数据库不存在（Hermes 可能未安装或尚未产生会话）")
                    }
                    return@Thread
                }
                db = openStateDb(dbFile.absolutePath)

                val tables = listTables(db)
                if (tables.isEmpty()) {
                    handler.post { showError("数据库中未找到任何数据表") }
                    return@Thread
                }

                val sessionInfo = discoverSessionTable(db, tables)
                val messageInfo = discoverMessageTable(db, tables, sessionInfo)

                val parsed = querySessions(db, sessionInfo, messageInfo)
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
            } finally {
                db?.close()
            }
        }.start()
    }

    // ── SQLite 工具 ─────────────────────────────────────────────────────

    /** state.db 的 host 路径：filesDir/home/.hermes/state.db。 */
    private fun stateDbFile(): File = configManager.getStateDbPath()

    /**
     * 以只读方式打开 state.db，并设置忙等待超时（容忍 Hermes daemon 并发写入）。
     * 仅在后台线程调用。
     */
    private fun openStateDb(path: String): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // busy_timeout 是连接级设置，不写文件，只读连接也可设置
            db.execSQL("PRAGMA busy_timeout = 3000")
        } catch (_: Exception) {
            // 忽略 — 不影响只读查询
        }
        return db
    }

    /** 列出数据库中所有用户表（排除 sqlite_/android_metadata/room 内部表）。 */
    private fun listTables(db: SQLiteDatabase): List<String> {
        val tables = ArrayList<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_metadata' " +
                "AND name NOT LIKE 'room_master_%'",
            null
        ).use { c ->
            while (c.moveToNext()) {
                tables.add(c.getString(0))
            }
        }
        return tables
    }

    /** 通过 PRAGMA table_info 获取表的列信息（含主键顺序）。 */
    private fun getColumnInfo(db: SQLiteDatabase, table: String): List<ColumnInfo> {
        val list = ArrayList<ColumnInfo>()
        try {
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                val pkIdx = c.getColumnIndexOrThrow("pk")
                while (c.moveToNext()) {
                    val pk = c.getInt(pkIdx)
                    list.add(ColumnInfo(name = c.getString(nameIdx), isPk = pk > 0, pkOrder = pk))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getColumnInfo($table) failed", e)
        }
        return list
    }

    /**
     * 在列中查找匹配的列名。
     * @param preferPk 是否优先取主键列
     * @param exact    精确匹配关键词（按优先级，大小写不敏感）
     * @param contains 包含匹配关键词（精确匹配全部失败后再尝试，按优先级）
     */
    private fun matchColumn(
        cols: List<ColumnInfo>,
        preferPk: Boolean = false,
        exact: List<String> = emptyList(),
        contains: List<String> = emptyList()
    ): String? {
        if (preferPk) {
            cols.filter { it.isPk }.minByOrNull { it.pkOrder }?.let { return it.name }
        }
        val lower = cols.map { it.name.lowercase() }
        for (kw in exact) {
            val idx = lower.indexOf(kw.lowercase())
            if (idx >= 0) return cols[idx].name
        }
        for (kw in contains) {
            val lkw = kw.lowercase()
            val idx = lower.indexOfFirst { it.contains(lkw) }
            if (idx >= 0) return cols[idx].name
        }
        return null
    }

    /** 查找 id/主键列：主键 → 精确名 → 安全包含名（避免 "id" 误命中 modified 等）。 */
    private fun findIdColumn(cols: List<ColumnInfo>): String? {
        cols.filter { it.isPk }.minByOrNull { it.pkOrder }?.let { return it.name }
        val lower = cols.map { it.name.lowercase() }
        val exactIds = listOf(
            "id", "session_id", "sessionid", "conversation_id", "conversationid",
            "thread_id", "threadid", "message_id", "messageid",
            "uuid", "uid", "mid", "sid", "oid", "guid"
        )
        for (kw in exactIds) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) return cols[idx].name
        }
        val containsIds = listOf(
            "session_id", "sessionid", "conversation_id", "conversationid",
            "thread_id", "threadid", "message_id", "messageid",
            "uuid", "uid", "guid"
        )
        for (kw in containsIds) {
            val idx = lower.indexOfFirst { it.contains(kw) }
            if (idx >= 0) return cols[idx].name
        }
        return null
    }

    /** 按优先级挑选会话表（sessions / conversation / thread / chat，不含 message）。 */
    private fun pickSessionTable(tables: List<String>): String? {
        val lower = tables.map { it.lowercase() }
        for (name in listOf(
            "sessions", "session", "conversations", "conversation",
            "threads", "thread", "chats", "chat"
        )) {
            val idx = lower.indexOf(name)
            if (idx >= 0) return tables[idx]
        }
        // 回退：名称含 session/conversation/thread/chat 且不含 message/msg
        for (i in lower.indices) {
            val t = lower[i]
            if ((t.contains("session") || t.contains("conversation") ||
                    t.contains("thread") || t.contains("chat")) &&
                !t.contains("message") && !t.contains("msg")
            ) {
                return tables[i]
            }
        }
        return null
    }

    /** 按优先级挑选消息表（messages / chat_messages / conversation_messages ...）。 */
    private fun pickMessageTable(tables: List<String>): String? {
        val lower = tables.map { it.lowercase() }
        for (name in listOf(
            "messages", "message", "chat_messages", "chatmessages",
            "conversation_messages", "conversationmessages",
            "session_messages", "sessionmessages", "msgs", "msg"
        )) {
            val idx = lower.indexOf(name)
            if (idx >= 0) return tables[idx]
        }
        for (i in lower.indices) {
            if (lower[i].contains("message") || lower[i].contains("msg")) {
                return tables[i]
            }
        }
        return null
    }

    /** 发现会话表并映射字段。找不到则抛出 [IllegalStateException]。 */
    private fun discoverSessionTable(
        db: SQLiteDatabase,
        tables: List<String>
    ): SessionTableInfo {
        val name = pickSessionTable(tables)
            ?: throw IllegalStateException(
                "未找到会话表（sessions/conversation/thread/chat）"
            )
        val cols = getColumnInfo(db, name)
        if (cols.isEmpty()) throw IllegalStateException("会话表 '$name' 无可读列")
        return SessionTableInfo(
            name = name,
            colId = findIdColumn(cols),
            colTitle = matchColumn(
                cols,
                exact = listOf("title", "name", "summary", "subject", "label", "heading", "topic"),
                contains = listOf("title", "summary", "subject", "heading", "label", "topic")
            ),
            colCreatedAt = matchColumn(
                cols,
                exact = listOf(
                    "created_at", "created", "create_time", "createdtime",
                    "timestamp", "ts", "time", "date", "start_time", "starttime", "begintime"
                ),
                contains = listOf("created", "timestamp", "create_time", "start_time", "starttime")
            ),
            colProvider = matchColumn(
                cols,
                exact = listOf("provider", "backend", "engine", "vendor", "source"),
                contains = listOf("provider", "backend", "engine", "vendor")
            ),
            colModel = matchColumn(
                cols,
                exact = listOf("model", "model_name", "modelname", "model_id"),
                contains = listOf("model")
            ),
            colMessageCount = matchColumn(
                cols,
                exact = listOf(
                    "message_count", "messagecount", "msg_count", "msgcount",
                    "num_messages", "nummessages", "messages_count", "messagescount"
                ),
                contains = listOf(
                    "message_count", "msg_count", "num_messages", "messagecount", "msgcount"
                )
            ),
        )
    }

    /** 发现消息表并映射字段。找不到返回 null。 */
    private fun discoverMessageTable(
        db: SQLiteDatabase,
        tables: List<String>,
        sessionInfo: SessionTableInfo
    ): MessageTableInfo? {
        val name = pickMessageTable(tables) ?: return null
        val cols = getColumnInfo(db, name)
        if (cols.isEmpty()) return null
        return MessageTableInfo(
            name = name,
            colId = findIdColumn(cols),
            colRole = matchColumn(
                cols,
                exact = listOf("role", "sender", "author", "actor", "speaker", "from", "source", "origin"),
                contains = listOf("role", "sender", "author", "actor", "speaker")
            ),
            colContent = matchColumn(
                cols,
                exact = listOf("content", "text", "body", "message", "data", "payload", "value", "content_text", "msg"),
                contains = listOf("content", "payload", "body", "data")
            ),
            colTimestamp = matchColumn(
                cols,
                exact = listOf(
                    "timestamp", "created_at", "created", "create_time",
                    "time", "date", "ts", "sent_at", "sent_time"
                ),
                contains = listOf("timestamp", "created", "sent", "create_time")
            ),
            colSessionFk = findSessionFk(cols, sessionInfo),
        )
    }

    /** 在消息表中查找会话外键列。 */
    private fun findSessionFk(cols: List<ColumnInfo>, sessionInfo: SessionTableInfo): String? {
        val lower = cols.map { it.name.lowercase() }
        val fkNames = listOf(
            "session_id", "sessionid", "conversation_id", "conversationid",
            "thread_id", "threadid", "chat_id", "chatid",
            "session", "conversation", "thread", "chat"
        )
        // 1. 精确匹配
        for (kw in fkNames) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) return cols[idx].name
        }
        // 2. 包含匹配
        for (kw in fkNames) {
            val idx = lower.indexOfFirst { it.contains(kw) }
            if (idx >= 0) return cols[idx].name
        }
        // 3. 与会话表 id 同名（且非通用 "id"，避免误取消息自身主键）
        sessionInfo.colId?.let { sid ->
            if (sid.lowercase() != "id") {
                val idx = lower.indexOf(sid.lowercase())
                if (idx >= 0) return cols[idx].name
            }
        }
        // 4. 回退：非主键且以 "_id" 结尾的列
        cols.firstOrNull { !it.isPk && it.name.lowercase().endsWith("_id") }?.let { return it.name }
        return null
    }

    /** 构建会话表 SELECT 的列片段（带别名，避免 JOIN 时列名歧义）。 */
    private fun buildSessionSelectColumns(s: SessionTableInfo): String {
        val seen = LinkedHashSet<String>()
        val cols = mutableListOf<String>()
        fun add(col: String?) {
            if (col != null && seen.add(col.lowercase())) {
                cols.add("s.\"$col\" AS \"$col\"")
            }
        }
        add(s.colId)
        add(s.colTitle)
        add(s.colCreatedAt)
        add(s.colProvider)
        add(s.colModel)
        return if (cols.isEmpty()) "NULL" else cols.joinToString(", ")
    }

    /** 查询全部会话。消息数优先取自带列，否则 JOIN COUNT，再否则 0。 */
    private fun querySessions(
        db: SQLiteDatabase,
        s: SessionTableInfo,
        m: MessageTableInfo?
    ): ArrayList<Session> {
        val parsed = ArrayList<Session>()
        val idCol = s.colId
            ?: throw IllegalStateException("会话表 '${s.name}' 缺少 id/主键列")

        val sql: String = when {
            // 自带消息数列
            s.colMessageCount != null -> {
                val cols = buildSessionSelectColumns(s)
                "SELECT $cols, s.\"${s.colMessageCount}\" AS \"__msg_count\" " +
                    "FROM \"${s.name}\" s"
            }
            // 通过消息表 JOIN 计算消息数
            m != null && m.colSessionFk != null -> {
                val cols = buildSessionSelectColumns(s)
                val fk = m.colSessionFk
                "SELECT $cols, COUNT(m.\"$fk\") AS \"__msg_count\" " +
                    "FROM \"${s.name}\" s " +
                    "LEFT JOIN \"${m.name}\" m ON m.\"$fk\" = s.\"$idCol\" " +
                    "GROUP BY s.\"$idCol\""
            }
            // 无法计算消息数
            else -> {
                val cols = buildSessionSelectColumns(s)
                "SELECT $cols, 0 AS \"__msg_count\" FROM \"${s.name}\" s"
            }
        }

        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                parsed.add(
                    Session(
                        id = readString(c, idCol) ?: "",
                        title = s.colTitle?.let { readString(c, it) } ?: "",
                        createdAt = s.colCreatedAt?.let { readString(c, it) } ?: "",
                        messageCount = readCount(c, "__msg_count"),
                        provider = s.colProvider?.let { readString(c, it) } ?: "",
                        model = s.colModel?.let { readString(c, it) } ?: "",
                    )
                )
            }
        }
        return parsed
    }

    /** 按会话 id 查询消息列表（按时间升序）。 */
    private fun queryMessages(
        db: SQLiteDatabase,
        m: MessageTableInfo,
        fkCol: String,
        sessionId: String
    ): ArrayList<Message> {
        val parsed = ArrayList<Message>()
        val selectParts = mutableListOf<String>()
        if (m.colRole != null) selectParts.add("\"${m.colRole}\" AS \"__role\"")
        if (m.colContent != null) selectParts.add("\"${m.colContent}\" AS \"__content\"")
        if (m.colTimestamp != null) selectParts.add("\"${m.colTimestamp}\" AS \"__ts\"")
        if (selectParts.isEmpty()) return parsed

        val orderBy = if (m.colTimestamp != null) " ORDER BY \"__ts\" ASC" else ""
        val sql = "SELECT ${selectParts.joinToString(", ")} FROM \"${m.name}\" " +
            "WHERE \"$fkCol\" = ?$orderBy"
        db.rawQuery(sql, arrayOf(sessionId)).use { c ->
            while (c.moveToNext()) {
                val role = readString(c, "__role") ?: "unknown"
                val content = readString(c, "__content") ?: ""
                val ts = readString(c, "__ts") ?: ""
                parsed.add(
                    Message(
                        role = role.ifEmpty { "unknown" },
                        content = extractContentString(content),
                        timestamp = ts,
                    )
                )
            }
        }
        return parsed
    }

    /** 读取文本列（对整数/浮点存储也兼容，返回其字符串形式）。 */
    private fun readString(c: Cursor, colName: String): String? {
        val idx = c.getColumnIndex(colName)
        if (idx < 0 || c.isNull(idx)) return null
        return c.getString(idx)
    }

    /** 读取 __msg_count 列，兼容整数/浮点/字符串数字存储。 */
    private fun readCount(c: Cursor, colName: String): Int {
        val idx = c.getColumnIndex(colName)
        if (idx < 0 || c.isNull(idx)) return 0
        return when (c.getType(idx)) {
            Cursor.FIELD_TYPE_INTEGER -> c.getInt(idx)
            Cursor.FIELD_TYPE_FLOAT -> c.getInt(idx)
            Cursor.FIELD_TYPE_STRING -> c.getString(idx).trim().toIntOrNull() ?: 0
            else -> 0
        }
    }

    // ── 内容解析 ─────────────────────────────────────────────────────────

    /**
     * 从消息 JSON 提取内容文本。
     * content 可能是字符串，也可能是内容块数组（Anthropic / OpenAI 格式）。
     *
     * 保持不变：用于解析消息对象中 content 字段为 JSON 对象/数组的情形。
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

    /**
     * 从数据库中读取的原始 content 字段提取文本。
     * content 可能是纯文本，也可能是 JSON：
     *   - JSON 数组（内容块，如 OpenAI/Anthropic 格式）
     *   - JSON 对象（可能含 content/text 字段，或单个内容块对象）
     *   - 纯文本
     * 解析失败时原样返回。
     */
    private fun extractContentString(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val type = item.optString("type", "")
                        if (type == "text" || item.has("text")) {
                            val t = item.optString("text", "")
                            if (t.isNotEmpty()) {
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append(t)
                            }
                        } else if (type == "tool_use" || type == "tool_result") {
                            val name = item.optString("name", type)
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append("[$name]")
                        }
                    }
                    sb.toString().ifEmpty { raw }
                }
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("content") -> extractContent(obj)
                        obj.has("text") -> obj.optString("text", "")
                        obj.has("body") -> obj.optString("body", "")
                        else -> raw
                    }
                }
                else -> raw
            }
        } catch (e: Exception) {
            raw
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
