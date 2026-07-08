package com.nous.hermes.mobile

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 终端会话管理器 — 支持多会话，跨 Activity 实例保持 PTY 会话存活。
 *
 * - 退出终端页面时，PTY 进程不会被杀死，只停止读取循环
 * - 重新打开终端时，重新连接到现有会话并恢复读取
 * - 支持创建多个独立会话，可切换
 * - 用户在 shell 中输入 exit 或手动 kill 才会真正终止进程
 */
object TerminalSession {

    private const val TAG = "TerminalSession"

    /** 单个会话 */
    data class Session(
        val id: Int,
        var masterFd: Int,
        var pid: Int,
        @Volatile var isAlive: Boolean,
        val pendingBuffer: StringBuilder = StringBuilder(),
        @Volatile var outputListener: ((ByteArray, Int) -> Unit)? = null,
        @Volatile var exitListener: ((Int) -> Unit)? = null,
        var readThread: Thread? = null,
    )

    /** 所有会话列表 */
    private val sessions = CopyOnWriteArrayList<Session>()

    /** 当前活跃会话 ID */
    @Volatile
    var activeSessionId: Int = -1
        private set

    private var nextSessionId = 1
    private const val MAX_PENDING = 50000
    private const val READ_BUFFER_SIZE = 8192
    private val PROOT_WARN_PATTERN = Regex("proot warning: can't sanitize binding")

    // ── 兼容旧 API（直接代理到当前会话） ──

    val masterFd: Int get() = activeSession?.masterFd ?: -1
    val pid: Int get() = activeSession?.pid ?: -1
    val isAlive: Boolean get() = activeSession?.isAlive ?: false

    private val activeSession: Session?
        get() = sessions.find { it.id == activeSessionId && it.isAlive }

    fun isRunning(): Boolean = activeSession != null

    /** 获取所有活跃会话 */
    fun getActiveSessions(): List<Session> = sessions.filter { it.isAlive }

    /** 获取会话列表信息（用于 UI 显示） */
    fun getSessionList(): List<Pair<Int, String>> {
        return sessions.map { s ->
            s.id to "会话 ${s.id}" + if (s.id == activeSessionId) " (当前)" else ""
        }
    }

    /**
     * 初始化新的 PTY 会话。
     */
    fun initSession(fd: Int, processId: Int): Session {
        val session = Session(
            id = nextSessionId++,
            masterFd = fd,
            pid = processId,
            isAlive = true,
        )
        sessions.add(session)
        activeSessionId = session.id
        startReadLoop(session)
        Log.i(TAG, "Session ${session.id} initialized: pid=${session.pid} fd=${session.masterFd}")
        return session
    }

    /**
     * 切换到指定会话。
     */
    fun switchTo(sessionId: Int): Boolean {
        val session = sessions.find { it.id == sessionId && it.isAlive } ?: return false
        activeSessionId = sessionId
        Log.i(TAG, "Switched to session $sessionId")
        return true
    }

    /**
     * 重新连接到当前活跃会话，注册输出监听器。
     * 返回缓存的待回放输出（可能为空）。
     */
    fun reconnect(onOutput: (ByteArray, Int) -> Unit, onExit: (Int) -> Unit): String? {
        val session = activeSession ?: return null
        session.outputListener = onOutput
        session.exitListener = onExit
        val buffered = if (session.pendingBuffer.isNotEmpty()) session.pendingBuffer.toString() else null
        session.pendingBuffer.clear()
        Log.i(TAG, "Reconnected to session ${session.id}, replaying ${buffered?.length ?: 0} chars")
        return buffered
    }

    /**
     * 断开连接（Activity 退出时调用），停止监听但保持进程存活。
     */
    fun disconnect() {
        val session = activeSession ?: return
        session.outputListener = null
        session.exitListener = null
        Log.i(TAG, "Disconnected from session ${session.id} (process kept alive)")
    }

    /**
     * 彻底终止指定会话。
     */
    fun killSession(sessionId: Int = activeSessionId) {
        val session = sessions.find { it.id == sessionId } ?: return
        session.isAlive = false
        session.outputListener = null
        session.exitListener = null
        session.readThread?.interrupt()
        if (session.pid > 0) {
            PtyNative.killProcess(session.pid, 15)
            Thread {
                Thread.sleep(500)
                PtyNative.killProcess(session.pid, 9)
            }.start()
        }
        if (session.masterFd >= 0) {
            PtyNative.close(session.masterFd)
            session.masterFd = -1
        }
        session.pendingBuffer.clear()
        sessions.remove(session)
        // 如果杀的是当前会话，切换到另一个活跃会话
        if (activeSessionId == sessionId) {
            activeSessionId = sessions.find { it.isAlive }?.id ?: -1
        }
        Log.i(TAG, "Session $sessionId killed")
    }

    /**
     * 终止所有会话。
     */
    fun killAllSessions() {
        for (s in sessions.toList()) {
            killSession(s.id)
        }
        activeSessionId = -1
    }

    fun write(data: ByteArray) {
        val session = activeSession ?: return
        if (session.masterFd >= 0) {
            PtyNative.write(session.masterFd, data)
        }
    }

    fun setWindowSize(rows: Int, cols: Int) {
        for (s in sessions) {
            if (s.isAlive && s.masterFd >= 0) {
                PtyNative.setWindowSize(s.masterFd, rows, cols)
            }
        }
    }

    private fun startReadLoop(session: Session) {
        session.readThread = Thread {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (session.isAlive) {
                val n = PtyNative.read(session.masterFd, buffer)
                if (n > 0) {
                    val output = String(buffer, 0, n, Charsets.UTF_8)
                    val filtered = filterProotWarnings(output)
                    if (filtered.isNotEmpty()) {
                        val filteredBytes = filtered.toByteArray(Charsets.UTF_8)
                        val listener = session.outputListener
                        if (listener != null) {
                            listener(filteredBytes, filteredBytes.size)
                        } else {
                            synchronized(session.pendingBuffer) {
                                if (session.pendingBuffer.length + filtered.length > MAX_PENDING) {
                                    session.pendingBuffer.delete(0, session.pendingBuffer.length - MAX_PENDING + filtered.length)
                                }
                                session.pendingBuffer.append(filtered)
                            }
                        }
                    }
                } else if (n == 0) {
                    break
                } else {
                    if (session.isAlive) Log.e(TAG, "PTY read error: $n")
                    break
                }
            }

            session.isAlive = false
            val exitCode = if (session.pid > 0) PtyNative.waitFor(session.pid) else -1
            Log.i(TAG, "Session ${session.id} ended: exitCode=$exitCode")
            session.exitListener?.invoke(exitCode)
            sessions.remove(session)
            if (activeSessionId == session.id) {
                activeSessionId = sessions.find { it.isAlive }?.id ?: -1
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun filterProotWarnings(input: String): String {
        if (!input.contains("proot warning")) return input
        val lines = input.split("\n")
        val result = lines.filter { line ->
            !PROOT_WARN_PATTERN.containsMatchIn(line)
        }
        return result.joinToString("\n")
    }
}
