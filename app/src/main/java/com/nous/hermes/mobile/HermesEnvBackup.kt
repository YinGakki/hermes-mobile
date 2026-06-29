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
class HermesEnvBackup(private val context: Context) {

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
        private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b)
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

        // 临时文件用于流式 tar 输出
        val tmpArchive = File(cacheDir, "hermes-env-backup-${System.currentTimeMillis()}.tar.gz")
        try {
            onProgress("正在打包环境（可能需要 1-3 分钟）…")

            // 用 Termux 自带的 tar（系统 toybox tar 在某些 Android 版本上
            // 不支持 --exclude 通配符，GNU tar 行为更可预测）。环境通过
            // runInPrefix 提供，但 tar 命令自身不需要 Termux prefix，用
            // 系统 tar 也可。这里直接用 Java ProcessBuilder 调用 Termux 的
            // sh -c "tar ..."，PATH 指向 prefix/bin。
            val tarBin = File(prefix, "bin/tar")
            val useTermuxTar = tarBin.exists()

            val cmd = if (useTermuxTar) {
                listOf(
                    File(prefix, "bin/sh").absolutePath, "-c",
                    buildString {
                        append("cd \"")
                        append(filesDir.absolutePath)
                        append("\" && \"")
                        append(tarBin.absolutePath)
                        append("\" -czf \"")
                        append(tmpArchive.absolutePath)
                        append("\" ")
                        EXCLUDE_PATTERNS.forEach { append("--exclude '").append(it).append("' ") }
                        append(PREFIX_DIR_NAME).append(" ").append(HOME_DIR_NAME)
                    },
                )
            } else {
                listOf("tar", "-czf", tmpArchive.absolutePath) +
                    EXCLUDE_PATTERNS.flatMap { listOf("--exclude", it) } +
                    listOf("-C", filesDir.absolutePath, PREFIX_DIR_NAME, HOME_DIR_NAME)
            }

            Log.i(TAG, "Running backup cmd: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["TMPDIR"] = cacheDir.absolutePath
            val proc = pb.start()
            // drain stdout/stderr combined
            val output = proc.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.appendLine(line)
                    onProgress(line)
                    line = reader.readLine()
                }
                sb.toString()
            }
            val code = proc.waitFor()
            if (code != 0) {
                Log.e(TAG, "tar failed with code $code: $output")
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

            // 校验 tar.gz 完整性（test -t 比 -x 更快暴露损坏）
            onProgress("校验备份完整性…")
            val testProc = ProcessBuilder("tar", "-tzf", tmpArchive.absolutePath)
                .redirectErrorStream(true).start()
            val testOut = testProc.inputStream.bufferedReader().readText()
            val testCode = testProc.waitFor()
            if (testCode != 0) {
                onProgress("错误：备份文件已损坏")
                Log.e(TAG, "tar test failed: $testOut")
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

            // 解压到 filesDir
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
