package com.nous.hermes.mobile

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * A Termux-style terminal view that displays PTY output and captures keyboard input.
 *
 * Design:
 * - Extends EditText for IME (soft keyboard) support and text rendering.
 * - Display-only: input is captured via a custom InputConnection and sent to PTY,
 *   NOT inserted into the EditText. The PTY (real TTY) handles echo.
 * - ANSI escape sequences are stripped for display.
 * - Carriage return (\r) overwrites the current line (progress bar support).
 * - Text buffer is capped at MAX_LINES to prevent memory issues.
 * - Auto-scrolls to bottom on new output.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_LINES = 2000
        // CSI: \033[...letter, OSC: \033]...\007 or \033]...\033\\, others
        private val ANSI_PATTERN = Regex(
            "\u001B\\[[0-9;?]*[a-zA-Z]" +           // CSI sequences (colors, cursor)
            "|\u001B\\][^\u0007]*\u0007" +            // OSC sequences (title, BEL)
            "|\u001B\\][^\u001B]*\u001B\\\\" +         // OSC sequences (ST terminated)
            "|\u001B[()][AB012]" +                    // Charset designations
            "|\u001B[=>]" +                           // Keypad mode
            "|\u001B[78]" +                           // Save/restore cursor
            "|\u001B[ c]"                             // Misc single-char escapes
        )
    }

    /** Callback invoked when the user types input that should be sent to the PTY. */
    var onUserInput: ((ByteArray) -> Unit)? = null

    /** Whether Ctrl modifier is active (toggled by the function key bar). */
    var ctrlActive = false

    private val textBuffer = StringBuilder()
    private var cursorLine = 0    // Current line index (for \r handling)
    private var cursorCol = 0     // Current column on the line (for \r overwrite)

    init {
        // Display-only but focusable for IME
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_NULL  // Prevents EditText from editing, but still shows IME
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(true)
    }

    /**
     * Append raw bytes from the PTY to the terminal display.
     * Handles ANSI stripping and \r (carriage return) line overwrite.
     */
    fun appendOutput(rawBytes: ByteArray, len: Int) {
        val raw = String(rawBytes, 0, len, Charsets.UTF_8)
        val cleaned = raw.replace(ANSI_PATTERN, "")

        for (ch in cleaned) {
            when (ch) {
                '\r' -> {
                    // Carriage return: move cursor to start of current line
                    cursorCol = 0
                }
                '\n' -> {
                    // Newline: append to buffer
                    textBuffer.append('\n')
                    cursorLine++
                    cursorCol = 0
                }
                '\u0007' -> {
                    // BEL — ignore (no audible bell)
                }
                '\b' -> {
                    // Backspace from PTY — move cursor back
                    if (cursorCol > 0) cursorCol--
                }
                else -> {
                    // Overwrite or append at cursor position
                    val lineStart = findLineStart()
                    val writePos = lineStart + cursorCol
                    if (writePos < textBuffer.length) {
                        textBuffer.setCharAt(writePos, ch)
                    } else {
                        // Pad to position if needed
                        while (textBuffer.length < writePos) {
                            textBuffer.append(' ')
                        }
                        textBuffer.append(ch)
                    }
                    cursorCol++
                }
            }
        }

        // Trim buffer if too large
        trimBuffer()

        // Create snapshot on the calling thread (not the UI thread)
        // to avoid race condition with textBuffer modifications.
        val display = textBuffer.toString()

        // Update display on UI thread
        post {
            setText(display)
            // Scroll to bottom (guard against empty text)
            if (display.isNotEmpty()) {
                setSelection(display.length)
            }
        }
    }

    /** Find the start index of the current line (after last \n). */
    private fun findLineStart(): Int {
        var idx = textBuffer.length - 1
        while (idx >= 0 && textBuffer[idx] != '\n') {
            idx--
        }
        return idx + 1
    }

    /** Trim the buffer to keep only the last MAX_LINES lines. */
    private fun trimBuffer() {
        val nlCount = textBuffer.count { it == '\n' }
        if (nlCount > MAX_LINES) {
            val linesToTrim = nlCount - MAX_LINES
            var trimIdx = 0
            var count = 0
            while (count < linesToTrim && trimIdx < textBuffer.length) {
                if (textBuffer[trimIdx] == '\n') count++
                trimIdx++
            }
            textBuffer.delete(0, trimIdx)
            cursorLine -= linesToTrim
            if (cursorLine < 0) cursorLine = 0
        }
    }

    // --- Input handling ---

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this, true)
    }

    /**
     * Send a string to the PTY.
     */
    fun sendToPty(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        onUserInput?.invoke(bytes)
    }

    /**
     * Send a single byte to the PTY (for control characters).
     */
    fun sendByteToPty(byte: Int) {
        onUserInput?.invoke(byteArrayOf(byte.toByte()))
    }

    /**
     * Send a character, applying Ctrl modifier if active.
     * Ctrl+A = 0x01, Ctrl+B = 0x02, ..., Ctrl+Z = 0x1A
     */
    fun sendCharWithCtrl(ch: Char) {
        if (ctrlActive && ch in 'a'..'z') {
            sendByteToPty(ch.code - 'a'.code + 1)
            ctrlActive = false  // Ctrl is one-shot
        } else if (ctrlActive && ch in 'A'..'Z') {
            sendByteToPty(ch.code - 'A'.code + 1)
            ctrlActive = false
        } else {
            sendToPty(ch.toString())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            // Ctrl+key combinations via hardware keyboard
            val c = event.unicodeChar.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z') {
                sendByteToPty(c.lowercaseChar().code - 'a'.code + 1)
                return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                sendByteToPty(0x0D)  // CR
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                sendByteToPty(0x08)  // Backspace (BS)
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendByteToPty(0x09)  // Tab
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendToPty("\u001B[A")  // ESC [ A
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendToPty("\u001B[B")  // ESC [ B
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendToPty("\u001B[D")  // ESC [ D
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendToPty("\u001B[C")  // ESC [ C
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                sendByteToPty(0x1B)  // ESC
                return true
            }
        }

        // Regular character
        val ch = event.unicodeChar
        if (ch != 0) {
            sendCharWithCtrl(ch.toChar())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * Custom InputConnection that captures soft keyboard input
     * and sends it to the PTY instead of modifying the EditText.
     */
    private class TerminalInputConnection(
        val terminalView: TerminalView,
        fullEditor: Boolean,
    ) : BaseInputConnection(terminalView, fullEditor) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Send composed text to PTY
            for (ch in text.toString()) {
                terminalView.sendCharWithCtrl(ch)
            }
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int, afterLength: Int
        ): Boolean {
            // Handle backspace from soft keyboard
            if (beforeLength > 0) {
                terminalView.sendByteToPty(0x08)
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        terminalView.sendByteToPty(0x0D)
                        return true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        terminalView.sendByteToPty(0x08)
                        return true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        terminalView.sendByteToPty(0x09)
                        return true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        terminalView.sendByteToPty(0x1B)
                        return true
                    }
                    else -> {
                        // 处理常规字符键 —— TYPE_NULL 模式下 IME 通过
                        // sendKeyEvent 逐键发送，不调用 commitText。
                        val ch = event.unicodeChar
                        if (ch != 0) {
                            terminalView.sendCharWithCtrl(ch.toChar())
                            return true
                        }
                    }
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun finishComposingText(): Boolean {
            return true
        }
    }
}
