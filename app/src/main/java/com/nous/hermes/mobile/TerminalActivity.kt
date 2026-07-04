package com.nous.hermes.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置终端 — 在 proot rootfs 里运行交互式 bash shell。
 *
 * 用户可以直接在终端里运行 hermes、hermes-web-ui、apt 等命令，
 * 无需安装 Termux 或用 adb shell。
 *
 * 实现：ProcessBuilder 启动 proot + bash，通过 stdin/stdout 交互。
 * 界面：ScrollView + TextView 显示输出，EditText 输入命令。
 */
class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalActivity"
    }

    private lateinit var outputView: TextView
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private val outputBuffer = StringBuilder()
    @Volatile private var isRunning = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0f172a.toInt())
        }

        // 顶部标题栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(16, 12, 16, 12)
        }
        val titleText = TextView(this).apply {
            text = "Hermes Terminal"
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val exitButton = Button(this).apply {
            text = "✕"
            setOnClickListener { finish() }
            setBackgroundColor(0xFF334155.toInt())
            setTextColor(0xFFe2e8f0.toInt())
        }
        titleBar.addView(titleText)
        titleBar.addView(exitButton)
        container.addView(titleBar)

        // 终端输出区
        scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF0f172a.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        outputView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF4ade80.toInt())
            textSize = 12f
            textIsSelectable = true
            setPadding(12, 8, 12, 8)
            text = "正在启动终端…\n"
        }
        scrollView.addView(outputView)
        container.addView(scrollView)

        // 进度条
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        container.addView(progressBar)

        // 输入栏
        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(8, 4, 8, 4)
        }
        inputField = EditText(this).apply {
            hint = "输入命令…"
            setHintTextColor(0xFF64748b.toInt())
            setTextColor(0xFFe2e8f0.toInt())
            backgroundColor = 0x00000000
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val sendButton = Button(this).apply {
            text = "发送"
            setOnClickListener { sendCommand() }
        }
        inputContainer.addView(inputField)
        inputContainer.addView(sendButton)
        container.addView(inputContainer)

        setContentView(container)

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else false
        }

        startShell()
    }

    /**
     * 启动 proot + bash 交互式 shell。
     */
    private fun startShell() {
        Thread {
            try {
                val pm = ProcessManager(this)
                // 构建 gateway 模式命令（login session，适合交互式）
                // 激活 venv 并设置 PATH，让 hermes 命令可用
                val shellCmd = "cd /root/home/hermes-agent 2>/dev/null; " +
                    "export PATH=/root/home/hermes-agent/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "export TERM=xterm-256color; " +
                    "echo '═══════════════════════════════════════════'; " +
                    "echo '  Hermes Agent 终端 — proot + Ubuntu 24.04'; " +
                    "echo '  输入 hermes --help 查看可用命令'; " +
                    "echo '  输入 exit 退出 shell'; " +
                    "echo '═══════════════════════════════════════════'; " +
                    "exec bash --login"

                val cmd = pm.buildGatewayCommand(shellCmd)
                val env = pm.prootEnvPublic()

                val pb = ProcessBuilder(cmd)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc
                isRunning = true

                // 读取输出
                val reader = proc.inputStream.bufferedReader()
                val buf = CharArray(4096)
                var n = reader.read(buf)
                while (n > 0 && isRunning) {
                    val chunk = String(buf, 0, n)
                    handler.post {
                        outputBuffer.append(chunk)
                        outputView.text = outputBuffer.toString()
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                    n = reader.read(buf)
                }

                if (isRunning) {
                    val exitCode = proc.waitFor()
                    handler.post {
                        outputBuffer.append("\n[进程退出，code=$exitCode]\n")
                        outputView.text = outputBuffer.toString()
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                        Toast.makeText(this, "Shell 已退出", Toast.LENGTH_SHORT).show()
                    }
                }
                isRunning = false
            } catch (e: Exception) {
                Log.e(TAG, "Shell start failed", e)
                handler.post {
                    outputBuffer.append("\n[错误: ${e.message}]\n")
                    outputView.text = outputBuffer.toString()
                    Toast.makeText(this, "终端启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * 发送输入命令到 shell。
     */
    private fun sendCommand() {
        val text = inputField.text.toString()
        if (text.isEmpty()) return
        try {
            val os = process?.outputStream ?: return
            os.write((text + "\n").toByteArray())
            os.flush()
            inputField.text.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
    }
}
