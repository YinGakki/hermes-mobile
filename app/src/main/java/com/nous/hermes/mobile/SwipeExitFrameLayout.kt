package com.nous.hermes.mobile

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 支持左边缘滑动退出的 [FrameLayout]。
 *
 * 提取自 [ChatActivity] / [TerminalActivity] 中几乎完全相同的
 * `SwipeExitContainer` 内部类，统一为独立自定义 View，消除重复实现。
 *
 * 交互流程：
 * 1. 用户从屏幕左边缘（[edgeWidth] 内）按下
 * 2. 向右滑动超过 16dp 且横向位移 > 纵向时，开始拦截手势
 * 3. 滑动过程中显示跟随手指的"← 退出"指示器，透明度随距离增加（0.3 → 1.0）
 * 4. 松手时滑动距离超过 [dragThreshold] 则退出 Activity，否则回弹隐藏指示器
 *
 * 优势：界面完全干净，无常驻按钮；手势符合直觉（类似 iOS 边缘滑动返回）。
 *
 * 通过 [setSwipeHint] 配置首次使用时的滑动提示 Toast（不同页面使用不同的
 * SharedPreferences 键与文案）。
 */
class SwipeExitFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        /** 首次提示所用的 SharedPreferences 文件名 */
        private const val PREFS_NAME = "hermes_prefs"
    }

    private val density = resources.displayMetrics.density

    /** 左边缘触发宽度（20dp） */
    private val edgeWidth = 20 * density

    /** 滑动退出阈值（120dp） */
    private val dragThreshold = 120 * density

    /** 开始拦截手势的横向位移阈值（16dp） */
    private val swipeStartThreshold = 16 * density

    private var startX = 0f
    private var startY = 0f
    private var fromEdge = false
    private var swiping = false

    // 跟随手指的滑动指示器
    private val hintView = TextView(context).apply {
        text = "←  退出"
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * density
            setColor(0xCC1e293b.toInt())
            setStroke(1, 0x66ffffff)
        }
        alpha = 0f
        visibility = View.GONE
    }

    init {
        addView(hintView, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        })
    }

    /**
     * 配置首次使用时的滑动提示 Toast：仅当 [prefKey] 对应的值为 false 时
     * 显示一次 [message]，随后标记为已显示。
     *
     * @param prefKey SharedPreferences 键名（不同页面用不同键以各自只提示一次）
     * @param message 提示文案
     */
    fun setSwipeHint(prefKey: String, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(prefKey, false)) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            prefs.edit().putBoolean(prefKey, true).apply()
        }
    }

    /**
     * 在左边缘区域排除系统手势（Android 10+ 的返回手势），
     * 否则系统会优先消费左边缘触摸，导致我们的左滑退出手势无效。
     * 系统限制每边最多 200dp 高度，超出部分自动截断。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && height > 0) {
            val exclusionHeight = minOf(height, (200 * density).toInt())
            val rect = Rect(0, 0, edgeWidth.toInt(), exclusionHeight)
            systemGestureExclusionRects = listOf(rect)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                fromEdge = ev.x < edgeWidth
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (fromEdge && !swiping) {
                    val dx = ev.rawX - startX
                    val ady = Math.abs(ev.rawY - startY)
                    // 横向滑动超过阈值且横向位移明显大于纵向时，开始拦截
                    if (dx > swipeStartThreshold && dx > ady * 1.5f) {
                        swiping = true
                        hintView.visibility = View.VISIBLE
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - startX).coerceAtLeast(0f)
                // 指示器跟随手指移动
                hintView.translationX = dx
                // 透明度随滑动距离增加（0.3 → 1.0）
                hintView.alpha = (dx / dragThreshold).coerceIn(0.3f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.rawX - startX
                if (dx > dragThreshold) {
                    // 达到阈值，淡出后退出
                    hintView.animate()
                        .alpha(0f)
                        .setDuration(100)
                        .withEndAction { hintView.visibility = View.GONE }
                        .start()
                    finishHostActivity()
                } else {
                    // 未达到阈值，回弹隐藏
                    hintView.animate()
                        .translationX(0f)
                        .alpha(0f)
                        .setDuration(150)
                        .withEndAction { hintView.visibility = View.GONE }
                        .start()
                }
                swiping = false
                fromEdge = false
            }
        }
        return true
    }

    /** 退出宿主 Activity（构造时传入的 Context 即为 Activity）。 */
    private fun finishHostActivity() {
        (context as? Activity)?.finish()
    }
}
