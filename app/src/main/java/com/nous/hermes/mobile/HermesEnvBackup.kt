package com.nous.hermes.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 备份/还原已安装好的 Hermes 运行环境（Termux prefix + home 目录）。
 *
 * 备份内容：files/usr 与 files/home 全量打包成 tar.gz，排除 tmp/、cache/、
 * *.pyc、npm cache 等可重建的临时文件。
 *
 * 还原流程：清空现有 prefix 与 home → 解压备份文件 → 跳过全部安装步骤直接可用。
 *
 * 文件路径策略：用 SAF 让用户选择保存位置（兼容 Documents/ Downloads/ USB OTG
 * 等任意位置）。SAF 返回的 Uri 不是文件系统路径，无法直接喂给 tar 命令，
 * 所以先在 cacheDir 生成/接收临时 tar.gz 文件，再用 ContentResolver 复制到
 * Uri / 从 Uri 复制到 cacheDir。
 */
class HermesEnvBackup(private val context: Context, private val serverMgr: HermesServerManager) {

    companion object {
        private const val TAG = "HermesEnvBackup"

        // 需要打包/还原的子目录（相对于 context.filesDir）
        private const val PREFIX_DIR_NAME = "usr"
        private const val HOME_DIR_NAME = "home"

        // 备份内排除的路径（相对 filesDir，归档条目形如 "usr/tmp/foo"）
        // 这些是可重建的临时/缓存文件，备份它们会浪费几百 MB 且无意义。
        private val EXCLUDE_PATTERNS = listOf(
            "usr/tmp",
            "usr/var/cache",
            "usr/var/log",
            "usr/var/tmp",
            "home/.cache",
            "home/.npm",
            "home/.cargo/registry",
            "home/.rustup/toolchains/*/share",
            "home/hermes-agent/.venv/lib/python*/site-packages/*/__pycache__",
            "home/hermes-agent/build",
            "home/hermes-agent/dist",
        )

        // 备份文件 magic header（gzip magic 0x1f 0x8b）
        private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x8b.toByte())
    }

    private val filesDir = context.filesDir
    private val cacheDir = context.cacheDir

    /**
     * 备份已安装的环境到用户选定的 Uri。
     *
     * 在调用方线程执行；调用方应在后台线程调用。
     *
     * @param targetUri SAF 返回的输出 Uri
     * @param onProgress 进度文本回调（UI 线程除外，调用方自行 post）
     * @return true 表示成功
     */
    fun backup(targetUri: Uri, onProgress: (String) -> Unit): Boolean {
        val prefix = File(filesDir, PREFIX_DIR_NAME)
        val home = File(filesDir, HOME_DIR_NAME)
        if (!prefix.isDirectory || !File(prefix, "bin/sh").exists()) {
            onProgress("错误：未检测到已安装的环境，无法备份")
            return false
        }

        // 临时文件用于流式 tar 输出。放在 cacheDir 而非 prefix/tmp/ 是
        // 因为备份内容包含 prefix/tmp/，tar 不能写到正在打包的目录里。
        val tmpArchive = File(cacheDir, "hermes-env-backup-${System.currentTimeMillis()}.tar.gz")
        try {
            onProgress("正在打包环境（可能需要 1-3 分钟）…")

            // 用 Termux 自带的 tar（系统 toybox tar 在 Android 11+ 可能
            // 不支持 --exclude 通配符，且某些版本缺 gzip 支持）。必须通过
            // serverMgr.runInPrefix 执行 —— Termux 的 tar 链接 libandroid-glob.so
            // 等动态库，只有 runInPrefix 配置的 LD_LIBRARY_PATH 才能找到。
            val excludesArg = EXCLUDE_PATTERNS.joinToString(" ") { "--exclude '$it'" }
            val tarCmd = """
                cd "${'$'}{FILES_DIR}" && tar -czf "${'$'}{ARCHIVE_PATH}" $excludesArg $PREFIX_DIR_NAME $HOME_DIR_NAME 2>&1
            """.trimIndent()
            // 在 runInPrefix 环境里注入 FILES_DIR 和 ARCHIVE_PATH（prefix 路径
            // 含空格或特殊字符时安全）。runInPrefix 的 env map 不支持额外
            // 变量，所以直接字符串拼接（路径来自 context.filesDir，无空格）。
            val cmd = tarCmd
                .replace("\${'$'}{FILES_DIR}", filesDir.absolutePath)
                .replace("\${'$'}{ARCHIVE_PATH}", tmpArchive.absolutePath)
            Log.i(TAG, "Running backup via runInPrefix: $cmd")
            val code = serverMgr.runInPrefix(cmd, onOutput = { onProgress(it) })
            if (code != 0) {
                Log.e(TAG, "tar failed with code $code")
                onProgress("错误：tar 打包失败（exit=$code）")
                return false
            }

            val archiveSize = tmpArchive.length()
            onProgress("打包完成：${formatSize(archiveSize)}，正在写入目标文件…")
            Log.i(TAG, "Backup archive size: $archiveSize bytes")

            // 复制到用户选定的 Uri
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                FileInputStream(tmpArchive).use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var n = inp.read(buf)
                    var copied = 0L
                    while (n > 0) {
                        out.write(buf, 0, n)
                        copied += n
                        if (copied % (10 * 1024 * 1024) < n) {
                            onProgress("写入中… ${formatSize(copied)} / ${formatSize(archiveSize)}")
                        }
                        n = inp.read(buf)
                    }
                }
            } ?: run {
                onProgress("错误：无法打开目标文件写入")
                return false
            }

            onProgress("✓ 环境已备份到目标位置")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            onProgress("错误：${e.message ?: "备份失败"}")
            return false
        } finally {
            tmpArchive.delete()
        }
    }

    /**
     * 从用户选定的 Uri 还原环境。
     *
     * 在调用方线程执行；调用方应在后台线程调用。
     *
     * @param sourceUri SAF 返回的输入 Uri
     * @param onProgress 进度文本回调
     * @return true 表示成功
     */
    fun restore(sourceUri: Uri, onProgress: (String) -> Unit): Boolean {
        val tmpArchive = File(cacheDir, "hermes-env-restore-${System.currentTimeMillis()}.tar.gz")
        try {
            // 先验证 magic header，避免用户选了非 tar.gz 文件后浪费几十秒解压
            val magicOk = context.contentResolver.openInputStream(sourceUri)?.use { inp ->
                val header = ByteArray(2)
                val read = inp.read(header)
                read == 2 && header.contentEquals(GZIP_MAGIC)
            } ?: false
            if (!magicOk) {
                onProgress("错误：所选文件不是有效的 .tar.gz 备份")
                return false
            }

            onProgress("正在读取备份文件…")
            // 复制到 cacheDir 临时文件（tar 命令需要文件系统路径，Uri 不行）
            context.contentResolver.openInputStream(sourceUri)?.use { inp ->
                FileOutputStream(tmpArchive).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n = inp.read(buf)
                    var totalCopied = 0L
                    while (n > 0) {
                        out.write(buf, 0, n)
                        totalCopied += n
                        n = inp.read(buf)
                    }
                    Log.i(TAG, "Restore archive copied: $totalCopied bytes")
                }
            } ?: run {
                onProgress("错误：无法读取备份文件")
                return false
            }

            // 校验 tar.gz 完整性（test 比 extract 更快暴露损坏）
            // 用 runInPrefix 调用 Termux tar（系统 tar 可能不支持 -t 或
            // 缺 gzip）。注意：这一步必须在清空 prefix 之前执行，因为
            // 清空后 prefix/bin/tar 就没了。
            onProgress("校验备份完整性…")
            val testCmd = "tar -tzf \"${tmpArchive.absolutePath}\" >/dev/null 2>&1"
            val testCode = serverMgr.runInPrefix(testCmd)
            if (testCode != 0) {
                onProgress("错误：备份文件已损坏或格式不支持")
                Log.e(TAG, "tar test failed (code=$testCode)")
                return false
            }

            // 备份完成 → 清空旧 prefix + home
            onProgress("清除现有环境…")
            val prefix = File(filesDir, PREFIX_DIR_NAME)
            val home = File(filesDir, HOME_DIR_NAME)
            try {
                if (prefix.exists()) prefix.deleteRecursively()
                if (home.exists()) home.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fully clear old env: ${e.message}")
                onProgress("警告：部分旧文件无法删除，继续还原…")
            }

            // 解压到 filesDir —— 此时 prefix 已清空，runInPrefix 不可用，
            // 改用 Java ProcessBuilder 调系统 tar（解压不需要 LD_LIBRARY_PATH，
            // 系统 toybox tar 的 -xzf 基础解压功能是支持的，且不依赖
            // Termux 的动态库）。如果系统 tar 不可用则用 Java GZIPInputStream
            // + TarInputStream 纯 Java 解压（备选方案，暂未实现）。
            onProgress("正在解压环境（可能需要 1-2 分钟）…")
            val proc = ProcessBuilder(
                "tar", "-xzf", tmpArchive.absolutePath,
                "-C", filesDir.absolutePath,
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.appendLine(line)
                    line = reader.readLine()
                }
                sb.toString()
            }
            val code = proc.waitFor()
            if (code != 0) {
                onProgress("错误：解压失败（exit=$code）")
                Log.e(TAG, "tar extract failed: $out")
                return false
            }

            // 修复可执行位 —— tar 应该保留了 mode bit，但 Android 文件系统
            // 在某些情况下会丢失，跟 rust/clang extract 同样问题
            onProgress("修复可执行权限…")
            fixExecutableBits(prefix)

            // 验证关键路径
            val shOk = File(prefix, "bin/sh").exists()
            val hermesOk = File(prefix, "bin/hermes").exists()
            if (!shOk) {
                onProgress("错误：还原后缺少 bin/sh，备份可能不完整")
                return false
            }
            onProgress(if (hermesOk) "✓ 环境还原成功，Hermes 可用" else "✓ 环境还原成功，但 Hermes 未安装（可能是仅备份了部分）")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            onProgress("错误：${e.message ?: "还原失败"}")
            return false
        } finally {
            tmpArchive.delete()
        }
    }

    /**
     * 对 prefix/bin 下所有文件、libexec 下文件强制 chmod 755。
     * tar 解压后 mode bit 可能丢失（同 rust/clang extract 问题）。
     */
    private fun fixExecutableBits(prefix: File) {
        try {
            val binDir = File(prefix, "bin")
            if (binDir.isDirectory) {
                binDir.listFiles()?.forEach { f ->
                    if (f.isFile) f.setExecutable(true, true)
                }
            }
            val libexec = File(prefix, "libexec")
            if (libexec.isDirectory) {
                libexec.walkTopDown().forEach { f ->
                    if (f.isFile) f.setExecutable(true, true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixExecutableBits partial failure: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }
}
