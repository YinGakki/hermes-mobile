package com.nous.hermes.mobile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 共享 UI 工具 — Drawable 工厂方法 + 主题颜色常量。
 *
 * 提取自 [SubSettingsActivity] / [ModelManagementActivity] 中重复的 Drawable 构建
 * 逻辑，保证两个页面（及未来其他页面）的卡片背景、图标方块、badge 标签视觉
 * 完全一致。所有方法均为静态（[object] 单例），接收 [Context] 参数以读取资源。
 *
 * 颜色常量集中在此处，避免散落在各 Activity 内的魔法数字。
 */
object UiUtils {

    // ── 主题颜色常量 ─────────────────────────────────────────────────────

    /** 页面背景 */
    val BG = 0xFF020617.toInt()
    /** 卡片背景 */
    val CARD = 0xFF0f172a.toInt()
    /** 可点击卡片按下态 / 图标方块背景 */
    val CARD_CLICKABLE = 0xFF1e293b.toInt()
    /** 主标题文字 */
    val TEXT_PRIMARY = 0xFFe2e8f0.toInt()
    /** 副标题文字 */
    val TEXT_SECONDARY = 0xFF94a3b8.toInt()
    /** 强调色（图标、链接、进度条） */
    val ACCENT = 0xFF818cf8.toInt()
    /** 成功 / 更新可用 */
    val SUCCESS = 0xFF10b981.toInt()
    /** 警告 / 测试版 */
    val WARNING = 0xFFf59e0b.toInt()
    /** 信息 / 内置标记 */
    val INFO = 0xFF6366f1.toInt()
    /** 危险 / 错误 */
    val DANGER = 0xFFef4444.toInt()

    /** 卡片描边色（与 [CARD] 配合使用） */
    val STROKE = 0xFF334155.toInt()
    /** 默认（绿色）badge 的暗底填充色 */
    val BADGE_DEFAULT_BG = 0xFF064e3b.toInt()

    // ── Drawable 工厂 ────────────────────────────────────────────────────

    /**
     * 可点击卡片背景：按下时高亮（[CARD_CLICKABLE]），常态为 [CARD] + 描边。
     * 圆角 12dp。
     */
    fun getClickableBackground(context: Context): StateListDrawable {
        val density = context.resources.displayMetrics.density
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(CARD_CLICKABLE)
                    cornerRadius = 12 * density
                }
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    setColor(CARD)
                    cornerRadius = 12 * density
                    setStroke(1, STROKE)
                }
            )
        }
    }

    /**
     * 普通卡片背景（不可点击）：[CARD] + 描边，圆角 12dp。
     */
    fun getPlainBackground(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = 12 * density
            setStroke(1, STROKE)
        }
    }

    /**
     * 图标方块背景：[CARD_CLICKABLE]，圆角 10dp。
     */
    fun getIconTileBackground(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(CARD_CLICKABLE)
            cornerRadius = 10 * density
        }
    }

    /**
     * 默认（绿色）badge 背景：[BADGE_DEFAULT_BG] 暗绿底 + [SUCCESS] 描边，圆角 6dp。
     */
    fun getBadgeBackground(context: Context): GradientDrawable {
        return badgeBackground(context, BADGE_DEFAULT_BG, SUCCESS)
    }

    /**
     * 通用 badge 背景：暗底填充 + 强调色描边，圆角 6dp。
     */
    private fun badgeBackground(context: Context, bgColor: Int, strokeColor: Int): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 6 * density
            setStroke(1, strokeColor)
        }
    }

    /**
     * 创建一个 badge 标签 [TextView]。
     *
     * 视觉与原 [ModelManagementActivity.makeBadge] 完全一致：
     * 10sp 粗体、暗底 + 描边背景（描边色即文字色）、圆角 6dp、
     * 内边距 (8,3,8,3)dp、左外边距 6dp。
     *
     * @param context   上下文
     * @param text      标签文字
     * @param bgColor   背景填充色（暗色底）
     * @param textColor 文字色（同时作为描边色）
     */
    fun makeBadge(context: Context, text: String, bgColor: Int, textColor: Int): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            background = badgeBackground(context, bgColor, textColor)
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
}
