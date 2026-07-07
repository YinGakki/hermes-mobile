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
import android.widget.HorizontalScrollView
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
 * 定时任务页面 — 管理 Hermes Agent 的 cron 定时任务。
 *
 * 通过 hermes-web-ui（端口 8648）提供的 REST API 完成：
 *   - GET    /api/hermes/jobs            获取任务列表
 *   - POST   /api/hermes/jobs            创建任务
 *   - POST   /api/hermes/jobs/{id}/pause 暂停任务
 *   - POST   /api/hermes/jobs/{id}/run   立即执行任务
 *   - DELETE /api/hermes/jobs/{id}       删除任务
 *
 * UI 完全用代码构建（无 XML layout），视觉风格与 [SubSettingsActivity] /
 * [SessionHistoryActivity] 一致：深色背景、圆角卡片、StateListDrawable 点击反馈、
 * ⏰ 文字图标方块。
 *
 * 交互：
 *   - 点击「创建任务」→ 弹出表单对话框（名称 / cron 表达式 / 提示词）
 *   - 点击任务卡片 → 弹出操作对话框（立即执行 / 暂停·恢复 / 删除）
 */
class CronJobsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CronJobsActivity"
        private const val BASE_URL = "http://127.0.0.1:8648"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // ── 颜色常量（与 SessionHistoryActivity 完全一致） ───────────────────
    private val colorBg = 0xFF020617.toInt()
    private val colorCard = 0xFF0f172a.toInt()
    private val colorClickable = 0xFF1e293b.toInt()
    private val colorStroke = 0xFF334155.toInt()
    private val colorTitle = 0xFFe2e8f0.toInt()
    private val colorSubtitle = 0xFF94a3b8.toInt()
    private val colorDim = 0xFF64748b.toInt()
    private val colorAccent = 0xFF818cf8.toInt()
    private val colorCyan = 0xFF22d3ee.toInt()

    // 状态 badge 配色（文字色 + 暗底）
    private val badgeEnabled = 0xFF10b981.toInt() to 0xFF064e3b.toInt()    // 绿 启用
    private val badgePaused = 0xFF94a3b8.toInt() to 0xFF1e293b.toInt()     // 灰 暂停
    private val badgeRunning = 0xFFf59e0b.toInt() to 0xFF451a03.toInt()    // 橙 运行中
    private val badgeError = 0xFFef4444.toInt() to 0xFF450a0a.toInt()      // 红 失败

    // ── 数据模型 ─────────────────────────────────────────────────────────
    /** 一个定时任务。 */
    private data class Job(
        val id: String,
        val name: String,
        val schedule: String,
        val enabled: Boolean,
        val lastRun: String,
        val nextRun: String,
        val status: String,
    )

    private var jobs: List<Job> = emptyList()

    // ── UI 引用 ──────────────────────────────────────────────────────────
    private lateinit var contentScrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    // ── 日期格式化 ───────────────────────────────────────────────────────
    private val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // ── cron 常用模板 ────────────────────────────────────────────────────
    private val cronTemplates = listOf(
        "每分钟" to "* * * * *",
        "每小时" to "0 * * * *",
        "每天 0点" to "0 0 * * *",
        "每天 9点" to "0 9 * * *",
        "每周一" to "0 9 * * 1",
        "每月1号" to "0 9 1 * *",
    )

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadJobs()
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
            text = "定时任务"
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
            addView(ProgressBar(this@CronJobsActivity).apply {
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
            addView(TextView(this@CronJobsActivity).apply {
                text = "加载失败"
                setTextColor(colorTitle)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            errorText = TextView(this@CronJobsActivity).apply {
                setTextColor(colorSubtitle)
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * density).toInt() }
            }
            addView(errorText)
            addView(TextView(this@CronJobsActivity).apply {
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
                setOnClickListener { loadJobs() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (20 * density).toInt() }
            })
        }
    }

    /** 渲染卡片列表：创建按钮 + section 标题 + 任务卡片 / 空状态。 */
    private fun renderCards() {
        contentContainer.removeAllViews()

        // 顶部「创建任务」按钮
        contentContainer.addView(makeCreateJobButton())

        // section 标题
        contentContainer.addView(makeSectionLabel("定时任务（${jobs.size}）"))

        if (jobs.isEmpty()) {
            contentContainer.addView(makeEmptyStateView())
            return
        }

        for (job in jobs) {
            contentContainer.addView(makeJobCard(job))
        }
    }

    /** 空状态提示。 */
    private fun makeEmptyStateView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(), (48 * density).toInt(),
                (20 * density).toInt(), (48 * density).toInt()
            )
            addView(TextView(this@CronJobsActivity).apply {
                text = "⏰"
                textSize = 40f
                gravity = Gravity.CENTER
                alpha = 0.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (16 * density).toInt() }
            })
            addView(TextView(this@CronJobsActivity).apply {
                text = "暂无定时任务"
                setTextColor(colorSubtitle)
                textSize = 15f
                gravity = Gravity.CENTER
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** 「创建任务」按钮卡片（带 + 图标）。 */
    private fun makeCreateJobButton(): LinearLayout {
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
            setOnClickListener { showCreateJobDialog() }

            // 图标方块
            val iconTile = LinearLayout(this@CronJobsActivity).apply {
                gravity = Gravity.CENTER
                background = getIconTileBackground()
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            iconTile.addView(TextView(this@CronJobsActivity).apply {
                text = "+"
                setTextColor(colorAccent)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(iconTile)

            // 文本
            val textCol = LinearLayout(this@CronJobsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (16 * density).toInt() }
            }
            textCol.addView(TextView(this@CronJobsActivity).apply {
                text = "创建任务"
                setTextColor(colorTitle)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(this@CronJobsActivity).apply {
                text = "添加一个新的定时任务"
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

    /** 任务卡片：⏰ 图标方块 + 名称/badge + cron + 下次运行 + › 箭头。 */
    private fun makeJobCard(job: Job): LinearLayout {
        val (badgeText, badgeColor, badgeBg) = resolveStatusBadge(job)

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

            // 左侧：⏰ 图标方块
            val iconTile = LinearLayout(this@CronJobsActivity).apply {
                gravity = Gravity.CENTER
                background = getIconTileBackground()
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            iconTile.addView(TextView(this@CronJobsActivity).apply {
                text = "⏰"
                textSize = 20f
                gravity = Gravity.CENTER
            })
            addView(iconTile)

            // 中间文本列
            val textCol = LinearLayout(this@CronJobsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (14 * density).toInt() }
            }

            // 第一行：名称 + badge
            val row1 = LinearLayout(this@CronJobsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(this@CronJobsActivity).apply {
                text = job.name.ifEmpty { "（未命名任务）" }
                setTextColor(colorTitle)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row1.addView(makeBadge(badgeText, badgeColor, badgeBg))
            textCol.addView(row1)

            // 第二行：cron 表达式（mono 灰色）
            if (job.schedule.isNotEmpty()) {
                textCol.addView(TextView(this@CronJobsActivity).apply {
                    text = job.schedule
                    setTextColor(colorSubtitle)
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * density).toInt() }
                })
            }

            // 第三行：下次运行时间
            val nextLabel = if (job.enabled) {
                "下次运行：${formatTime(job.nextRun)}"
            } else {
                "已暂停"
            }
            textCol.addView(TextView(this@CronJobsActivity).apply {
                text = nextLabel
                setTextColor(colorDim)
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
            addView(TextView(this@CronJobsActivity).apply {
                text = "›"
                setTextColor(colorDim)
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(), (20 * density).toInt()
                ).apply { marginStart = (8 * density).toInt() }
            })

            // 交互：点击弹出操作菜单
            setOnClickListener { showJobActionsDialog(job) }
        }
    }

    /**
     * 根据任务状态解析 badge 文本与配色。
     * - enabled && status=="running" → 运行中（橙）
     * - enabled → 启用（绿）
     * - !enabled → 暂停（灰）
     * - status=="failed"/"error" → 失败（红）
     */
    private fun resolveStatusBadge(job: Job): Triple<String, Int, Int> {
        val status = job.status.lowercase()
        return when {
            status == "failed" || status == "error" ->
                Triple("失败", badgeError.first, badgeError.second)
            status == "running" ->
                Triple("运行中", badgeRunning.first, badgeRunning.second)
            !job.enabled ->
                Triple("暂停", badgePaused.first, badgePaused.second)
            else ->
                Triple("启用", badgeEnabled.first, badgeEnabled.second)
        }
    }

    /** 创建一个 badge 标签。 */
    private fun makeBadge(text: String, textColor: Int, darkBg: Int): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            background = getBadgeBackground(darkBg, textColor)
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

    /** 点击任务卡片：弹出操作对话框。 */
    private fun showJobActionsDialog(job: Job) {
        val pauseResumeLabel = if (job.enabled) "暂停" else "恢复"
        val items = arrayOf("立即执行", pauseResumeLabel, "删除")

        MaterialAlertDialogBuilder(this)
            .setTitle(job.name.ifEmpty { "定时任务" })
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> runJob(job)
                    1 -> pauseJob(job)
                    2 -> confirmDeleteJob(job)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除确认对话框（红色强调）。 */
    private fun confirmDeleteJob(job: Job) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除「${job.name}」吗？\n此操作不可撤销。")
            .setPositiveButton("删除") { _, _ -> deleteJob(job) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点击「创建任务」：弹出表单（名称 / cron 表达式 / 提示词）。 */
    private fun showCreateJobDialog() {
        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (4 * density).toInt(), (4 * density).toInt(),
                (4 * density).toInt(), (8 * density).toInt()
            )
        }

        val nameEt = addFormField(form, "任务名称", "如 每日报告", false, false)
        val cronEt = addCronField(form, "cron 表达式", "0 9 * * *", false)
        val promptEt = addFormField(form, "任务提示词", "执行每日报告", true, false)

        scroll.addView(form)

        MaterialAlertDialogBuilder(this)
            .setTitle("创建任务")
            .setView(scroll)
            .setPositiveButton("创建") { _, _ ->
                val name = nameEt.text.toString().trim()
                val schedule = cronEt.text.toString().trim()
                val prompt = promptEt.text.toString().trim()
                if (name.isEmpty() || schedule.isEmpty()) {
                    Toast.makeText(this, "任务名称和 cron 表达式不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createJob(name, schedule, prompt)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 向表单容器追加一个「标签 + 输入框」字段，返回该 EditText。 */
    private fun addFormField(
        container: LinearLayout,
        label: String,
        hint: String,
        multiline: Boolean,
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
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP
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

    /** cron 表达式输入字段 + 常用模板快捷按钮。 */
    private fun addCronField(
        container: LinearLayout,
        label: String,
        hint: String,
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
            inputType = InputType.TYPE_CLASS_TEXT
            background = getPlainBackground()
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
        }
        container.addView(et)

        // 常用模板提示标签
        container.addView(TextView(this).apply {
            text = "常用模板（点击填入）"
            setTextColor(colorDim)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        })

        // 模板按钮横向滚动条
        val templatesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val templatesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((tplName, tplCron) in cronTemplates) {
            templatesRow.addView(makeCronTemplateButton(tplName, tplCron, et))
        }
        templatesScroll.addView(templatesRow)
        container.addView(templatesScroll)

        return et
    }

    /** 单个 cron 模板快捷按钮。 */
    private fun makeCronTemplateButton(name: String, cron: String, target: EditText): View {
        return TextView(this).apply {
            text = name
            setTextColor(colorAccent)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = getClickableBackground()
            isClickable = true
            isFocusable = true
            setPadding(
                (12 * density).toInt(), (6 * density).toInt(),
                (12 * density).toInt(), (6 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            setOnClickListener {
                target.setText(cron)
                target.setSelection(cron.length)
            }
        }
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
        return MaterialAlertDialogBuilder(this)
            .setView(container)
            .setCancelable(false)
            .show()
    }

    // ── API 调用 ─────────────────────────────────────────────────────────

    /** GET /api/hermes/jobs → 解析并渲染卡片。 */
    private fun loadJobs() {
        showLoading()
        Thread {
            try {
                val resp = httpRequest("GET", "/api/hermes/jobs")
                val arr = JSONArray(resp)
                val parsed = ArrayList<Job>()
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    parsed.add(
                        Job(
                            id = j.optString("id", ""),
                            name = j.optString("name", ""),
                            schedule = j.optString("schedule", ""),
                            enabled = j.optBoolean("enabled", true),
                            lastRun = j.optString("last_run", ""),
                            nextRun = j.optString("next_run", ""),
                            status = j.optString("status", ""),
                        )
                    )
                }
                // 启用的在前，暂停的在后；同状态按下次运行时间排序
                parsed.sortWith(compareBy<Job>({ !it.enabled }, { it.nextRun }))
                handler.post {
                    jobs = parsed
                    renderCards()
                    showContent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadJobs failed", e)
                handler.post { showError(e.message ?: "未知错误") }
            }
        }.start()
    }

    /** 重新加载任务列表。 */
    private fun refreshJobs() {
        loadJobs()
    }

    /** POST /api/hermes/jobs — 创建任务。 */
    private fun createJob(name: String, schedule: String, prompt: String) {
        val dialog = showProgressDialog("正在创建任务…")
        Thread {
            try {
                val body = JSONObject().apply {
                    put("name", name)
                    put("schedule", schedule)
                    put("prompt", prompt)
                }.toString()
                httpRequest("POST", "/api/hermes/jobs", body)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "任务已创建", Toast.LENGTH_SHORT).show()
                    refreshJobs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "createJob failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "创建失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** POST /api/hermes/jobs/{id}/pause — 暂停/恢复任务。 */
    private fun pauseJob(job: Job) {
        val dialog = showProgressDialog(if (job.enabled) "正在暂停…" else "正在恢复…")
        Thread {
            try {
                httpRequest(
                    "POST",
                    "/api/hermes/jobs/" + URLEncoder.encode(job.id, "UTF-8") + "/pause"
                )
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        if (job.enabled) "已暂停" else "已恢复",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshJobs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "pauseJob failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "操作失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** POST /api/hermes/jobs/{id}/run — 立即执行任务。 */
    private fun runJob(job: Job) {
        val dialog = showProgressDialog("正在触发执行…")
        Thread {
            try {
                httpRequest(
                    "POST",
                    "/api/hermes/jobs/" + URLEncoder.encode(job.id, "UTF-8") + "/run"
                )
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "已触发执行", Toast.LENGTH_SHORT).show()
                    refreshJobs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "runJob failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "执行失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** DELETE /api/hermes/jobs/{id} — 删除任务。 */
    private fun deleteJob(job: Job) {
        val dialog = showProgressDialog("正在删除任务…")
        Thread {
            try {
                httpRequest(
                    "DELETE",
                    "/api/hermes/jobs/" + URLEncoder.encode(job.id, "UTF-8")
                )
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "任务已删除", Toast.LENGTH_SHORT).show()
                    refreshJobs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteJob failed", e)
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(this, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                }
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

    // ── 时间格式化 ───────────────────────────────────────────────────────

    /**
     * 将 ISO 8601 时间字符串格式化为 "yyyy-MM-dd HH:mm"。
     * 兼容带/不带时区的形式；解析失败时返回原字符串。
     */
    private fun formatTime(iso: String): String {
        if (iso.isBlank()) return "—"
        return try {
            val zdt = ZonedDateTime.parse(iso)
            zdt.withZoneSameInstant(ZoneId.systemDefault())
                .format(displayFormatter)
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(iso).format(displayFormatter)
            } catch (e2: DateTimeParseException) {
                try {
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

    /** 通用 badge 背景：暗底填充 + 强调色描边。 */
    private fun getBadgeBackground(darkBg: Int, accentColor: Int): Drawable {
        return GradientDrawable().apply {
            setColor(darkBg)
            cornerRadius = 6 * density
            setStroke(1, accentColor)
        }
    }
}
