package com.nous.hermes.mobile

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * proot 进程管理器，参照 openclaw-termux 的 ProcessManager.kt 实现。
 *
 * 全程用 proot 包裹命令执行，提供两种模式：
 *   - Install 模式 (buildInstallCommand): 对应 proot-distro 的 run_proot_cmd()
 *     用于 apt-get/dpkg/pip install 等安装操作。--root-id 假冒 root，
 *     不带 --sysvipc（dpkg fork 子进程时会 SIGABRT）。
 *   - Gateway 模式 (buildGatewayCommand): 对应 proot-distro 的 command_login()
 *     用于长期运行 Hermes gateway。--change-id=0:0 + --sysvipc + 完整 uname 结构。
 *
 * 关键设计：
 *   1. proot 二进制通过 jniLibs（libproot.so）打包，Android 自动解压到
 *      nativeLibraryDir 并带执行位，绕过 W^X 策略。
 *   2. ProcessBuilder.environment().clear() 必须调用，否则 Android JVM 的
 *      LD_PRELOAD/CLASSPATH/DEX2OAT 会泄漏进 proot，破坏 fork+exec。
 *   3. guest 环境通过 `env -i` 设置（proot 命令行里），与 proot-distro 一致。
 *   4. fake /proc 文件 bind mount（Android 限制 /proc 访问，proot-distro
 *      用静态假数据绕过）。
 *   5. resolv.conf 双写：configDir（bind mount 用）+ rootfs/etc（兜底）。
 */
