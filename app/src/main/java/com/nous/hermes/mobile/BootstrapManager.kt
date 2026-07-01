package com.nous.hermes.mobile

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载并解压 Ubuntu rootfs，构建 proot 能直接 chroot 进去的完整 Linux 环境。
 *
 * 参照 openclaw-termux 的 BootstrapManager.kt 实现，全量替换原来的 Termux
 * prefix（手动 deb 解压）方案。核心改动：
 *   - 不再解压 bootstrap-aarch64.zip，改为下载 ubuntu-base arm64 tarball
 *   - 用 Apache Commons Compress 做 Java 两阶段提取（Phase 1 目录/文件/硬链接，
 *     Phase 2 符号链接），解决 tar 条目顺序导致的符号链接先于目标文件的问题
 *   - 预创建运行时目录（mkdir 在 proot 里坏的，直接 Java mkdirs）
 *   - 写 fake /proc 文件（Android 限制 /proc 访问，proot-distro 用静态假数据绕过）
 *   - libtalloc.so → libtalloc.so.2 复制（proot 链接 libtalloc.so.2，jniLibs 只能放 *.so）
 *
 * 目录布局（与 ProcessManager 保持一致）：
 *   filesDir/rootfs/ubuntu   ← Ubuntu rootfs（bind --rootfs 到这里）
 *   filesDir/home            ← 用户 home（bind 到 rootfs /root/home）
 *   filesDir/tmp             ← PROOT_TMP_DIR
 *   filesDir/config          ← resolv.conf + proc_fakes + sys_fakes
 *   filesDir/lib             ← libtalloc.so.2 等运行时库
 *   nativeLibDir             ← libproot.so / libprootloader.so（jniLibs 解压）
 */
object BootstrapManager {

    private const val TAG = "HermesBootstrap"

    // tar LF_HARDLINK 标志位（'1' = 49）。commons-compress 1.26 的
    // TarArchiveEntry 没有 isHardLink()，需用 linkFlag 直接判断。
    private const val LF_HARDLINK: Byte = '1'.code.toByte()

    // Ubuntu 24.04 base rootfs（arm64）。多镜像 fallback：官方 cdimage 在国内
    // 经常超时，清华镜像兜底。
    private val ROOTFS_URLS = listOf(
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz",
    )

    data class Paths(
        val filesDir: String,
        val rootfsDir: String,
        val homeDir: String,
        val tmpDir: String,
        val configDir: String,
        val libDir: String,
        val nativeLibDir: String,
    )

    fun getPaths(context: Context): Paths {
        val filesDir = context.filesDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return Paths(
            filesDir = filesDir,
            rootfsDir = "$filesDir/rootfs/ubuntu",
            homeDir = "$filesDir/home",
            tmpDir = "$filesDir/tmp",
            configDir = "$filesDir/config",
            libDir = "$filesDir/lib",
            nativeLibDir = nativeLibDir,
        )
    }

    fun isBootstrapInstalled(context: Context): Boolean {
        val paths = getPaths(context)
        // rootfs 提取完成的标志：/bin/bash 存在（Ubuntu rootfs 必有）。
        // proot 二进制单独检查（jniLibs）。
        return File(paths.rootfsDir, "bin/bash").exists() &&
            File(paths.nativeLibDir, "libproot.so").exists()
    }

    /**
     * 每次启动刷新可能被 Android 清理的系统配置文件。
     * 基于 openclaw "rebuild resolv.conf on every start" 模式。
     */
    fun ensureSystemConfig(context: Context) {
        val paths = getPaths(context)
        ensureRuntimeDirs(paths)
        writeResolvConf(paths)
        writeProcFakes(paths)
        writeSysFakes(paths)
        copyLibtalloc(paths)
    }

