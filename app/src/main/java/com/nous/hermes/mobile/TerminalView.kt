package com.nous.hermes.mobile

import android.content.Context
import android.text.InputType
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * 真正的终端视图 — 使用 TerminalScreen 虚拟屏幕缓冲区，
 * 支持 ANSI 颜色、光标控制、屏幕清除/滚动。
 *
 * 架构：
 * - TerminalScreen: rows×cols 字符网格 + ANSI 解析器
 * - TerminalView: EditText + SpannableStringBuilder 渲染
 *   · PTY 输出 → TerminalScreen.write() → render() → setText()
 *   · 用户输入 → onUserInput 回调 → PTY
 *   · 布局变化 → 计算行列数 → TerminalScreen.resize()
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "TerminalView"
    }

    /** 回调：用户输入发送到 PTY */
    var onUserInput: ((ByteArray) -> Unit)? = null

    /** Ctrl 修饰键是否激活 */
    var ctrlActive = false

    /** 虚拟终端屏幕 */
    val screen = TerminalScreen()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_NULL
        isVerticalScrollBarEnabled = true
        setSingleLine(false)
    }

    /**
     * 写入 PTY 输出到终端屏幕，并触发渲染。
     * 在 PTY 读取线程调用（非 UI 线程）。
     */
    fun appendOutput(rawBytes: ByteArray, len: Int) {
        screen.write(rawBytes, len)
        renderToView()
    }

    /**
     * 将 TerminalScreen 渲染到 EditText。
     * 在 UI 线程执行 setText。
     */
    private fun renderToView() {
        val rendered = screen.render()
        post {
            val preserved = selectionStart
            setText(rendered)
            // 滚动到底部
            if (rendered.isNotEmpty()) {
                val pos = minOf(preserved.coerceAtLeast(rendered.length - 1), rendered.length)
                setSelection(pos)
            }
        }
    }

    /** 计算当前视图能显示的行列数 */
    fun updateScreenSize() {
        if (width <= 0 || height <= 0) return
        val paint = this.paint
        val charWidth = if (paint.measureText("M") > 0) paint.measureText("M") else 7f
        val charHeight = maxOf(lineHeight, 1)
        val newCols = maxOf((width / charWidth).toInt(), 20)
        val newRows = maxOf((height / charHeight).toInt(), 5)
        if (newCols != screen.cols || newRows != screen.rows) {
            screen.resize(newRows, newCols)
            renderToView()
        }
    }

    // ── 输入处理 ──

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this, true)
    }

    fun sendToPty(text: String) {
        onUserInput?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    fun sendByteToPty(byte: Int) {
        onUserInput?.invoke(byteArrayOf(byte.toByte()))
    }

    fun sendCharWithCtrl(ch: Char) {
        if (ctrlActive && ch in 'a'..'z') {
            sendByteToPty(ch.code - 'a'.code + 1)
            ctrlActive = false
        } else if (ctrlActive && ch in 'A'..'Z') {
            sendByteToPty(ch.code - 'A'.code + 1)
            ctrlActive = false
        } else {
            sendToPty(ch.toString())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            val c = event.unicodeChar.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z') {
                sendByteToPty(c.lowercaseChar().code - 'a'.code + 1)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                sendByteToPty(0x0D); return true
            }
            KeyEvent.KEYCODE_DEL -> { sendByteToPty(0x08); return true }
            KeyEvent.KEYCODE_TAB -> { sendByteToPty(0x09); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { sendToPty("\u001B[A"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { sendToPty("\u001B[B"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { sendToPty("\u001B[D"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sendToPty("\u001B[C"); return true }
            KeyEvent.KEYCODE_ESCAPE -> { sendByteToPty(0x1B); return true }
        }
        val ch = event.unicodeChar
        if (ch != 0) {
            sendCharWithCtrl(ch.toChar())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 自定义 InputConnection — 捕获软键盘输入发送到 PTY，
     * 不修改 EditText 内容（内容由 TerminalScreen 控制）。
     */
    private class TerminalInputConnection(
        val terminalView: TerminalView,
        fullEditor: Boolean,
    ) : BaseInputConnection(terminalView, fullEditor) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            for (ch in text.toString()) terminalView.sendCharWithCtrl(ch)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) terminalView.sendByteToPty(0x08)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER -> { terminalView.sendByteToPty(0x0D); return true }
                    KeyEvent.KEYCODE_DEL -> { terminalView.sendByteToPty(0x08); return true }
                    KeyEvent.KEYCODE_TAB -> { terminalView.sendByteToPty(0x09); return true }
                    KeyEvent.KEYCODE_ESCAPE -> { terminalView.sendByteToPty(0x1B); return true }
                    else -> {
                        val ch = event.unicodeChar
                        if (ch != 0) { terminalView.sendCharWithCtrl(ch.toChar()); return true }
                    }
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun finishComposingText(): Boolean = true
    }
}