class ProcessManager(
    private val context: Context,
    private val filesDir: String,
    private val nativeLibDir: String,
) {

    companion object {
        private const val TAG = "HermesProcessManager"
        // 匹配 proot-distro v4.37.0 默认值
        const val FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro"
        const val FAKE_KERNEL_VERSION =
            "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
    }

    // 目录布局（与 BootstrapManager 保持一致）
    val rootfsDir get() = "$filesDir/rootfs/ubuntu"
    val tmpDir get() = "$filesDir/tmp"
    val homeDir get() = "$filesDir/home"
    val configDir get() = "$filesDir/config"
    val libDir get() = "$filesDir/lib"

    fun getProotPath(): String = "$nativeLibDir/libproot.so"

    /**
     * proot 二进制自身需要的主机侧环境变量。
     * 注意：这些不会泄漏进 guest（guest 环境由 `env -i` 清空后设置）。
     */
    private fun prootEnv(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to tmpDir,
        "PROOT_LOADER" to "$nativeLibDir/libprootloader.so",
        "PROOT_LOADER_32" to "$nativeLibDir/libprootloader32.so",
        // proot 自身链接 libtalloc.so.2（BootstrapManager 会把 libtalloc.so
        // 复制成 libtalloc.so.2 放到 libDir）
        "LD_LIBRARY_PATH" to "$libDir:$nativeLibDir",
        // 不设 PROOT_NO_SECCOMP（proot-distro 也不设），seccomp 提供高效
        // syscall 拦截 + 正确的 fork/clone 子进程追踪。
        // 不设 PROOT_L2S_DIR（用 Java 提取 rootfs，没有 L2S 元数据）。
    )

    /**
     * 公开 proot 主机侧环境变量，供需要直接 spawn proot 进程的调用方
     * （如 HermesEnvBackup）使用。
     */
    fun prootEnvPublic(): Map<String, String> = prootEnv()

    /**
     * 确保 resolv.conf 存在。所有 proot 操作都经过 commonProotFlags()，
     * 所以这里能保证 DNS 对所有调用方可用。
     */
    private fun ensureResolvConf() {
        val content = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
        try {
            val resolvFile = File(configDir, "resolv.conf")
            if (!resolvFile.exists() || resolvFile.length() == 0L) {
                resolvFile.parentFile?.mkdirs()
                resolvFile.writeText(content)
            }
        } catch (_: Exception) {}
        // 兜底：直接写进 rootfs /etc/resolv.conf，bind mount 失败时也能用
        try {
            val rootfsResolv = File(rootfsDir, "etc/resolv.conf")
            if (!rootfsResolv.exists() || rootfsResolv.length() == 0L) {
                rootfsResolv.parentFile?.mkdirs()
                rootfsResolv.writeText(content)
            }
        } catch (_: Exception) {}
    }

    /**
     * 公共 proot bind mounts，install 和 gateway 模式共享。
     * 完全匹配 proot-distro 的 bind mount 列表。
     */
    private fun commonProotFlags(): List<String> {
        ensureResolvConf()

        val prootPath = getProotPath()
        val procFakes = "$configDir/proc_fakes"
        val sysFakes = "$configDir/sys_fakes"

        return listOf(
            prootPath,
            "--link2symlink",
            "-L",
            "--kill-on-exit",
            "--rootfs=$rootfsDir",
            "--cwd=/root",
            // 核心设备 bind（匹配 proot-distro）
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            // fake /proc 条目 — Android 限制 /proc 访问，proot-distro 用
            // 静态假数据绕过。
            "--bind=$procFakes/loadavg:/proc/loadavg",
            "--bind=$procFakes/stat:/proc/stat",
            "--bind=$procFakes/uptime:/proc/uptime",
            "--bind=$procFakes/version:/proc/version",
            "--bind=$procFakes/vmstat:/proc/vmstat",
            "--bind=$procFakes/cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$procFakes/max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            // libgcrypt 启动时读这个；缺失会导致 apt HTTP method SIGABRT
            "--bind=$procFakes/fips_enabled:/proc/sys/crypto/fips_enabled",
            // 共享内存 — proot-distro 把 rootfs/tmp bind 到 /dev/shm
            "--bind=$rootfsDir/tmp:/dev/shm",
            // SELinux 覆盖 — 空 dir 禁用 SELinux 检查
            "--bind=$sysFakes/empty:/sys/fs/selinux",
            // DNS
            "--bind=$configDir/resolv.conf:/etc/resolv.conf",
            // home 目录
            "--bind=$homeDir:/root/home",
        ).let { flags ->
            // 可选：bind 共享存储（有 MANAGE_EXTERNAL_STORAGE 权限时）
            val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                val sdcard = Environment.getExternalStorageDirectory()
                sdcard.exists() && sdcard.canRead()
            }
            if (hasAccess) {
                val storageDir = File("$rootfsDir/storage")
                storageDir.mkdirs()
                val sdcardLink = File("$rootfsDir/sdcard")
                if (!sdcardLink.exists()) {
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf("ln", "-sf", "/storage/emulated/0", "$rootfsDir/sdcard"),
                        ).waitFor()
                    } catch (_: Exception) {
                        sdcardLink.mkdirs()
                    }
                }
                flags + listOf(
                    "--bind=/storage:/storage",
                    "--bind=/storage/emulated/0:/sdcard",
                )
            } else {
                flags
            }
        }
    }

    /**
     * INSTALL 模式 — 匹配 proot-distro 的 run_proot_cmd()
     * 用于 apt-get/dpkg/pip install/git clone 等安装操作。
     * 简单：无 --sysvipc，简单 kernel-release，最小 guest 环境。
     */
    fun buildInstallCommand(command: String): List<String> {
        val flags = commonProotFlags().toMutableList()
        // --root-id: 假冒 root 身份（同 proot-distro run_proot_cmd）
        flags.add(1, "--root-id")
        // 简单 kernel-release（proot-distro run_proot_cmd 用纯字符串）
        flags.add(2, "--kernel-release=$FAKE_KERNEL_RELEASE")
        // 注意：install 模式不用 --sysvipc（dpkg fork 子进程会 SIGABRT）

        // guest 环境通过 env -i 设置（匹配 proot-distro run_proot_cmd）
        flags.addAll(
            listOf(
                "/usr/bin/env", "-i",
                "HOME=/root",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "TMPDIR=/tmp",
                "DEBIAN_FRONTEND=noninteractive",
                "/bin/bash", "-c",
                command,
            ),
        )
        return flags
    }

    /**
     * GATEWAY 模式 — 匹配 proot-distro 的 command_login()
     * 用于长期运行 Hermes gateway。
     * 完整：--sysvipc + 完整 uname 结构 + 更多 guest 环境变量。
     */
    fun buildGatewayCommand(command: String): List<String> {
        val flags = commonProotFlags().toMutableList()
        val arch = getArch()
        val machine = when (arch) {
            "arm" -> "armv7l"
            else -> arch // aarch64, x86_64, x86
        }
        // --change-id=0:0（proot-distro command_login 用这个表示 root）
        flags.add(1, "--change-id=0:0")
        // --sysvipc: 启用 SysV IPC（proot-distro login session 启用）
        flags.add(2, "--sysvipc")
        // 完整 uname 结构（匹配 proot-distro command_login）
        // 格式: \sysname\nodename\release\version\machine\domainname\personality\
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE" +
            "\\$FAKE_KERNEL_VERSION\\$machine\\localdomain\\-1\\"
        flags.add(3, "--kernel-release=$kernelRelease")

        // guest 环境通过 env -i（匹配 proot-distro command_login）
        flags.addAll(
            listOf(
                "/usr/bin/env", "-i",
                "HOME=/root",
                "USER=root",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "TMPDIR=/tmp",
                "/bin/bash", "-c",
                command,
            ),
        )
        return flags
    }

    /**
     * 在 proot（install 模式）里执行命令，返回输出。
     * 用于安装阶段的 apt/pip/git/chmod 等。
     *
     * @param command 要在 rootfs 内执行的 shell 命令
     * @param timeoutSeconds 超时秒数（默认 900s = 15min，pip install 可能很久）
     * @param onOutput 每行输出的回调（用于进度显示）
     * @return 命令的完整 stdout 输出
     * @throws RuntimeException 命令失败或超时
     */
    fun runInProotSync(
        command: String,
        timeoutSeconds: Long = 900,
        onOutput: ((String) -> Unit)? = null,
    ): String {
        val cmd = buildInstallCommand(command)
        val env = prootEnv()

        val pb = ProcessBuilder(cmd)
        // 关键：清除继承的 Android JVM 环境变量。
        // 不清除的话 LD_PRELOAD/CLASSPATH/DEX2OAT 会泄漏进 proot，破坏 fork+exec。
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            // 过滤 proot 自身的警告噪音
            if (l.contains("proot warning") || l.contains("can't sanitize")) {
                continue
            }
            output.appendLine(l)
            onOutput?.invoke(l)
        }

        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw RuntimeException("Command timed out after ${timeoutSeconds}s: $command")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val errorOutput = output.toString().takeLast(3000)
            throw RuntimeException(
                "Command failed (exit $exitCode): $command\n$errorOutput",
            )
        }
        return output.toString()
    }

    /**
     * 在 proot 里执行命令，返回退出码（不抛异常）。
     * 用于需要根据退出码判断的场景（如 isXxxInstalled 检查）。
     */
    fun runInProotExitCode(
        command: String,
        timeoutSeconds: Long = 900,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val cmd = buildInstallCommand(command)
        val env = prootEnv()
        val pb = ProcessBuilder(cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (l.contains("proot warning") || l.contains("can't sanitize")) continue
            onOutput?.invoke(l)
        }
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }

    /**
     * 启动长驻 gateway 进程（gateway 模式）。
     * 调用方负责读取 stdout/stderr 和管理进程生命周期。
     */
    fun startProotProcess(command: String): Process {
        val cmd = buildGatewayCommand(command)
        val env = prootEnv()
        val pb = ProcessBuilder(cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        return pb.start()
    }

    private fun getArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "arm"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> abi
        }
    }
}
