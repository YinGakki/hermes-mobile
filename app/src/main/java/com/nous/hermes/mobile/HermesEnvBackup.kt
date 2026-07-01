package com.nous.hermes.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 备份/还原已安装好的 Hermes 运行环境（proot rootfs + home + config + lib）。
 *
 * 新架构（proot + Ubuntu rootfs）下备份内容：
 *   filesDir/rootfs  — Ubuntu rootfs（含 apt 装的 python/build deps）
 *   filesDir/home    — hermes-agent 代码 + .venv + .hermes 配置
 *   filesDir/config  — resolv.conf + proc_fakes + build-deps marker
 *   filesDir/lib     — libtalloc.so.2 等运行时库
 *
 * 排除 rootfs 运行时目录（proc/sys/dev/tmp/run，这些是空目录或 bind mount），
 * 以及 venv 内的 __pycache__、apt cache 等可重建文件。
 *
 * 还原后跳过全部安装步骤直接可用。
 *
 * 实现策略：用系统 toybox tar（/system/bin/sh -c "tar -cf -"）打包到 stdout，
 * Java GZIPOutputStream 压缩，彻底不依赖 proot/prefix 二进制（还原时 rootfs
 * 可能已被清空，proot 不可用）。
 */
class HermesEnvBackup(private val context: Context, private val serverMgr: HermesServerManager) {

    companion object {
        private const val TAG = "HermesEnvBackup"

        // 需要打包/还原的子目录（相对于 context.filesDir）
        private val BACKUP_DIR_NAMES = listOf("rootfs", "home", "config", "lib")

        // 备份内排除的路径（归档条目形如 "rootfs/proc/..."）
        // 这些是可重建的临时/缓存/运行时文件。
        private val EXCLUDE_PATTERNS = listOf(
            "rootfs/proc", "rootfs/sys", "rootfs/dev",
            "rootfs/run", "rootfs/tmp", "rootfs/var/tmp",
            "rootfs/var/cache", "rootfs/var/log",
            "home/.cache", "home/.npm",
            "home/hermes-agent/.venv/lib/python*/site-packages/*/__pycache__",
            "home/hermes-agent/build", "home/hermes-agent/dist",
        )

        // 备份文件 magic header（gzip magic 0x1f 0x8b）
        private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x8b.toByte())
    }

    private val filesDir = context.filesDir
    private val cacheDir = context.cacheDir

    /**
     * 备份已安装的环境到用户选定的 Uri。
     *
     * @param targetUri SAF 返回的输出 Uri
     * @param onProgress 进度文本回调
     * @return true 表示成功
     */
    fun backup(targetUri: Uri, onProgress: (String) -> Unit): Boolean {
        // 检查 rootfs 是否已安装
        val rootfs = File(filesDir, "rootfs")
        val home = File(filesDir, "home")
        if (!File(rootfs, "ubuntu/bin/bash").exists()) {
            onProgress("错误：未检测到已安装的环境，无法备份")
            return false
        }

        val tmpArchive = File(cacheDir, "hermes-env-backup-${System.currentTimeMillis()}.tar.gz")
        try {
            onProgress("正在打包环境（可能需要 1-3 分钟）…")

            // 用系统 toybox tar（不依赖 proot/prefix，还原时 rootfs 已清空也能用）。
            // tar -cf - 写到 stdout 不压缩，Java GZIPOutputStream 压缩，
            // 彻底绕过 gzip 二进制权限问题（同旧方案）。
            val excludesArg = EXCLUDE_PATTERNS.joinToString(" ") { "--exclude '$it'" }
            val dirsArg = BACKUP_DIR_NAMES.joinToString(" ")
            val tarCmd = "cd \"${filesDir.absolutePath}\" && tar -cf - $excludesArg $dirsArg"
            Log.i(TAG, "Running backup: $tarCmd")

            val pb = ProcessBuilder("/system/bin/sh", "-c", tarCmd)
            pb.environment().clear()
            // 系统 tar 不需要任何特殊环境变量
            pb.directory(filesDir)
            pb.redirectErrorStream(false)

            val proc = pb.start()

            // 并发读 stderr（tar 文本输出 → onProgress）
            val stderrThread = Thread {
                try {
                    proc.errorStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            Log.d(TAG, "[tar stderr] $line")
                            onProgress(line)
                            line = reader.readLine()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stderr reader interrupted: ${e.message}")
                }
            }.also { it.isDaemon = true; it.start() }

            // 读 stdout（tar 二进制流）→ GZIP 压缩 → 临时文件
            var totalRead = 0L
            try {
                GZIPOutputStream(FileOutputStream(tmpArchive)).use { gzOut ->
                    proc.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        var n = inp.read(buf)
                        while (n > 0) {
                            gzOut.write(buf, 0, n)
                            totalRead += n
                            if (totalRead % (50 * 1024 * 1024) < n) {
                                onProgress("打包中… ${formatSize(totalRead)}")
                            }
                            n = inp.read(buf)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GZIP write failed: ${e.message}")
                proc.destroyForcibly()
                onProgress("错误：打包失败 — ${e.message}")
                return false
            }

            stderrThread.join(5000)
            val code = proc.waitFor()
            if (code != 0) {
                Log.e(TAG, "tar failed with code $code (read $totalRead bytes)")
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
     * @param sourceUri SAF 返回的输入 Uri
     * @param onProgress 进度文本回调
     * @return true 表示成功
     */
    fun restore(sourceUri: Uri, onProgress: (String) -> Unit): Boolean {
        val tmpArchive = File(cacheDir, "hermes-env-restore-${System.currentTimeMillis()}.tar.gz")
        try {
            // 先验证 magic header
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

            // 校验完整性（用系统 tar，还原时 rootfs 可能已清空 proot 不可用）
            onProgress("校验备份完整性…")
            val testProc = ProcessBuilder(
                "/system/bin/sh", "-c",
                "tar -tzf \"${tmpArchive.absolutePath}\" >/dev/null 2>&1"
            ).redirectErrorStream(true).start()
            val testCode = testProc.waitFor()
            if (testCode != 0) {
                onProgress("错误：备份文件已损坏或格式不支持")
                Log.e(TAG, "tar test failed (code=$testCode)")
                return false
            }

            // 清空旧环境
            onProgress("清除现有环境…")
            for (dirName in BACKUP_DIR_NAMES) {
                val dir = File(filesDir, dirName)
                try {
                    if (dir.exists()) dir.deleteRecursively()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear $dirName: ${e.message}")
                }
            }

            // 解压到 filesDir（系统 toybox tar 支持 -xzf）
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

            // 验证关键路径
            val bashOk = File(filesDir, "rootfs/ubuntu/bin/bash").exists()
            val hermesOk = File(filesDir, "home/hermes-agent/.venv/bin/activate").exists()
            if (!bashOk) {
                onProgress("错误：还原后缺少 rootfs/ubuntu/bin/bash，备份可能不完整")
                return false
            }
            // 刷新系统配置（resolv.conf/proc_fakes 等可能被覆盖或缺失）
            BootstrapManager.ensureSystemConfig(context)
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
