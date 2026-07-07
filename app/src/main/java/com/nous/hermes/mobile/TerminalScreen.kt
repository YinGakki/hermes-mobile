package com.nous.hermes.mobile

import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import android.util.Log

/**
 * 虚拟终端屏幕 — 维护 rows×cols 的字符网格，解析 ANSI 转义序列，
 * 渲染为 SpannableStringBuilder 供 EditText 显示。
 *
 * 支持的 ANSI 序列：
 * - SGR (ESC[...m)：前景/背景色、粗体、下划线、重置
 *   · 30-37 / 90-97  前景色（标准 + 亮色）
 *   · 40-47 / 100-107 背景色（标准 + 亮色）
 *   · 38;5;n / 48;5;n  256 色
 *   · 38;2;r;g;b / 48;2;r;g;b  真彩色
 *   · 0  重置, 1  粗体, 4  下划线, 22  取消粗体, 24  取消下划线
 * - 光标移动：ESC[H (home), ESC[r;cH (定位), ESC[nA/B/C/D (上/下/右/左)
 * - 清除：ESC[K (行), ESC[J (屏), ESC[2J (全屏)
 * - 滚动：ESC[r (scroll region), ESC[nS/T (scroll up/down)
 * - 字符操作：ESC[nP (删字符), ESC[n@ (插字符), ESC[nL (插行), ESC[nM (删行)
 * - 其他：ESC[?25h/l (光标显隐), ESC 7/8 (保存/恢复光标)
 * - 控制字符：\r \n \b \t \u0007
 */
