package com.nous.hermes.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * - 底部功能键栏（ESC、Tab、Ctrl、方向键）适配软键盘
 * - 左边缘滑动退出
 *
 * 架构：
 * - PtyNative (JNI) → posix_openpt + fork + exec → proot + bash
 * - TerminalView → 显示 PTY 输出，捕获键盘输入发送到 PTY
 */
class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalActivity"
        private const val READ_BUFFER_SIZE = 8192
        private const val PREF_SWIPE_HINT_SHOWN = "terminal_swipe_hint_shown"
    }

    private lateinit var terminalView: TerminalView
    private lateinit var ctrlButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var masterFd: Int = -1
    private var pid: Int = -1
    @Volatile private var isRunning = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮 + 全屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val density = resources.displayMetrics.density

        // === 根容器：支持左边缘滑动退出 ===
        val container = SwipeExitContainer(this)

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
        titleBar.addView(titleText)
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
                if (masterFd >= 0) {
                    PtyNative.write(masterFd, bytes)
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

        // ESC 键
        val escBtn = makeKeyButton("ESC").apply {
            setOnClickListener { terminalView.sendByteToPty(0x1B) }
        }
        // Tab 键
        val tabBtn = makeKeyButton("TAB").apply {
            setOnClickListener { terminalView.sendByteToPty(0x09) }
        }
        // Ctrl 键（切换）
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
        // 方向键 ←
        val leftBtn = makeKeyButton("←", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[D") }
        }
        // 方向键 ↑
        val upBtn = makeKeyButton("↑", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[A") }
        }
        // 方向键 ↓
        val downBtn = makeKeyButton("↓", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[B") }
        }
        // 方向键 →
        val rightBtn = makeKeyButton("→", (36 * density).toInt()).apply {
            setOnClickListener { terminalView.sendToPty("\u001B[C") }
        }

        keyBar.addView(escBtn)
        keyBar.addView(tabBtn)
        keyBar.addView(ctrlButton)
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

        // 首次提示
        showSwipeHint()

        // 启动终端
        startPtyShell()
    }

    /**
     * 启动 PTY shell：通过 JNI 创建伪终端，fork + exec proot + bash。
     */
    private fun startPtyShell() {
        Thread {
            try {
                val paths = BootstrapManager.getPaths(this)
                val pm = ProcessManager(this, paths.filesDir, paths.nativeLibDir)

                // 构建交互式 shell 命令（与之前一致，但现在通过 PTY 运行）
                val shellCmd = (
                    "cd /root/home/hermes-agent 2>/dev/null; " +
                    "export PATH=/root/home/hermes-agent/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "export TERM=xterm-256color; " +
                    "echo '═══════════════════════════════════════════'; " +
                    "echo '  Hermes Agent 终端 — proot + Ubuntu 24.04'; " +
                    "echo '  输入 hermes --help 查看可用命令'; " +
                    "echo '  输入 exit 退出 shell'; " +
                    "echo '═══════════════════════════════════════════'; " +
                    "exec bash --login"
                )

                val cmd = pm.buildGatewayCommand(shellCmd).toTypedArray()
                val env = pm.prootEnvPublic().map { (k, v) -> "$k=$v" }.toTypedArray()

                Log.i(TAG, "Starting PTY shell: ${cmd.firstOrNull()}")

                // 创建 PTY 子进程
                val result = PtyNative.createSubprocess(cmd, env)
                if (result == null) {
                    handler.post {
                        val msg = "[错误: 无法创建 PTY]\n"
                        terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                        Toast.makeText(this, "终端启动失败", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                masterFd = result[0]
                pid = result[1]
                isRunning = true

                Log.i(TAG, "PTY shell started: pid=$pid masterFd=$masterFd")

                // 设置初始窗口大小
                updateWindowSize()

                // 读取循环
                val buffer = ByteArray(READ_BUFFER_SIZE)
                while (isRunning) {
                    val n = PtyNative.read(masterFd, buffer)
                    if (n > 0) {
                        terminalView.appendOutput(buffer, n)
                    } else if (n == 0) {
                        // EOF
                        break
                    } else {
                        // Error
                        if (isRunning) {
                            Log.e(TAG, "PTY read error: $n")
                        }
                        break
                    }
                }

                if (isRunning) {
                    val exitCode = PtyNative.waitFor(pid)
                    handler.post {
                        val msg = "\n[进程退出，code=$exitCode]\n"
                        terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                        Toast.makeText(this, "Shell 已退出", Toast.LENGTH_SHORT).show()
                    }
                }
                isRunning = false

            } catch (e: Exception) {
                Log.e(TAG, "PTY shell failed", e)
                handler.post {
                    val msg = "\n[错误: ${e.message}]\n"
                    terminalView.appendOutput(msg.toByteArray(), msg.toByteArray().size)
                    Toast.makeText(this, "终端启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * 根据终端视图大小计算并设置 PTY 窗口大小（行×列）。
     */
    private fun updateWindowSize() {
        if (masterFd < 0) return
        terminalView.post {
            val paint = terminalView.paint
            val charWidth = if (paint.measureText("M") > 0) paint.measureText("M") else 7f
            val charHeight = maxOf(terminalView.lineHeight, 1)
            val cols = maxOf((terminalView.width / charWidth).toInt(), 20)
            val rows = maxOf((terminalView.height / charHeight).toInt(), 5)
            Log.i(TAG, "Window size: ${rows}r x ${cols}c")
            PtyNative.setWindowSize(masterFd, rows, cols)
        }
    }

    /**
     * 首次进入提示滑动退出手势（仅显示一次）。
     */
    private fun showSwipeHint() {
        val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_SWIPE_HINT_SHOWN, false)) {
            Toast.makeText(
                this,
                "从屏幕左边缘向右滑动可退出终端",
                Toast.LENGTH_LONG
            ).show()
            prefs.edit().putBoolean(PREF_SWIPE_HINT_SHOWN, true).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // 关闭 PTY 并终止子进程
        if (pid > 0) {
            PtyNative.killProcess(pid, 15)  // SIGTERM
            Thread {
                Thread.sleep(500)
                PtyNative.killProcess(pid, 9)  // SIGKILL
            }.start()
        }
        if (masterFd >= 0) {
            PtyNative.close(masterFd)
            masterFd = -1
        }
    }

    /**
     * 自定义 FrameLayout：左边缘滑动退出（与 ChatActivity 相同的实现）。
     */
    inner class SwipeExitContainer(context: Context) : FrameLayout(context) {

        private val density = resources.displayMetrics.density
        private val edgeWidth = 20 * density
        private val triggerThreshold = 100 * density

        private var startX = 0f
        private var startY = 0f
        private var fromEdge = false
        private var swiping = false

        private val hintView = TextView(context).apply {
            text = "←  退出"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
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
                        if (dx > 16 * density && dx > ady * 1.5f) {
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
                    hintView.translationX = dx
                    hintView.alpha = (dx / triggerThreshold).coerceIn(0.3f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - startX
                    if (dx > triggerThreshold) {
                        hintView.animate().alpha(0f).setDuration(100)
                            .withEndAction { hintView.visibility = View.GONE }.start()
                        finish()
                    } else {
                        hintView.animate().translationX(0f).alpha(0f).setDuration(150)
                            .withEndAction { hintView.visibility = View.GONE }.start()
                    }
                    swiping = false
                    fromEdge = false
                }
            }
            return true
        }
    }
}
