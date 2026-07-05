package com.nous.hermes.mobile

/**
 * JNI bridge to native PTY (pseudo-terminal) functions.
 *
 * Provides a real TTY for interactive programs (hermes setup, vim, htop, etc.)
 * by using posix_openpt + fork + exec, instead of ProcessBuilder pipes.
 *
 * Native code: app/src/main/cpp/pty.c
 * Built via CMake (see app/build.gradle.kts → externalNativeBuild).
 *
 * Usage:
 *   val result = PtyNative.createSubprocess(cmdArray, envArray)
 *   val masterFd = result[0]
 *   val pid = result[1]
 *   PtyNative.setWindowSize(masterFd, rows, cols)
 *   // Read thread: PtyNative.read(masterFd, buffer)
 *   // Write:       PtyNative.write(masterFd, data)
 *   // On exit:     PtyNative.waitFor(pid)
 *   // Cleanup:     PtyNative.close(masterFd)
 */
object PtyNative {

    init {
        System.loadLibrary("hermespty")
    }

    /**
     * Create a PTY pair, fork, and exec the given command.
     *
     * @param cmd Array of command + arguments (e.g. ["/path/to/proot", "--rootfs=...", "bash"])
     * @param env Array of "KEY=VALUE" environment variable strings
     * @return int[2] = {masterFd, pid}, or null on failure
     */
    fun createSubprocess(cmd: Array<String>, env: Array<String>): IntArray? {
        return nativeCreateSubprocess(cmd, env)
    }

    /** Write bytes to the PTY master fd. Returns bytes written, or -1 on error. */
    fun write(fd: Int, data: ByteArray): Int {
        return nativeWrite(fd, data)
    }

    /**
     * Read bytes from the PTY master fd into the buffer.
     * Returns bytes read (0 = EOF, -1 = error).
     * Blocks until data is available.
     */
    fun read(fd: Int, buffer: ByteArray): Int {
        return nativeRead(fd, buffer)
    }

    /** Set the PTY window size (triggers SIGWINCH in child process). */
    fun setWindowSize(fd: Int, rows: Int, cols: Int) {
        nativeSetWindowSize(fd, rows, cols)
    }

    /** Wait for child process to exit. Returns exit code. */
    fun waitFor(pid: Int): Int {
        return nativeWaitFor(pid)
    }

    /** Close the PTY master fd. */
    fun close(fd: Int) {
        nativeClose(fd)
    }

    /** Send a signal to the child process (e.g. SIGTERM=15, SIGKILL=9). */
    fun killProcess(pid: Int, signal: Int) {
        nativeKillProcess(pid, signal)
    }

    // --- JNI declarations ---

    @JvmStatic private external fun nativeCreateSubprocess(
        cmd: Array<String>, env: Array<String>
    ): IntArray?

    @JvmStatic private external fun nativeWrite(fd: Int, data: ByteArray): Int

    @JvmStatic private external fun nativeRead(fd: Int, buffer: ByteArray): Int

    @JvmStatic private external fun nativeSetWindowSize(fd: Int, rows: Int, cols: Int)

    @JvmStatic private external fun nativeWaitFor(pid: Int): Int

    @JvmStatic private external fun nativeClose(fd: Int)

    @JvmStatic private external fun nativeKillProcess(pid: Int, signal: Int)
}
