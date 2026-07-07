package com.nous.hermes.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 查看并编辑 Hermes Agent 的 SOUL.md（Agent 人格定义文件）。
 *
 * SOUL.md 位于 proot rootfs 内的 `~/.hermes/SOUL.md`（即 rootfs 视角的
 * `/root/home/.hermes/SOUL.md`）。在 Android app 侧，对应的 host 路径是
 * `filesDir/home/.hermes/SOUL.md`（与 BootstrapManager.Paths.homeDir 一致，
 * home 目录通过 proot bind 挂载到 rootfs 的 /root/home）。
 *
 * 读写直接走 java.io.File（host 路径），无需经 proot exec，简单可靠：
 *   - 读取：File(filesDir, "home/.hermes/SOUL.md").readText()
 *   - 写入：File(...).writeText(content)
 *
 * UI 纯代码构建（参考 SubSettingsActivity 模式）：标题栏（返回 + 标题 + 保存）、
 * 提示文字、多行 monospace 编辑框、字符计数、加载 ProgressBar。
 *
 * 返回拦截：若有未保存修改，弹出确认对话框（放弃 / 继续编辑）。
 */
class SoulEditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SoulEditorActivity"
        // SOUL.md 的 host 相对路径：filesDir/home/.hermes/SOUL.md
        private const val SOUL_RELATIVE_PATH = "home/.hermes/SOUL.md"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var progressBar: ProgressBar
    private lateinit var editor: EditText
    private lateinit var charCountText: TextView
    private lateinit var saveBtn: TextView

    /** 上一次保存（或加载）到磁盘的内容，用于判断编辑器是否存在未保存修改。 */
    private var loadedContent: String = ""

    /** 是否正在执行保存，避免重复点击。 */
    private var saving = false

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        // 拦截返回键：有未保存修改时弹确认框，否则直接退出。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    showDiscardConfirmDialog()
                } else {
                    finish()
                }
            }
        })

        loadSoulFile()
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF020617.toInt())
        }

        // --- 标题栏：返回 ‹ + 标题 + 保存按钮 ---
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(
                (8 * density).toInt(), (10 * density).toInt(),
                (8 * density).toInt(), (10 * density).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
            elevation = 4 * density
        }
        val backBtn = TextView(this).apply {
            text = "‹"
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(
                (12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            background = getClickableBackground()
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val titleText = TextView(this).apply {
            text = "SOUL.md 编辑器"
            setTextColor(0xFFe2e8f0.toInt())
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        }
        saveBtn = TextView(this).apply {
            text = "保存"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(
                (18 * density).toInt(), (8 * density).toInt(),
                (18 * density).toInt(), (8 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            background = getSaveButtonBackground()
            setOnClickListener { saveSoulFile() }
        }
        titleBar.addView(backBtn)
        titleBar.addView(titleText)
        titleBar.addView(saveBtn)
        root.addView(titleBar)

        // --- 内容区：FrameLayout 叠加（编辑区 + 居中加载进度） ---
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // 编辑区纵向容器：提示 / 编辑框 / 字符计数
        val editorColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF020617.toInt())
        }

        // 提示文字
        val hint = TextView(this).apply {
            text = "Agent 人格定义文件。修改后会立即影响 Agent 的行为。"
            setTextColor(0xFF94a3b8.toInt())
            textSize = 12f
            setPadding(
                (20 * density).toInt(), (14 * density).toInt(),
                (20 * density).toInt(), (8 * density).toInt()
            )
        }
        editorColumn.addView(hint)

        // 多行 monospace 编辑框
        editor = EditText(this).apply {
            setTextColor(0xFFe2e8f0.toInt())
            setHintTextColor(0xFF64748b.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP
            background = getEditorBackground()
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt()
            )
            // 多行 + 不自动换行（横向滚动）+ 关闭拼写建议，贴近代码编辑器体验
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                marginStart = (20 * density).toInt()
                marginEnd = (20 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            hint = "# SOUL.md\n# 在这里定义 Agent 的人格、偏好与行为准则…"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateCharCount()
                }
            })
        }
        editorColumn.addView(editor)

        // 字符计数
        charCountText = TextView(this).apply {
            text = "0 字符"
            setTextColor(0xFF94a3b8.toInt())
            textSize = 12f
            gravity = Gravity.END
            setPadding(
                (20 * density).toInt(), (4 * density).toInt(),
                (20 * density).toInt(), (14 * density).toInt()
            )
        }
        editorColumn.addView(charCountText)

        contentFrame.addView(editorColumn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 加载进度（居中覆盖）
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        contentFrame.addView(progressBar)

        root.addView(contentFrame)
        setContentView(root)
    }

    // ── 加载 / 保存 ──────────────────────────────────────────────────────────

    private fun soulFile(): File = File(filesDir, SOUL_RELATIVE_PATH)

    private fun loadSoulFile() {
        setLoading(true)
        val file = soulFile()
        Thread {
            val content = try {
                if (file.exists()) file.readText() else ""
            } catch (e: Exception) {
                Log.e(TAG, "read SOUL.md failed", e)
                handler.post {
                    Toast.makeText(this, "读取失败：${e.message}", Toast.LENGTH_LONG).show()
                }
                ""
            }
            handler.post {
                editor.setText(content)
                editor.setSelection(content.length)
                loadedContent = content
                updateCharCount()
                setLoading(false)
            }
        }.start()
    }

    private fun saveSoulFile() {
        if (saving) return
        saving = true
        saveBtn.isEnabled = false
        saveBtn.alpha = 0.5f
        val content = editor.text?.toString() ?: ""
        val file = soulFile()
        Thread {
            var ok = true
            var errMsg: String? = null
            try {
                // 确保父目录存在（首次安装或目录缺失时），然后写入。
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()
                file.writeText(content)
            } catch (e: Exception) {
                Log.e(TAG, "write SOUL.md failed", e)
                ok = false
                errMsg = e.message
            }
            handler.post {
                saving = false
                saveBtn.isEnabled = true
                saveBtn.alpha = 1f
                if (ok) {
                    loadedContent = content
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "保存失败：${errMsg ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── 状态辅助 ─────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        editor.visibility = if (loading) View.GONE else View.VISIBLE
        saveBtn.isEnabled = !loading
        saveBtn.alpha = if (loading) 0.5f else 1f
    }

    private fun updateCharCount() {
        val n = editor.text?.length ?: 0
        charCountText.text = "$n 字符"
    }

    private fun hasUnsavedChanges(): Boolean {
        return editor.text?.toString() != loadedContent
    }

    private fun showDiscardConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("放弃修改？")
            .setMessage("当前内容尚未保存，退出将丢失修改。")
            .setPositiveButton("放弃修改") { _, _ -> finish() }
            .setNegativeButton("继续编辑", null)
            .setCancelable(true)
            .show()
    }

    // ── Drawable 工厂（纯代码，避免依赖 XML） ───────────────────────────────

    private fun getClickableBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1e293b.toInt())
                    cornerRadius = 12 * density
                }
            )
            addState(
                intArrayOf(),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x001e293b)
                    cornerRadius = 12 * density
                }
            )
        }
    }

    private fun getSaveButtonBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF6366f1.toInt())
                    cornerRadius = 10 * density
                }
            )
            addState(
                intArrayOf(),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF818cf8.toInt())
                    cornerRadius = 10 * density
                }
            )
        }
    }

    private fun getEditorBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0f172a.toInt())
            cornerRadius = 12 * density
            setStroke(1, 0xFF334155.toInt())
        }
    }
}