class TerminalScreen(
    var rows: Int = 24,
    var cols: Int = 80,
) {
    companion object {
        private const val TAG = "TerminalScreen"

        // 标准 16 色 + 亮色（xterm 调色板）
        private val COLOR_PALETTE = intArrayOf(
            0xFF000000.toInt(),  // 0  黑
            0xFFcc0000.toInt(),  // 1  红
            0xFF4e9a06.toInt(),  // 2  绿
            0xFFc4a000.toInt(),  // 3  黄
            0xFF3465a4.toInt(),  // 4  蓝
            0xFF75507b.toInt(),  // 5  品红
            0xFF06989a.toInt(),  // 6  青
            0xFFd3d7cf.toInt(),  // 7  白
            0xFF555753.toInt(),  // 8  亮黑
            0xFFef2929.toInt(),  // 9  亮红
            0xFF8ae234.toInt(),  // 10 亮绿
            0xFFfce94f.toInt(),  // 11 亮黄
            0xFF729fcf.toInt(),  // 12 亮蓝
            0xFFad7fa8.toInt(),  // 13 亮品红
            0xFF34e2e2.toInt(),  // 14 亮青
            0xFFeeeeec.toInt(),  // 15 亮白
        )

        // 256 色调色板（索引 16-255）
        private val PALETTE_256: IntArray by lazy { build256Palette() }

        private fun build256Palette(): IntArray {
            val palette = IntArray(256)
            // 0-15：标准色
            for (i in 0..15) palette[i] = COLOR_PALETTE[i]
            // 16-231：6×6×6 RGB 立方体
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            var idx = 16
            for (r in 0..5)
                for (g in 0..5)
                    for (b in 0..5) {
                        palette[idx++] = (0xFF000000.toInt() or
                            (levels[r] shl 16) or (levels[g] shl 8) or levels[b])
                    }
            // 232-255：灰阶（24 级）
            for (i in 0..23) {
                val v = 8 + i * 10
                palette[232 + i] = (0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
            }
            return palette
        }

        private fun colorFrom256(idx: Int): Int =
            if (idx in PALETTE_256.indices) PALETTE_256[idx] else 0xFFd3d7cf.toInt()
    }

    // ── 屏幕缓冲区 ──
    private data class Cell(
        var ch: Char = ' ',
        var fg: Int = -1,   // -1 = 默认
        var bg: Int = -1,
        var bold: Boolean = false,
        var underline: Boolean = false,
    )

    @Volatile private var buffer = Array(rows) { Array(cols) { Cell() } }
    private val bufferLock = Any()

    // ── 光标 ──
    var cursorRow = 0
    var cursorCol = 0
    var cursorVisible = true

    // ── 当前属性（SGR 状态） ──
    private var curFg = -1
    private var curBg = -1
    private var curBold = false
    private var curUnderline = false

    // ── 保存的光标（ESC 7 / ESC 8） ──
    private var savedRow = 0
    private var savedCol = 0
    private var savedFg = -1
    private var savedBg = -1
    private var savedBold = false
    private var savedUnderline = false

    // ── 滚动区域 ──
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // ── ANSI 解析状态机 ──
    private enum class State { GROUND, ESC, CSI, OSC, ESC_INTERMEDIATE }
    private var parseState = State.GROUND
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var escIntermediate: Char = '\u0000'

    // ── 脏标记（优化渲染） ──
    @Volatile private var dirty = true

    // ═══════════════════════════════════════════════════════════════════
    //  公开 API
    // ═══════════════════════════════════════════════════════════════════

    /** 写入 PTY 输出数据（UTF-8 字节） */
    fun write(data: ByteArray, len: Int) {
        val text = String(data, 0, len, Charsets.UTF_8)
        write(text)
    }

    /** 写入字符串 */
    fun write(text: String) {
        synchronized(bufferLock) {
            for (ch in text) processChar(ch)
        }
        dirty = true
    }

    /** 调整屏幕大小 */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0) return
        if (newRows == rows && newCols == cols) return

        val newBuffer = Array(newRows) { Array(newCols) { Cell() } }
        synchronized(bufferLock) {
            // 复制旧内容到新缓冲区
            val copyRows = minOf(buffer.size, newRows)
            for (r in 0 until copyRows) {
                val copyCols = minOf(buffer[r].size, newCols)
                for (c in 0 until copyCols) {
                    val src = buffer[r][c]
                    newBuffer[r][c].ch = src.ch
                    newBuffer[r][c].fg = src.fg
                    newBuffer[r][c].bg = src.bg
                    newBuffer[r][c].bold = src.bold
                    newBuffer[r][c].underline = src.underline
                }
            }
            // 替换缓冲区引用
            buffer = newBuffer
        }

        rows = newRows
        cols = newCols
        scrollBottom = rows - 1
        if (cursorRow >= rows) cursorRow = rows - 1
        if (cursorCol >= cols) cursorCol = cols - 1
        dirty = true
    }

    /** 渲染为 SpannableStringBuilder */
    fun render(): SpannableStringBuilder {
        if (!dirty) return lastRender
        val sb = SpannableStringBuilder()
        synchronized(bufferLock) {
            val buf = buffer
            var lastFg = -1
            var lastBg = -1
            var lastBold = false
            var lastUnderline = false
            var spanStart = 0

            for (r in 0 until rows) {
                // 找到行末（去掉尾部空格）
                var lineEnd = cols
                while (lineEnd > 0 && buf[r][lineEnd - 1].ch == ' ') lineEnd--

                for (c in 0 until lineEnd) {
                    val cell = buf[r][c]
                    // 属性变化时设置 span
                    if (cell.fg != lastFg || cell.bg != lastBg ||
                        cell.bold != lastBold || cell.underline != lastUnderline) {
                        // 先结束上一个 span
                        if (sb.length > spanStart) {
                            applySpans(sb, spanStart, sb.length, lastFg, lastBg, lastBold, lastUnderline)
                        }
                        spanStart = sb.length
                        lastFg = cell.fg
                        lastBg = cell.bg
                        lastBold = cell.bold
                        lastUnderline = cell.underline
                    }
                    sb.append(cell.ch)
                }
                // 行末 span
                if (sb.length > spanStart) {
                    applySpans(sb, spanStart, sb.length, lastFg, lastBg, lastBold, lastUnderline)
                    spanStart = sb.length
                }
                if (r < rows - 1) sb.append('\n')
            }
        }

        // 光标（如果可见，加一个方块标记）
        if (cursorVisible && cursorRow in 0 until rows && cursorCol in 0 until cols) {
            val cursorPos = cursorRow * (cols + 1) + cursorCol // +1 for \n
            // 简单的光标：在光标位置加背景色
            if (cursorPos <= sb.length) {
                sb.setSpan(BackgroundColorSpan(0xFF64748b.toInt()),
                    cursorPos, minOf(cursorPos + 1, sb.length),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        lastRender = sb
        dirty = false
        return sb
    }

    private var lastRender = SpannableStringBuilder()

    private fun applySpans(
        sb: SpannableStringBuilder, start: Int, end: Int,
        fg: Int, bg: Int, bold: Boolean, underline: Boolean,
    ) {
        if (end <= start) return
        if (fg != -1) sb.setSpan(ForegroundColorSpan(fg), start, end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bg != -1) sb.setSpan(BackgroundColorSpan(bg), start, end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (underline) sb.setSpan(UnderlineSpan(), start, end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ANSI 解析状态机
    // ═══════════════════════════════════════════════════════════════════

    private fun processChar(ch: Char) {
        when (parseState) {
            State.GROUND -> processGround(ch)
            State.ESC -> processEsc(ch)
            State.CSI -> processCsi(ch)
            State.OSC -> processOsc(ch)
            State.ESC_INTERMEDIATE -> processEscIntermediate(ch)
        }
    }

    private fun processGround(ch: Char) {
        when (ch) {
            '\u001B' -> { parseState = State.ESC; csiBuffer.clear() }
            '\r' -> cursorCol = 0
            '\n' -> lineFeed()
            '\b' -> { if (cursorCol > 0) cursorCol-- }
            '\t' -> {
                val next = (cursorCol / 8 + 1) * 8
                cursorCol = minOf(next, cols - 1)
            }
            '\u0007' -> { /* bell — ignore */ }
            else -> {
                if (ch.code >= 32) putChar(ch)
            }
        }
    }

    private fun processEsc(ch: Char) {
        when (ch) {
            '[' -> { parseState = State.CSI; csiBuffer.clear() }
            ']' -> { parseState = State.OSC; oscBuffer.clear() }
            '7' -> { saveCursor(); parseState = State.GROUND }
            '8' -> { restoreCursor(); parseState = State.GROUND }
            'D' -> { lineFeed(); parseState = State.GROUND }  // IND
            'E' -> { cursorCol = 0; lineFeed(); parseState = State.GROUND }  // NEL
            'M' -> { reverseLineFeed(); parseState = State.GROUND }  // RI
            'c' -> { resetScreen(); parseState = State.GROUND }  // RIS
            '(' -> { parseState = State.ESC_INTERMEDIATE; escIntermediate = '(' }
            ')' -> { parseState = State.ESC_INTERMEDIATE; escIntermediate = ')' }
            '=' -> { parseState = State.GROUND }  // keypad mode
            '>' -> { parseState = State.GROUND }
            else -> { parseState = State.GROUND }
        }
    }

    private fun processEscIntermediate(ch: Char) {
        // 字符集指定 (ESC ( B = ASCII) — 忽略
        parseState = State.GROUND
    }

    private fun processCsi(ch: Char) {
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == ' ' || ch == ':' || ch == '=') {
            csiBuffer.append(ch)
        } else {
            // 终止符 — 执行序列
            executeCsi(ch)
            parseState = State.GROUND
        }
    }

    private fun processOsc(ch: Char) {
        if (ch == '\u0007' || ch == '\u001B') {
            // OSC 结束（BEL 或 ST）
            if (ch == '\u001B') {
                // ESC \ — ST 终止
                parseState = State.ESC
            } else {
                parseState = State.GROUND
            }
        } else {
            oscBuffer.append(ch)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CSI 序列执行
    // ═══════════════════════════════════════════════════════════════════

    private fun executeCsi(terminator: Char) {
        val params = parseCsiParams()
        val private = csiBuffer.startsWith("?")

        when (terminator) {
            // SGR — 颜色/样式
            'm' -> if (!private) executeSGR(params)

            // 光标移动
            'H', 'f' -> {
                val row = params.getOrNull(0)?.takeIf { it > 0 }?.let { it - 1 } ?: 0
                val col = params.getOrNull(1)?.takeIf { it > 0 }?.let { it - 1 } ?: 0
                setCursor(row, col)
            }
            'A' -> setCursor(cursorRow - (params.getOrNull(0) ?: 1), cursorCol)  // 上
            'B' -> setCursor(cursorRow + (params.getOrNull(0) ?: 1), cursorCol)  // 下
            'C' -> setCursor(cursorRow, cursorCol + (params.getOrNull(0) ?: 1))  // 右
            'D' -> setCursor(cursorRow, cursorCol - (params.getOrNull(0) ?: 1))  // 左
            'E' -> setCursor(cursorRow + (params.getOrNull(0) ?: 1), 0)  // 下一行行首
            'F' -> setCursor(cursorRow - (params.getOrNull(0) ?: 1), 0)  // 上一行行首
            'G' -> setCursor(cursorRow, (params.getOrNull(0)?.let { it - 1 } ?: 0))  // 列定位

            // 清除
            'J' -> eraseDisplay(params.getOrNull(0) ?: 0)    // 清屏
            'K' -> eraseLine(params.getOrNull(0) ?: 0)       // 清行

            // 滚动
            'S' -> scrollUp(params.getOrNull(0) ?: 1)        // 向上滚
            'T' -> scrollDown(params.getOrNull(0) ?: 1)      // 向下滚
            'L' -> insertLines(params.getOrNull(0) ?: 1)     // 插行
            'M' -> deleteLines(params.getOrNull(0) ?: 1)     // 删行
            'P' -> deleteChars(params.getOrNull(0) ?: 1)     // 删字符
            '@' -> insertChars(params.getOrNull(0) ?: 1)     // 插字符
            'X' -> eraseChars(params.getOrNull(0) ?: 1)      // 擦字符

            // 滚动区域
            'r' -> {
                if (!private) {
                    val top = params.getOrNull(0)?.takeIf { it > 0 }?.let { it - 1 } ?: 0
                    val bot = params.getOrNull(1)?.takeIf { it > 0 }?.let { it - 1 }
                        ?: (rows - 1)
                    setScrollRegion(top, bot)
                }
            }

            // 光标显隐
            'h' -> if (private && params.contains(25)) cursorVisible = true
            'l' -> if (private && params.contains(25)) cursorVisible = false

            // 光标查询（应答）
            'n' -> { /* 设备状态报告 — 需要 PTY 回写，暂忽略 */ }

            else -> {
                Log.d(TAG, "Unhandled CSI: ESC[${csiBuffer}$terminator")
            }
        }
    }

    private fun parseCsiParams(): List<Int> {
        val str = csiBuffer.toString().trimStart('?', '>', ' ', '=', ':')
        if (str.isEmpty()) return emptyList()
        return str.split(';').map { it.toIntOrNull() ?: 0 }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SGR（颜色/样式）
    // ═══════════════════════════════════════════════════════════════════

    private fun executeSGR(params: List<Int>) {
        if (params.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> resetAttributes()
                p == 1 -> curBold = true
                p == 2 -> curBold = false  // dim → 不加粗
                p == 3 -> { /* italic — 忽略 */ }
                p == 4 -> curUnderline = true
                p == 22 -> curBold = false
                p == 23 -> { /* not italic */ }
                p == 24 -> curUnderline = false
                p == 7 -> { /* reverse video — 交换 fg/bg */
                    val tmp = curFg; curFg = curBg; curBg = tmp
                }
                p == 27 -> { /* not reverse */ }
                p in 30..37 -> curFg = COLOR_PALETTE[p - 30]
                p == 38 -> {
                    // 扩展前景色
                    when (params.getOrNull(i + 1)) {
                        5 -> { curFg = colorFrom256(params.getOrNull(i + 2) ?: 0); i += 2 }
                        2 -> {
                            curFg = (0xFF000000.toInt() or
                                ((params.getOrNull(i + 2) ?: 0) shl 16) or
                                ((params.getOrNull(i + 3) ?: 0) shl 8) or
                                (params.getOrNull(i + 4) ?: 0))
                            i += 4
                        }
                    }
                }
                p == 39 -> curFg = -1  // 默认前景色
                p in 40..47 -> curBg = COLOR_PALETTE[p - 40]
                p == 48 -> {
                    when (params.getOrNull(i + 1)) {
                        5 -> { curBg = colorFrom256(params.getOrNull(i + 2) ?: 0); i += 2 }
                        2 -> {
                            curBg = (0xFF000000.toInt() or
                                ((params.getOrNull(i + 2) ?: 0) shl 16) or
                                ((params.getOrNull(i + 3) ?: 0) shl 8) or
                                (params.getOrNull(i + 4) ?: 0))
                            i += 4
                        }
                    }
                }
                p == 49 -> curBg = -1  // 默认背景色
                p in 90..97 -> curFg = COLOR_PALETTE[p - 90 + 8]   // 亮前景色
                p in 100..107 -> curBg = COLOR_PALETTE[p - 100 + 8] // 亮背景色
            }
            i++
        }
    }

    private fun resetAttributes() {
        curFg = -1
        curBg = -1
        curBold = false
        curUnderline = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  屏幕操作
    // ═══════════════════════════════════════════════════════════════════

    private fun putChar(ch: Char) {
        if (cursorRow !in 0 until rows || cursorCol !in 0 until cols) return
        val cell = buffer[cursorRow][cursorCol]
        cell.ch = ch
        cell.fg = curFg
        cell.bg = curBg
        cell.bold = curBold
        cell.underline = curUnderline
        advanceCursor()
    }

    private fun advanceCursor() {
        cursorCol++
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
    }

    private fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    private fun lineFeed() {
        cursorRow++
        if (cursorRow > scrollBottom) {
            cursorRow = scrollBottom
            scrollUp(1)
        }
    }

    private fun reverseLineFeed() {
        cursorRow--
        if (cursorRow < scrollTop) {
            cursorRow = scrollTop
            scrollDown(1)
        }
    }

    private fun scrollUp(n: Int) {
        val steps = minOf(n, scrollBottom - scrollTop + 1)
        for (r in scrollTop..(scrollBottom - steps)) {
            val src = r + steps
            if (src < rows) {
                for (c in 0 until cols) copyCell(buffer[src][c], buffer[r][c])
            }
        }
        // 清空底部 lines
        for (r in (scrollBottom - steps + 1)..scrollBottom) {
            for (c in 0 until cols) clearCell(buffer[r][c])
        }
    }

    private fun scrollDown(n: Int) {
        val steps = minOf(n, scrollBottom - scrollTop + 1)
        for (r in scrollBottom downTo (scrollTop + steps)) {
            val src = r - steps
            if (src >= 0) {
                for (c in 0 until cols) copyCell(buffer[src][c], buffer[r][c])
            }
        }
        // 清空顶部 lines
        for (r in scrollTop until (scrollTop + steps)) {
            for (c in 0 until cols) clearCell(buffer[r][c])
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                // 从光标到屏幕末尾
                eraseLineRange(cursorRow, cursorCol, cols - 1)
                for (r in (cursorRow + 1) until rows) eraseLineRange(r, 0, cols - 1)
            }
            1 -> {
                // 从屏幕开头到光标
                for (r in 0 until cursorRow) eraseLineRange(r, 0, cols - 1)
                eraseLineRange(cursorRow, 0, cursorCol)
            }
            2, 3 -> {
                // 全屏
                for (r in 0 until rows) eraseLineRange(r, 0, cols - 1)
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseLineRange(cursorRow, cursorCol, cols - 1)       // 光标到行末
            1 -> eraseLineRange(cursorRow, 0, cursorCol)              // 行首到光标
            2 -> eraseLineRange(cursorRow, 0, cols - 1)               // 整行
        }
    }

    private fun eraseLineRange(row: Int, fromCol: Int, toCol: Int) {
        if (row !in 0 until rows) return
        for (c in fromCol..toCol) {
            if (c in 0 until cols) clearCell(buffer[row][c])
        }
    }

    private fun eraseChars(n: Int) {
        if (cursorRow !in 0 until rows) return
        val end = minOf(cursorCol + n, cols)
        for (c in cursorCol until end) clearCell(buffer[cursorRow][c])
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val steps = minOf(n, scrollBottom - cursorRow + 1)
        // 向下移动行
        for (r in scrollBottom downTo (cursorRow + steps)) {
            val src = r - steps
            if (src >= 0) {
                for (c in 0 until cols) copyCell(buffer[src][c], buffer[r][c])
            }
        }
        // 清空新行
        for (r in cursorRow until (cursorRow + steps)) {
            for (c in 0 until cols) clearCell(buffer[r][c])
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val steps = minOf(n, scrollBottom - cursorRow + 1)
        // 向上移动行
        for (r in cursorRow..(scrollBottom - steps)) {
            val src = r + steps
            if (src < rows) {
                for (c in 0 until cols) copyCell(buffer[src][c], buffer[r][c])
            }
        }
        // 清空底部行
        for (r in (scrollBottom - steps + 1)..scrollBottom) {
            for (c in 0 until cols) clearCell(buffer[r][c])
        }
    }

    private fun insertChars(n: Int) {
        if (cursorRow !in 0 until rows) return
        val steps = minOf(n, cols - cursorCol)
        // 向右移动字符
        for (c in (cols - 1) downTo (cursorCol + steps)) {
            val src = c - steps
            if (src >= 0) copyCell(buffer[cursorRow][src], buffer[cursorRow][c])
        }
        // 清空新字符
        for (c in cursorCol until (cursorCol + steps)) {
            if (c < cols) clearCell(buffer[cursorRow][c])
        }
    }

    private fun deleteChars(n: Int) {
        if (cursorRow !in 0 until rows) return
        val steps = minOf(n, cols - cursorCol)
        // 向左移动字符
        for (c in cursorCol until (cols - steps)) {
            val src = c + steps
            if (src < cols) copyCell(buffer[cursorRow][src], buffer[cursorRow][c])
        }
        // 清空右侧
        for (c in (cols - steps) until cols) {
            clearCell(buffer[cursorRow][c])
        }
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(scrollTop, rows - 1)
        // 光标移到区域内
        setCursor(scrollTop, 0)
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedFg = curFg
        savedBg = curBg
        savedBold = curBold
        savedUnderline = curUnderline
    }

    private fun restoreCursor() {
        cursorRow = savedRow
        cursorCol = savedCol
        curFg = savedFg
        curBg = savedBg
        curBold = savedBold
        curUnderline = savedUnderline
    }

    private fun resetScreen() {
        for (r in 0 until rows)
            for (c in 0 until cols)
                clearCell(buffer[r][c])
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        resetAttributes()
    }

    // ── Cell 操作工具 ──
    private fun clearCell(cell: Cell) {
        cell.ch = ' '
        cell.fg = curFg
        cell.bg = curBg
        cell.bold = curBold
        cell.underline = curUnderline
    }

    private fun copyCell(src: Cell, dst: Cell) {
        dst.ch = src.ch
        dst.fg = src.fg
        dst.bg = src.bg
        dst.bold = src.bold
        dst.underline = src.underline
    }
}
