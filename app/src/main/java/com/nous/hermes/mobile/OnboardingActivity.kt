package com.nous.hermes.mobile

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Scroller
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/**
 * 首次使用引导页面。
 *
 * 3 页滑动引导（自定义水平分页器 OnboardingPager，纯代码构建，不依赖
 * ViewPager2 以避免额外的传递依赖）：
 *   1. 欢迎 — 图标 + 标题 + 描述
 *   2. 核心功能 — 功能列表
 *   3. 准备开始 — 安装提示 + 权限说明 + "开始安装" 按钮
 *
 * 交互：
 *   - 左右滑动切换页面（底部 3 个圆点指示器，当前页高亮 #818cf8）。
 *   - 最后一页"开始安装" → 写 onboarding_completed=true → setResult(RESULT_OK) + finish。
 *   - "跳过"按钮 → setResult(RESULT_OK) + finish。
 *   - 返回键 → setResult(RESULT_CANCELED) + finish（用户中途退出引导）。
 *
 * 结果码约定：
 *   - RESULT_OK        — 用户完成引导（点击开始安装或跳过），调用方应继续主流程。
 *   - RESULT_CANCELED  — 用户中途退出引导，调用方应保持未完成状态。
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"
        private const val PREF_NAME = "hermes_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val PAGE_COUNT = 3
    }

    private val density by lazy { resources.displayMetrics.density }

    private lateinit var pager: OnboardingPager
    private val dots = mutableListOf<View>()

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        // 返回键 = 中途退出引导 → CANCELED
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelOnboarding()
            }
        })
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF020617.toInt())
        }

        // --- 顶部栏：右上角"跳过" ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt()
            )
        }
        val skipBtn = TextView(this).apply {
            text = "跳过"
            setTextColor(0xFF94a3b8.toInt())
            textSize = 14f
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            background = getTransparentClickableBackground()
            setOnClickListener { completeOnboarding() }
        }
        topBar.addView(skipBtn)
        root.addView(topBar)

        // --- 分页器 ---
        pager = OnboardingPager(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            pageCount = PAGE_COUNT
            onPageChanged = { page -> updateIndicator(page) }
        }
        pager.addView(buildWelcomePage())
        pager.addView(buildFeaturesPage())
        pager.addView(buildReadyPage())
        root.addView(pager)

        // --- 底部页面指示器 ---
        val indicatorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (14 * density).toInt(), 0, (24 * density).toInt())
        }
        for (i in 0 until PAGE_COUNT) {
            val dot = View(this).apply {
                val size = (if (i == 0) 9 else 8) * density
                layoutParams = LinearLayout.LayoutParams(size.toInt(), size.toInt()).apply {
                    val m = (5 * density).toInt()
                    marginStart = m
                    marginEnd = m
                }
                background = makeDotBackground(i == 0)
            }
            dots.add(dot)
            indicatorRow.addView(dot)
        }
        root.addView(indicatorRow)

        setContentView(root)
    }

    // ── 第 1 页：欢迎 ────────────────────────────────────────────────────────

    private fun buildWelcomePage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (32 * density).toInt(), (8 * density).toInt(),
                (32 * density).toInt(), (8 * density).toInt()
            )
        }

        // 大图标（使用 ic_launcher_foreground）
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                (128 * density).toInt(), (128 * density).toInt()
            ).apply { bottomMargin = (24 * density).toInt() }
        }
        page.addView(icon)

        page.addView(makeTitle("欢迎使用 Hermes Agent"))

        page.addView(makeDescription(
            "在 Android 上运行完整的 AI Agent 环境。支持终端、聊天、模型管理。"
        ))
        return page
    }

    // ── 第 2 页：核心功能 ────────────────────────────────────────────────────

    private fun buildFeaturesPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (28 * density).toInt(), (8 * density).toInt(),
                (28 * density).toInt(), (8 * density).toInt()
            )
        }

        page.addView(makeTitle("核心功能").apply {
            gravity = Gravity.CENTER
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                bottomMargin = (20 * density).toInt()
            }
        })

        val features = listOf(
            "🖥️" to ("内置终端" to "完整的 ANSI 终端模拟器"),
            "💬" to ("AI 聊天" to "通过 WebUI 界面对话"),
            "⚙️" to ("模型管理" to "管理 Provider 和切换模型"),
            "📦" to ("环境备份" to "一键备份/还原完整环境"),
            "🔄" to ("自动更新" to "Hermes/WebUI/APK 一键更新"),
        )
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for ((emoji, pair) in features) {
            list.addView(makeFeatureRow(emoji, pair.first, pair.second))
        }
        page.addView(list)
        return page
    }

    private fun makeFeatureRow(emoji: String, title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt()
            )
            val icon = TextView(this@OnboardingActivity).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(), (40 * density).toInt()
                )
            }
            addView(icon)
            val col = LinearLayout(this@OnboardingActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (14 * density).toInt() }
            }
            col.addView(TextView(this@OnboardingActivity).apply {
                text = title
                setTextColor(0xFFe2e8f0.toInt())
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this@OnboardingActivity).apply {
                text = subtitle
                setTextColor(0xFF94a3b8.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
            })
            addView(col)
        }
    }

    // ── 第 3 页：准备开始 ────────────────────────────────────────────────────

    private fun buildReadyPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (32 * density).toInt(), (8 * density).toInt(),
                (32 * density).toInt(), (8 * density).toInt()
            )
        }

        page.addView(makeTitle("准备开始"))

        page.addView(makeDescription(
            "安装过程需要约 10-15 分钟，需要约 1GB 存储空间。"
        ))

        page.addView(TextView(this).apply {
            text = "需要存储权限和电池优化白名单以确保后台运行。"
            setTextColor(0xFF94a3b8.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing((3 * density), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (28 * density).toInt()
            }
        })

        // "开始安装" 按钮
        val startBtn = TextView(this).apply {
            text = "开始安装"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = getPrimaryButtonBackground()
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { completeOnboarding() }
        }
        page.addView(startBtn)

        return page
    }

    // ── 公共组件 ─────────────────────────────────────────────────────────────

    private fun makeTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * density).toInt() }
        }
    }

    private fun makeDescription(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFF94a3b8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing((4 * density), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ── 指示器 ───────────────────────────────────────────────────────────────

    private fun updateIndicator(page: Int) {
        for (i in dots.indices) {
            dots[i].background = makeDotBackground(i == page)
            val size = (if (i == page) 9 else 8) * density
            dots[i].layoutParams = dots[i].layoutParams.apply {
                width = size.toInt()
                height = size.toInt()
            }
        }
    }

    private fun makeDotBackground(active: Boolean): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (active) 0xFF818cf8.toInt() else 0xFF334155.toInt())
        }
    }

    // ── 结果处理 ─────────────────────────────────────────────────────────────

    /** 用户完成引导（开始安装 / 跳过）：标记完成并返回 OK。 */
    private fun completeOnboarding() {
        try {
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write onboarding pref: ${e.message}")
        }
        setResult(RESULT_OK)
        finish()
    }

    /** 用户中途退出引导：返回 CANCELED，不写完成标记。 */
    private fun cancelOnboarding() {
        setResult(RESULT_CANCELED)
        finish()
    }

    // ── Drawable 工厂 ───────────────────────────────────────────────────────

    private fun getPrimaryButtonBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF6366f1.toInt())
                    cornerRadius = 14 * density
                }
            )
            addState(
                intArrayOf(),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF818cf8.toInt())
                    cornerRadius = 14 * density
                }
            )
        }
    }

    private fun getTransparentClickableBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x221e293b)
                    cornerRadius = 10 * density
                }
            )
            addState(
                intArrayOf(),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x001e293b)
                    cornerRadius = 10 * density
                }
            )
        }
    }

    // ── 自定义水平分页器 ─────────────────────────────────────────────────────

    /**
     * 轻量级水平滑动分页器（纯代码，无外部依赖）。
     *
     * - 子 View 横向排列，每个占满一页宽度。
     * - 手指拖动跟随，松手后按速度/位移吸附到最近页（Scroller 平滑滚动）。
     * - 水平滑动优先于垂直滑动（onInterceptTouchEvent 判定），保证页内按钮可点击。
     */
    inner class OnboardingPager(context: Context) : ViewGroup(context) {

        var pageCount: Int = 0
        var onPageChanged: ((Int) -> Unit)? = null

        private var currentPage: Int = 0

        private val scroller = Scroller(context, android.view.animation.DecelerateInterpolator())
        private var velocity: VelocityTracker? = null
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var dragging = false

        private fun maxScrollX(): Int = if (pageCount > 0) (pageCount - 1) * width else 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                getChildAt(i).measure(childW, childH)
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val cl = i * w
                child.layout(cl, 0, cl + child.measuredWidth, child.measuredHeight)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (pageCount <= 1) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    lastX = ev.x
                    dragging = false
                    velocity?.clear()
                    velocity = velocity ?: VelocityTracker.obtain()
                    velocity?.addMovement(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    // 持续更新 lastX，避免拦截切换瞬间出现位移跳变。
                    lastX = ev.x
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        // 通知父布局（如 ScrollView）不要再拦截本次触摸序列
                        requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (pageCount <= 1) return false
            velocity = velocity ?: VelocityTracker.obtain()
            velocity?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    lastX = ev.x
                    if (!scroller.isFinished) scroller.abortAnimation()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lastX
                    lastX = ev.x
                    var newScroll = scrollX - dx.toInt()
                    newScroll = newScroll.coerceIn(0, maxScrollX())
                    scrollTo(newScroll, 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vx = velocity?.xVelocity ?: 0f
                    var target: Int
                    if (abs(vx) > minFling && width > 0) {
                        // vx>0 表示手指向右移动 → 上一页
                        target = if (vx > 0) currentPage - 1 else currentPage + 1
                    } else if (width > 0) {
                        target = (scrollX + width / 2) / width
                    } else {
                        target = currentPage
                    }
                    smoothScrollToPage(target)
                    dragging = false
                    velocity?.clear()
                }
            }
            return true
        }

        /** 平滑滚动到指定页并通知页码变化。 */
        fun smoothScrollToPage(page: Int) {
            if (width <= 0) return
            val target = page.coerceIn(0, pageCount - 1)
            if (target != currentPage) {
                currentPage = target
                onPageChanged?.invoke(target)
            }
            val dx = target * width - scrollX
            if (dx != 0) {
                scroller.startScroll(scrollX, 0, dx, 0, 300)
                invalidate()
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                invalidate()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            velocity?.recycle()
            velocity = null
        }
    }
}
