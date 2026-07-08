package com.nous.hermes.mobile

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 终端会话单例 — 跨 Activity 实例保持 PTY 会话存活。
 *
 * 退出终端页面时，PTY 进程不会被杀死，只停止读取循环。
 * 重新打开终端时，重新连接到现有会话并恢复读取。
 *
 * 用户在 shell 中输入 exit 或手动 kill 才会真正终止进程。
 */
object TerminalSession {

    private const val TAG = "TerminalSession"

    @Volatile var masterFd: Int = -1
        private set
    @Volatile var pid: Int = -1
        private set
    @Volatile var isAlive: Boolean = false
        private set

    /** 缓冲退出期间产生的输出，重新连接后回放 */
    private val pendingBuffer = StringBuilder()
    private val MAX_PENDING = 50000  // 最多缓存 50KB

    /** 输出监听器（Activity 重新连接后注册） */
    @Volatile
    private var outputListener: ((ByteArray, Int) -> Unit)? = null

    /** 退出监听器 */
    @Volatile
    private var exitListener: ((Int) -> Unit)? = null

    /** 读取线程 */
    private var readThread: Thread? = null

    private const val READ_BUFFER_SIZE = 8192

    /** proot 警告过滤模式 */
    private val PROOT_WARN_PATTERN = Regex("proot warning: can't sanitize binding")

    fun isRunning(): Boolean = isAlive && masterFd >= 0

    /**
     * 初始化新的 PTY 会话。如果已有活跃会话则直接返回。
     */
    fun initSession(fd: Int, processId: Int) {
        if (isAlive) {
            Log.w(TAG, "Session already alive, ignoring init")
            PtyNative.close(fd)  // 关闭多余的 fd
            return
        }
        masterFd = fd
        pid = processId
        isAlive = true
        pendingBuffer.clear()
        startReadLoop()
        Log.i(TAG, "Session initialized: pid=$pid masterFd=$masterFd")
    }

    /**
     * 重新连接到现有会话，注册输出监听器。
     * 返回缓存的待回放输出（可能为空）。
     */
    fun reconnect(onOutput: (ByteArray, Int) -> Unit, onExit: (Int) -> Unit): String? {
        if (!isAlive) return null
        outputListener = onOutput
        exitListener = onExit
        val buffered = if (pendingBuffer.isNotEmpty()) pendingBuffer.toString() else null
        pendingBuffer.clear()
        Log.i(TAG, "Reconnected to session, replaying ${buffered?.length ?: 0} chars")
        return buffered
    }

    /**
     * 断开连接（Activity 退出时调用），停止监听但保持进程存活。
     */
    fun disconnect() {
        outputListener = null
        exitListener = null
        Log.i(TAG, "Disconnected (process kept alive)")
    }

    /**
     * 彻底终止会话。
     */
    fun killSession() {
        isAlive = false
        outputListener = null
        exitListener = null
        readThread?.interrupt()
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
        pid = -1
        pendingBuffer.clear()
        Log.i(TAG, "Session killed")
    }

    fun write(data: ByteArray) {
        if (masterFd >= 0) {
            PtyNative.write(masterFd, data)
        }
    }

    fun setWindowSize(rows: Int, cols: Int) {
        if (masterFd >= 0) {
            PtyNative.setWindowSize(masterFd, rows, cols)
        }
    }

    private fun startReadLoop() {
        readThread = Thread {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (isAlive) {
                val n = PtyNative.read(masterFd, buffer)
                if (n > 0) {
                    // 过滤 proot 警告
                    val output = String(buffer, 0, n, Charsets.UTF_8)
                    val filtered = filterProotWarnings(output)
                    if (filtered.isNotEmpty()) {
                        val filteredBytes = filtered.toByteArray(Charsets.UTF_8)
                        val listener = outputListener
                        if (listener != null) {
                            listener(filteredBytes, filteredBytes.size)
                        } else {
                            // 没有监听器，缓存输出
                            synchronized(pendingBuffer) {
                                if (pendingBuffer.length + filtered.length > MAX_PENDING) {
                                    pendingBuffer.delete(0, pendingBuffer.length - MAX_PENDING + filtered.length)
                                }
                                pendingBuffer.append(filtered)
                            }
                        }
                    }
                } else if (n == 0) {
                    // EOF
                    break
                } else {
                    if (isAlive) Log.e(TAG, "PTY read error: $n")
                    break
                }
            }

            isAlive = false
            val exitCode = if (pid > 0) PtyNative.waitFor(pid) else -1
            Log.i(TAG, "Session ended: exitCode=$exitCode")
            exitListener?.invoke(exitCode)
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * 过滤 proot 警告行（can't sanitize binding 等）。
     */
    private fun filterProotWarnings(input: String): String {
        if (!input.contains("proot warning")) return input

        val lines = input.split("\n")
        val result = lines.filter { line ->
            !PROOT_WARN_PATTERN.containsMatchIn(line)
        }
        return result.joinToString("\n")
    }
}
