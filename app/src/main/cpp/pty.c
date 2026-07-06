/*
 * PTY (Pseudo-Terminal) JNI bridge for Hermes Android terminal.
 *
 * Provides forkpty-like functionality using POSIX PTY APIs:
 *   posix_openpt → grantpt → unlockpt → ptsname → fork → exec
 *
 * This allows interactive TUI programs (hermes setup, vim, htop, etc.)
 * to detect a real TTY and use raw mode, unlike ProcessBuilder pipes.
 *
 * Reference: Termux's termux-bootstrap/native.c uses the same pattern.
 */

#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <android/log.h>

#define TAG "PtyNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Create a PTY pair, fork, and exec the given command in the child.
 *
 * The child gets the PTY slave as stdin/stdout/stderr + controlling terminal,
 * so isatty() returns true and termios works.
 *
 * @param cmdArray  argv[0..n-1] for execvp
 * @param envArray  environment variables as "KEY=VALUE" strings
 * @return int[2] = {masterFd, pid}, or NULL on failure
 */
JNIEXPORT jintArray JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeCreateSubprocess(
    JNIEnv *env, jclass cls,
    jobjectArray cmdArray, jobjectArray envArray)
{
    /* --- Phase 1: Prepare C strings from JNI (before fork) --- */
    jsize cmdLen = (*env)->GetArrayLength(env, cmdArray);
    if (cmdLen == 0) {
        LOGE("Empty command array");
        return NULL;
    }
    char **argv = (char **)malloc((cmdLen + 1) * sizeof(char *));
    if (!argv) return NULL;
    jsize i;
    for (i = 0; i < cmdLen; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, cmdArray, i);
        const char *cstr = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cstr);
        (*env)->ReleaseStringUTFChars(env, s, cstr);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[cmdLen] = NULL;

    jsize envLen = envArray ? (*env)->GetArrayLength(env, envArray) : 0;
    char **envp = NULL;
    if (envLen > 0) {
        envp = (char **)malloc((envLen + 1) * sizeof(char *));
        if (envp) {
            for (i = 0; i < envLen; i++) {
                jstring s = (jstring)(*env)->GetObjectArrayElement(env, envArray, i);
                const char *cstr = (*env)->GetStringUTFChars(env, s, NULL);
                envp[i] = strdup(cstr);
                (*env)->ReleaseStringUTFChars(env, s, cstr);
                (*env)->DeleteLocalRef(env, s);
            }
            envp[envLen] = NULL;
        }
    }

    /* --- Phase 2: Allocate PTY master --- */
    int masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    if (masterFd < 0) {
        LOGE("posix_openpt failed");
        goto fail;
    }
    if (grantpt(masterFd) < 0) {
        LOGE("grantpt failed");
        close(masterFd);
        goto fail;
    }
    if (unlockpt(masterFd) < 0) {
        LOGE("unlockpt failed");
        close(masterFd);
        goto fail;
    }
    char *slaveName = ptsname(masterFd);
    if (!slaveName) {
        LOGE("ptsname failed");
        close(masterFd);
        goto fail;
    }

    /* --- Phase 3: Fork --- */
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed");
        close(masterFd);
        goto fail;
    }

    if (pid == 0) {
        /* === Child process === */
        close(masterFd);

        int slaveFd = open(slaveName, O_RDWR);
        if (slaveFd < 0) {
            _exit(127);
        }

        /* Create new session and set slave as controlling terminal */
        setsid();
        ioctl(slaveFd, TIOCSCTTY, 0);

        /* Redirect stdio to slave PTY */
        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);
        if (slaveFd > 2) close(slaveFd);

        /* Set environment */
        if (envp) {
            for (i = 0; envp[i]; i++) {
                putenv(envp[i]);
            }
        }

        /* Exec — never returns on success */
        execvp(argv[0], argv);

        /* Exec failed */
        const char *msg = "exec failed\n";
        write(STDERR_FILENO, msg, strlen(msg));
        _exit(127);
    }

    /* === Parent process === */
    /* Free prepared strings (child has its own copy after fork) */
    for (i = 0; i < cmdLen; i++) free(argv[i]);
    free(argv);
    if (envp) {
        for (i = 0; i < envLen; i++) free(envp[i]);
        free(envp);
    }

    LOGI("Subprocess created: pid=%d masterFd=%d", pid, masterFd);

    /* Return [masterFd, pid] */
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result) {
        jint buf[2] = {masterFd, (jint)pid};
        (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    }
    return result;

fail:
    for (i = 0; i < cmdLen; i++) free(argv[i]);
    free(argv);
    if (envp) {
        for (i = 0; i < envLen; i++) free(envp[i]);
        free(envp);
    }
    return NULL;
}

/*
 * Write bytes to the PTY master fd.
 * @return number of bytes written, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeWrite(
    JNIEnv *env, jclass cls, jint fd, jbyteArray data)
{
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return -1;

    ssize_t total = 0;
    while (total < len) {
        ssize_t n = write(fd, buf + total, len - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        total += n;
    }

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint)total;
}

/*
 * Read bytes from the PTY master fd into the given buffer.
 * @return number of bytes read, 0 on EOF, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeRead(
    JNIEnv *env, jclass cls, jint fd, jbyteArray buffer)
{
    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) return -1;

    ssize_t n;
    do {
        n = read(fd, buf, len);
    } while (n < 0 && errno == EINTR);

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    return (jint)n;
}

/*
 * Set the PTY window size (rows × cols).
 * Sends TIOCSWINSZ ioctl to the master fd, which generates
 * SIGWINCH in the child process.
 */
JNIEXPORT void JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeSetWindowSize(
    JNIEnv *env, jclass cls, jint fd, jint rows, jint cols)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/*
 * Wait for the child process to exit and return its exit code.
 */
JNIEXPORT jint JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeWaitFor(
    JNIEnv *env, jclass cls, jint pid)
{
    int status;
    pid_t ret;
    do {
        ret = waitpid(pid, &status, 0);
    } while (ret < 0 && errno == EINTR);

    if (ret < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/*
 * Close the PTY master fd.
 */
JNIEXPORT void JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeClose(
    JNIEnv *env, jclass cls, jint fd)
{
    if (fd >= 0) close(fd);
}

/*
 * Send a signal to the child process.
 */
JNIEXPORT void JNICALL
Java_com_nous_hermes_mobile_PtyNative_nativeKillProcess(
    JNIEnv *env, jclass cls, jint pid, jint signal)
{
    if (pid > 0) kill(pid, signal);
}
