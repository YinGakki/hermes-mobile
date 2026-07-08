package com.nous.hermes.mobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置终端 — Termux 风格的 PTY 终端，在 proot rootfs 里运行交互式 bash shell。
 *
 * 特性：
 * - 真正的 PTY（伪终端），支持交互式 TUI 程序（hermes setup、vim、htop 等）
 * - 全屏终端显示，直接在终端里输入（无独立输入框）
 * - ANSI 转义序列处理（颜色剥离、\r 行覆盖）
 * - 底部功能键栏（ESC、Tab、Ctrl、方向键、复制）适配软键盘
 * - 左边缘滑动退出（不杀进程，后台保持会话）
 * - 长按终端可复制全部内容
 *
 * 会话管理：
 * - 使用 [TerminalSession] 单例，退出页面不杀进程
 * - 重新打开时自动恢复之前的会话和缓冲输出
 * - 在 shell 中输入 exit 才会真正终止进程
 */
class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalActivity"
        private const val PREF_SWIPE_HINT_SHOWN = "terminal_swipe_hint_shown"
    }

    private lateinit var terminalView: TerminalView
    private lateinit var ctrlButton: TextView

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val density = resources.displayMetrics.density

        // === 根容器：支持左边缘滑动退出 ===
        val container = SwipeExitFrameLayout(this)

        // === 垂直布局：标题栏 + 终端区 + 功能键栏 ===
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0f172a.toInt())
        }

        // --- 标题栏 ---
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
            elevation = 4 * density
        }
        val titleText = TextView(this).apply {
            text = "Hermes Terminal"
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 会话状态指示
        val statusText = TextView(this).apply {
            text = ""
            setTextColor(0xFF64748b.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
        }

        // 会话管理按钮
        val sessionBtn = TextView(this).apply {
            text = "会话 +"
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6 * density
                setColor(0xFF334155.toInt())
            }
            val pad = (10 * density).toInt()
            setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            isClickable = true
            setOnClickListener {
                showSessionDialog(statusText)
            }
        }

        // 退出终端按钮
        val exitBtn = TextView(this).apply {
            text = "退出"
            setTextColor(0xFFfca5a5.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6 * density
                setColor(0xFF7f1d1d.toInt())
            }
            val pad = (10 * density).toInt()
            setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            setOnClickListener {
                showExitDialog()
            }
        }

        titleBar.addView(titleText)
        titleBar.addView(statusText)
        titleBar.addView(sessionBtn)
        titleBar.addView(exitBtn)
        rootLayout.addView(titleBar)

        // --- 终端显示区 ---
        terminalView = TerminalView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF4ade80.toInt())
            textSize = 12f
            setBackgroundColor(0xFF0f172a.toInt())
            setPadding(
                (8 * density).toInt(), (6 * density).toInt(),
                (8 * density).toInt(), (6 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            // 输入回调 → 写入 PTY
            onUserInput = { bytes ->
                TerminalSession.write(bytes)
            }
            // 点击终端区域 → 请求焦点 + 弹出软键盘
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
            // 长按 → 复制全部内容
            setOnLongClickListener {
                val text = text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    Toast.makeText(this@TerminalActivity, "已复制终端内容", Toast.LENGTH_SHORT).show()
                }
                true
            }
            // 布局变化时更新 PTY 窗口大小
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (width > 0 && height > 0) {
                    val newW = right - left
                    val newH = bottom - top
                    val oldW = oldRight - oldLeft
                    val oldH = oldBottom - oldTop
                    if (newW != oldW || newH != oldH) {
                        terminalView.updateScreenSize()
                        sendWindowSizeToPty()
                    }
                }
            }
        }
        rootLayout.addView(terminalView)

        // --- 底部功能键栏 ---
        val keyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(
                (6 * density).toInt(), (4 * density).toInt(),
                (6 * density).toInt(), (4 * density).toInt()
            )
        }

        fun makeKeyButton(label: String, width: Int = 0): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(0xFFe2e8f0.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                val pad = (10 * density).toInt()
                setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(0xFF334155.toInt())
                }
                isClickable = true
                if (width > 0) {
                    layoutParams = LinearLayout.LayoutParams(
                        width, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                } else {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            }
        }

        val escBtn = makeKeyButton("ESC").apply {
            setOnClickListener { terminalView.sendByteToPty(0x1B) }
        }
        val tabBtn = makeKeyButton("TAB").apply {
            setOnClickListener { terminalView.sendByteToPty(0x09) }
        }
        ctrlButton = makeKeyButton("CTRL").apply {
            setOnClickListener {
                terminalView.ctrlActive = !terminalView.ctrlActive
                if (terminalView.ctrlActive) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(0xFF06b6d4.toInt())
                    }
                    setTextColor(0xFF0f172a.toInt())
                } else {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(0xFF334155.toInt())
                    }
                    setTextColor(0xFFe2e8f0.toInt())
                }
            }
        }
        // 复制按钮
        val copyBtn = makeKeyButton("COPY").apply {
            setOnClickListener {
                val text = terminalView.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    Toast.makeText(this@TerminalActivity, "已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val leftBtn = makeKeyButton("←", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[D") }
        }
        val upBtn = makeKeyButton("↑", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[A") }
        }
        val downBtn = makeKeyButton("↓", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[B") }
        }
        val rightBtn = makeKeyButton("→", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[C") }
        }

        keyBar.addView(escBtn)
        keyBar.addView(tabBtn)
        keyBar.addView(ctrlButton)
        keyBar.addView(copyBtn)
        keyBar.addView(leftBtn)
        keyBar.addView(upBtn)
        keyBar.addView(downBtn)
        keyBar.addView(rightBtn)
        rootLayout.addView(keyBar)

        container.addView(rootLayout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(container)

        container.setSwipeHint(PREF_SWIPE_HINT_SHOWN, "从屏幕左边缘向右滑动可退出终端（会话保留）")

        // 连接或创建终端会话
        connectTerminal(statusText)
    }

    override fun onResume() {
        super.onResume()
        terminalView.post {
            terminalView.requestFocus()
        }
    }

    /**
     * 连接到现有会话或创建新会话。
     */
    private fun connectTerminal(statusText: TextView) {
        statusTextRef = statusText
        // 设置 DSR 回调 — TUI 应用通过 ESC[6n 查询光标位置
        terminalView.screen.dsrCallback = { response ->
            TerminalSession.write(response.toByteArray())
        }

        if (TerminalSession.isRunning()) {
            // 恢复现有会话
            statusText.text = "已有会话"
            val replay = TerminalSession.reconnect(
                onOutput = { bytes, len ->
                    terminalView.appendOutput(bytes, len)
                },
                onExit = { code ->
                    handler.post {
                        val msg = "\n[进程退出，code=$code]\n"
                        terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                        Toast.makeText(this, "Shell 已退出", Toast.LENGTH_SHORT).show()
                        statusText.text = "已退出"
                    }
                }
            )
            // 回放缓存的输出
            if (replay != null && replay.isNotEmpty()) {
                terminalView.appendOutput(replay.toByteArray(), replay.toByteArray().size)
            }
            // 更新窗口大小
            updateWindowSize()
            Log.i(TAG, "Reconnected to existing terminal session")
        } else {
            // 创建新会话
            statusText.text = "启动中…"
            startPtyShell(statusText)
        }
    }

    /**
     * 启动 PTY shell：通过 JNI 创建伪终端，fork + exec proot + bash。
     */
    private fun startPtyShell(statusText: TextView) {
        Thread {
            try {
                val paths = BootstrapManager.getPaths(this)
                val pm = ProcessManager(this, paths.filesDir, paths.nativeLibDir)

                val shellCmd = (
                    "cd /root/home/hermes-agent 2>/dev/null; " +
                    "export PATH=/root/home/hermes-agent/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "export TERM=xterm-256color; " +
                    "export PS1='\\u@\\h:\\w\\$ '; " +
                    "echo '  Hermes Agent 终端 — proot + Ubuntu 24.04'; " +
                    "echo '  输入 hermes --help 查看可用命令 | 输入 exit 退出 shell'; " +
                    "exec bash --login"
                )

                val cmd = pm.buildGatewayCommand(shellCmd).toTypedArray()
                val env = pm.prootEnvPublic().map { (k, v) -> "$k=$v" }.toTypedArray()

                Log.i(TAG, "Starting PTY shell: ${cmd.firstOrNull()}")

                val result = PtyNative.createSubprocess(cmd, env)
                if (result == null) {
                    handler.post {
                        val msg = "[错误: 无法创建 PTY]\n"
                        terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                        Toast.makeText(this, "终端启动失败", Toast.LENGTH_LONG).show()
                        statusText.text = "启动失败"
                    }
                    return@Thread
                }

                val masterFd = result[0]
                val pid = result[1]

                Log.i(TAG, "PTY shell started: pid=$pid masterFd=$masterFd")

                // 初始化会话单例
                TerminalSession.initSession(masterFd, pid)

                handler.post { statusText.text = "" }

                // 注册输出和退出监听
                TerminalSession.reconnect(
                    onOutput = { bytes, len ->
                        terminalView.appendOutput(bytes, len)
                    },
                    onExit = { code ->
                        handler.post {
                            val msg = "\n[进程退出，code=$code]\n"
                            terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                            Toast.makeText(this, "Shell 已退出", Toast.LENGTH_SHORT).show()
                            statusText.text = "已退出"
                        }
                    }
                )

                // 设置初始窗口大小
                updateWindowSize()

            } catch (e: Exception) {
                Log.e(TAG, "PTY shell failed", e)
                handler.post {
                    val msg = "\n[错误: ${e.message}]\n"
                    terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                    Toast.makeText(this, "终端启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = "启动失败"
                }
            }
        }.start()
    }

    private fun updateWindowSize() {
        terminalView.post {
            if (terminalView.width > 0 && terminalView.height > 0) {
                terminalView.updateScreenSize()
                sendWindowSizeToPty()
            } else {
                terminalView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (terminalView.width > 0 && terminalView.height > 0) {
                            terminalView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            sendWindowSizeToPty()
                        }
                    }
                })
            }
        }
    }

    private fun sendWindowSizeToPty() {
        val rows = terminalView.screen.rows
        val cols = terminalView.screen.cols
        Log.i(TAG, "Window size: ${rows}r x ${cols}c")
        TerminalSession.setWindowSize(rows, cols)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 断开连接但保持进程存活
        TerminalSession.disconnect()
    }

    /** 会话管理对话框 — 新建会话、切换会话、关闭会话 */
    private fun showSessionDialog(statusText: TextView) {
        val sessionList = TerminalSession.getSessionList()
        val items = if (sessionList.isEmpty()) {
            arrayOf("+ 新建会话")
        } else {
            sessionList.map { it.second }.toTypedArray() + arrayOf("+ 新建会话")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("终端会话 (${sessionList.size})")
            .setItems(items) { _, which ->
                if (which == items.size - 1) {
                    // 新建会话
                    createNewSession(statusText)
                } else {
                    // 切换到选中的会话
                    val sessionId = sessionList[which].first
                    if (sessionId != TerminalSession.activeSessionId) {
                        switchToSession(sessionId, statusText)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 新建终端会话 */
    private fun createNewSession(statusText: TextView) {
        // 断开当前会话的监听
        TerminalSession.disconnect()
        // 清空终端显示
        terminalView.screen.resetScreen()
        terminalView.setText("")
        statusText.text = "启动中…"
        startPtyShell(statusText)
    }

    /** 切换到另一个会话 */
    private fun switchToSession(sessionId: Int, statusText: TextView) {
        TerminalSession.disconnect()
        terminalView.screen.resetScreen()
        terminalView.setText("")

        if (TerminalSession.switchTo(sessionId)) {
            statusText.text = "会话 $sessionId"
            val replay = TerminalSession.reconnect(
                onOutput = { bytes, len ->
                    terminalView.appendOutput(bytes, len)
                },
                onExit = { code ->
                    handler.post {
                        val msg = "\n[进程退出，code=$code]\n"
                        terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                        Toast.makeText(this, "Shell 已退出", Toast.LENGTH_SHORT).show()
                        statusText.text = "已退出"
                    }
                }
            )
            if (replay != null && replay.isNotEmpty()) {
                terminalView.appendOutput(replay.toByteArray(), replay.toByteArray().size)
            }
            updateWindowSize()
        }
    }

    /** 退出终端对话框 */
    private fun showExitDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("退出终端")
            .setMessage("退出终端页面（会话保持后台运行）还是终止当前会话？")
            .setPositiveButton("仅退出页面") { _, _ ->
                finish()
            }
            .setNegativeButton("终止会话") { _, _ ->
                TerminalSession.killSession()
                Toast.makeText(this, "会话已终止", Toast.LENGTH_SHORT).show()
                // 如果还有其他会话，切换过去；否则关闭页面
                if (TerminalSession.isRunning()) {
                    statusTextRef?.let { switchToSession(TerminalSession.activeSessionId, it) }
                } else {
                    finish()
                }
            }
            .setNeutralButton("取消", null)
            .show()
    }

    // statusText 引用（供 showExitDialog 使用）
    private var statusTextRef: TextView? = null

    // 边缘滑动退出容器与首次提示 Toast 已提取至 [SwipeExitFrameLayout]。
}