    /**
     * 下载 Ubuntu rootfs tarball 并解压到 rootfsDir。
     * 幂等：rootfs 已存在则直接返回。
     */
    fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ) {
        val paths = getPaths(context)
        val rootfsFile = File(paths.rootfsDir)

        if (rootfsFile.isDirectory && File(rootfsFile, "bin/bash").exists()) {
            Log.i(TAG, "Rootfs already installed at ${paths.rootfsDir}")
            ensureSystemConfig(context)
            return
        }

        ensureRuntimeDirs(paths)

        val stagingFile = File("${paths.filesDir}/rootfs-staging")
        if (stagingFile.exists()) deleteRecursive(stagingFile)
        stagingFile.mkdirs()

        onProgress("Downloading Ubuntu rootfs (arm64, ~30MB)…")
        val tarball = File(paths.tmpDir, "ubuntu-rootfs.tar.gz")
        if (!downloadRootfs(paths, tarball, onProgress)) {
            throw RuntimeException("Failed to download Ubuntu rootfs from all mirrors")
        }

        onProgress("Extracting rootfs (two-phase: dirs/files → symlinks)…")
        extractRootfsTwoPhase(tarball, stagingFile, onProgress)

        tarball.delete()

        // 原子重命名 staging → final
        if (rootfsFile.exists()) deleteRecursive(rootfsFile)
        if (!stagingFile.renameTo(rootfsFile)) {
            throw RuntimeException("Failed to rename $stagingFile to ${paths.rootfsDir}")
        }

        onProgress("Configuring rootfs for proot…")
        configureRootfs(paths)

        ensureSystemConfig(context)

        Log.i(TAG, "Rootfs installed at ${paths.rootfsDir}")
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    private fun downloadRootfs(
        paths: Paths,
        outFile: File,
        onProgress: (String) -> Unit,
    ): Boolean {
        for (url in ROOTFS_URLS) {
            val host = url.substringAfter("://").substringBefore("/")
            onProgress("尝试下载: $host …")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                if (conn.responseCode != 200) {
                    onProgress("HTTP ${conn.responseCode} from $host")
                    conn.disconnect()
                    continue
                }
                val total = conn.contentLengthLong
                outFile.parentFile?.mkdirs()
                var downloaded = 0L
                conn.inputStream.use { inp ->
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var n = inp.read(buf)
                        while (n > 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0 && downloaded % (5 * 1024 * 1024) < n) {
                                val pct = downloaded * 100 / total
                                onProgress("下载中… $pct% (${formatSize(downloaded)})")
                            }
                            n = inp.read(buf)
                        }
                    }
                }
                conn.disconnect()
                if (outFile.length() < 1024 * 1024) {
                    onProgress("tarball 太小 (${outFile.length()} 字节)，可能是错误页")
                    outFile.delete()
                    continue
                }
                onProgress("✓ 下载完成 (${formatSize(outFile.length())})")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Download from $host failed: ${e.message}")
                onProgress("$host 下载失败: ${e.message}")
                outFile.delete()
            }
        }
        return false
    }

    // ── 两阶段提取 ──────────────────────────────────────────────────────────

    /**
     * Java 两阶段提取（参照 openclaw BootstrapManager）。
     *
     * Phase 1: 目录、普通文件、硬链接。遇到符号链接跳过并记录。
     * Phase 2: 创建符号链接（此时所有目标文件都已存在）。
     *
     * tar 条目顺序不保证符号链接在目标之后，单趟提取会导致符号链接指向
     * 尚未创建的文件（虽然 Unix 允许，但 proot 对 dangling link 行为不稳）。
     */
    private fun extractRootfsTwoPhase(
        tarball: File,
        destDir: File,
        onProgress: (String) -> Unit,
    ) {
        val symlinks = mutableListOf<Pair<String, String>>() // (target, linkPath)
        var entryCount = 0
        var lastProgress = 0

        tarball.inputStream().buffered().use { fis ->
            GzipCompressorInputStream(fis).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount % 2000 == 0 && entryCount - lastProgress >= 2000) {
                            onProgress("提取中… $entryCount 个条目")
                            lastProgress = entryCount
                        }
                        extractEntry(tar, entry!!, destDir, symlinks)
                        entry = tar.nextTarEntry
                    }
                }
            }
        }

        onProgress("创建符号链接 (${symlinks.size} 个)…")
        for ((target, linkPath) in symlinks) {
            try {
                val linkFile = File(linkPath)
                if (linkFile.exists()) linkFile.delete()
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                // 部分符号链接可能指向 /proc 或绝对路径（不在 rootfs 内），
                // 创建失败不致命。
                Log.d(TAG, "symlink $linkPath -> $target: ${e.message}")
            }
        }
        onProgress("✓ rootfs 提取完成（$entryCount 个条目）")
    }

    private fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        destDir: File,
        symlinks: MutableList<Pair<String, String>>,
    ) {
        val name = entry.name
        // 防路径穿越（tarball 里不应有 ../，但防御性检查）
        if (name.contains("..")) return
        val outFile = File(destDir, name)

        when {
            entry.isSymbolicLink -> {
                // Phase 2 处理：先记录，等所有普通文件提取完再创建
                outFile.parentFile?.mkdirs()
                symlinks.add(entry.linkName to outFile.absolutePath)
                // 必须消费掉条目内容（即使符号链接无内容，也要让流前进）
                tar.read(ByteArray(0))
            }
            entry.linkFlag == LF_HARDLINK -> {
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                try {
                    if (target.exists() && !outFile.exists()) {
                        Os.link(target.absolutePath, outFile.absolutePath)
                    }
                } catch (_: Exception) {
                    if (target.exists() && !outFile.exists()) {
                        target.copyTo(outFile, overwrite = false)
                    }
                }
            }
            entry.isDirectory -> {
                outFile.mkdirs()
            }
            else -> {
                // 普通文件：从 tar 流读取内容写出
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n = tar.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        n = tar.read(buf)
                    }
                }
                setMode(outFile, entry.mode)
            }
        }
    }

    private fun setMode(file: File, mode: Int) {
        try {
            // 提取 tar 的 mode 位。Android 文件系统支持 chmod，但 proot
            // 会在运行时重新解释这些位，所以这里保留原 mode 即可。
            val perm = mode and 0b111_111_111
            if (perm != 0) Os.chmod(file.absolutePath, perm)
        } catch (_: Exception) {
            // chmod 失败不致命，proot 自己也会处理权限
        }
    }

    // ── 运行时目录 ──────────────────────────────────────────────────────────

    /**
     * 预创建 proot 运行时需要的所有目录。mkdir 在 proot 里坏的（proot 的
     * mkdir 实现有 bug，部分场景静默失败），所以直接用 Java mkdirs。
     * 参照 openclaw BootstrapManager 的 createAllDirs()。
     */
    private fun ensureRuntimeDirs(paths: Paths) {
        val rootfs = paths.rootfsDir
        // 顶层运行时目录
        listOf(
            paths.tmpDir, paths.configDir, paths.libDir, paths.homeDir,
            "$rootfs/tmp", "$rootfs/root", "$rootfs/root/home",
            "$rootfs/var/lib", "$rootfs/var/cache", "$rootfs/var/log",
            "$rootfs/var/tmp", "$rootfs/var/run", "$rootfs/run",
            "$rootfs/dev", "$rootfs/proc", "$rootfs/sys",
        ).forEach { File(it).mkdirs() }
        // apt/dpkg 工作目录
        listOf(
            "$rootfs/var/lib/apt/lists/partial",
            "$rootfs/var/lib/dpkg/info",
            "$rootfs/var/lib/dpkg/triggers",
            "$rootfs/var/lib/dpkg/updates",
            "$rootfs/var/cache/apt/archives/partial",
            "$rootfs/var/log/apt",
        ).forEach { File(it).mkdirs() }
        // proc/sys fakes 目录
        File(paths.configDir, "proc_fakes").mkdirs()
        File(paths.configDir, "proc_fakes/sys/kernel").mkdirs()
        File(paths.configDir, "proc_fakes/sys/fs/inotify").mkdirs()
        File(paths.configDir, "proc_fakes/sys/crypto").mkdirs()
        File(paths.configDir, "sys_fakes/empty").mkdirs()
    }

    // ── rootfs 配置 ─────────────────────────────────────────────────────────

    /**
     * 写 apt/dpkg 兼容配置、源列表、passwd/group 等。
     * 参照 openclaw BootstrapManager.configureRootfs()。
     */
    private fun configureRootfs(paths: Paths) {
        val rootfs = paths.rootfsDir

        // apt.conf.d — proot 兼容配置（关键）：
        //   APT::Sandbox::User "root"  — 禁用 apt 沙箱降权（proot 里降权会失败）
        //   Dpkg::Use-Pty "0"          — 禁用 PTY（proot 的 PTY 不稳定）
        //   force-unsafe-io            — 跳过 fsync（Android 闪存上慢）
        val aptConfDir = File(rootfs, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "99proot").writeText(
            """
            APT::Sandbox::User "root";
            APT::Sandbox::Seccomp "false";
            Dpkg::Use-Pty "0";
            APT::Get::Assume-Yes "true";
            """.trimIndent() + "\n"
        )
        // bootstrap 阶段放宽验证：ubuntu-base 最小 rootfs 可能不含 ca-certificates
        // 和 ubuntu-keyring，所以用 HTTP 源 + trusted=yes 跳过 GPG 验证。
        // 装 ca-certificates + ubuntu-keyring 后可恢复严格模式。
        File(aptConfDir, "99bootstrap-relaxed").writeText(
            "APT::Get::Allow-Unauthenticated \"true\";\n" +
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n"
        )
        File(aptConfDir, "99unsafe-io").writeText(
            "DPkg::Options { \"--force-unsafe-io\"; }\n"
        )

        // Ubuntu ports 源（arm64 用 ports.ubuntu.com，不是 archive.ubuntu.com）
        // 清华镜像优先（国内速度快），官方兜底。
        // 用 HTTP 而非 HTTPS：ubuntu-base 最小 rootfs 不保证含 ca-certificates，
        // HTTPS 会导致 apt-get update 报 Certificate verification failed。
        // [trusted=yes] 跳过 GPG 验证（rootfs 可能不含 ubuntu-keyring）。
        val sourcesList = File(rootfs, "etc/apt/sources.list")
        sourcesList.parentFile?.mkdirs()
        sourcesList.writeText(
            "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main universe\n" +
            "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main universe\n" +
            "# deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports/ noble main universe\n" +
            "# deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports/ noble-updates main universe\n"
        )

        // dpkg status 文件（空文件，apt update 会用，dpkg --configure 需要）
        File(rootfs, "var/lib/dpkg/status").apply {
            parentFile?.mkdirs()
            if (!exists()) writeText("")
        }
        File(rootfs, "var/lib/dpkg/available").apply {
            if (!exists()) writeText("")
        }

        // /etc/passwd + /etc/group（dpkg/apt 需要 getpwuid_r 解析当前用户）
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists()) {
            passwd.writeText(
                "root:x:0:0:root:/root:/bin/bash\n" +
                "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n" +
                "_apt:x:100:65534::/nonexistent:/usr/sbin/nologin\n"
            )
        }
        val group = File(rootfs, "etc/group")
        if (!group.exists()) {
            group.writeText(
                "root:x:0:\n" +
                "daemon:x:1:\n" +
                "nogroup:x:65534:\n"
            )
        }

        // 时区（避免 Python tzdata 交互式 prompt 卡死）
        val timezone = File(rootfs, "etc/timezone")
        if (!timezone.exists()) timezone.writeText("Etc/UTC\n")
        val tzFile = File(rootfs, "etc/localtime")
        if (!tzFile.exists()) {
            // 写一个最小 TZif2 stub，避免无 zoneinfo 时 tzset() 崩溃
            tzFile.writeBytes(ByteArray(44) { 0 }.also { it[0] = 'T'.code.toByte() })
        }

        // hostname
        File(rootfs, "etc/hostname").apply { if (!exists()) writeText("hermes\n") }

        Log.i(TAG, "Rootfs configured (apt/dpkg/passwd/timezone)")
    }

    // ── DNS / fake proc / libtalloc ─────────────────────────────────────────

    private fun writeResolvConf(paths: Paths) {
        // 国内 DNS 优先（223.5.5.5 阿里、114.114.114.114），Google/Cloudflare 兜底。
        // 注意：proot 里 DNS 走 host 网络栈，直接用 IP 不需要 /etc/hosts。
        val content = "nameserver 223.5.5.5\nnameserver 114.114.114.114\nnameserver 8.8.8.8\n"
        try {
            // configDir/resolv.conf —— ProcessManager bind mount 用
            val r1 = File(paths.configDir, "resolv.conf")
            r1.writeText(content)
            // rootfs/etc/resolv.conf —— bind mount 失败时的兜底
            val r2 = File(paths.rootfsDir, "etc/resolv.conf")
            r2.parentFile?.mkdirs()
            r2.writeText(content)
        } catch (e: Exception) {
            Log.w(TAG, "writeResolvConf: ${e.message}")
        }
    }

    /**
     * 写 fake /proc 文件。Android 限制 /proc 访问，proot-distro 用静态假数据
     * bind mount 绕过。尤其 fips_enabled —— libgcrypt 启动时读它，缺失会 SIGABRT。
     */
    private fun writeProcFakes(paths: Paths) {
        val fakes = File(paths.configDir, "proc_fakes")
        try {
            File(fakes, "loadavg").apply { if (!exists()) writeText("0.50 0.40 0.30 1/100 100\n") }
            File(fakes, "stat").apply { if (!exists()) writeText("cpu  0 0 0 0 0 0 0 0 0 0\n") }
            File(fakes, "uptime").apply { if (!exists()) writeText("100.0 80.0\n") }
            File(fakes, "version").apply {
                if (!exists()) writeText(
                    "Linux version ${ProcessManager.FAKE_KERNEL_RELEASE} " +
                    "(proot@hermes) (gcc) #1 SMP PREEMPT\n"
                )
            }
            File(fakes, "vmstat").apply { if (!exists()) writeText("cpu 0 0 0 0\n") }
            File(fakes, "sys/kernel/cap_last_cap").apply {
                if (!exists()) writeText("38\n")
            }
            File(fakes, "sys/fs/inotify/max_user_watches").apply {
                if (!exists()) writeText("524288\n")
            }
            File(fakes, "sys/crypto/fips_enabled").apply {
                if (!exists()) writeText("0\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeProcFakes: ${e.message}")
        }
    }

    private fun writeSysFakes(paths: Paths) {
        // 空 dir 禁用 SELinux 检查（bind 到 /sys/fs/selinux）
        File(paths.configDir, "sys_fakes/empty").mkdirs()
    }

    /**
     * proot 链接 libtalloc.so.2，但 jniLibs 只能放 *.so 命名的文件，
     * fetch-proot-binaries.sh 抓的是 libtalloc.so。这里复制成 .so.2 放到
     * libDir（ProcessManager 把 libDir 加进 LD_LIBRARY_PATH）。
     */
    private fun copyLibtalloc(paths: Paths) {
        try {
            val src = File(paths.nativeLibDir, "libtalloc.so")
            val dst = File(paths.libDir, "libtalloc.so.2")
            if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
                src.copyTo(dst, overwrite = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyLibtalloc: ${e.message}")
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun deleteRecursive(fileOrDir: File) {
        // 安全：不跟随指向 rootfs 外的符号链接（避免删除 /sdcard 等）
        val isSymlink = try {
            Os.readlink(fileOrDir.absolutePath); true
        } catch (_: Exception) { false }
        if (isSymlink) {
            fileOrDir.delete()
            return
        }
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { deleteRecursive(it) }
        }
        fileOrDir.delete()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb >= 1024 -> String.format("%.2f GB", mb / 1024)
            mb >= 1 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
    }
}