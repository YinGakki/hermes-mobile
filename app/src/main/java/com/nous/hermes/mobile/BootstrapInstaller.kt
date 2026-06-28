package com.nous.hermes.mobile

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Extracts the Termux bootstrap archive from APK assets into the app's private
 * data directory, creating a usable Linux-like prefix environment without root.
 *
 * Same extraction logic as Termux's TermuxInstaller.java:
 *   1. Extract all zip entries into a staging directory
 *   2. Parse SYMLINKS.txt and create symlinks
 *   3. Set execute permissions on bin/, libexec/, lib/apt/methods/
 *   4. Atomically rename staging -> final prefix
 *
 * Hermes-specific note: this file is identical to AnyClaw's BootstrapInstaller
 * except for the package name and TAG. The extraction logic is generic and
 * works for any applicationId.
 */
object BootstrapInstaller {

    private const val TAG = "HermesBootstrap"

    data class Paths(
        val filesDir: String,
        val prefixDir: String,
        val homeDir: String,
        val tmpDir: String,
    )

    fun getPaths(context: Context): Paths {
        val filesDir = context.filesDir.absolutePath
        return Paths(
            filesDir = filesDir,
            prefixDir = "$filesDir/usr",
            homeDir = "$filesDir/home",
            tmpDir = "$filesDir/usr/tmp",
        )
    }

    fun isBootstrapInstalled(context: Context): Boolean {
        val paths = getPaths(context)
        return File(paths.prefixDir).isDirectory && File(paths.prefixDir, "bin/sh").exists()
    }

    /**
     * Ensure critical system config files exist on every app launch.
     * Android may clear files in the app's filesDir between launches
     * (especially on low-memory devices), so resolv.conf and timezone
     * files might be missing even when the bootstrap is installed.
     *
     * Based on openclaw-termux's "rebuild resolv.conf on every start" pattern.
     */
    fun ensureSystemConfig(context: Context) {
        val paths = getPaths(context)
        val prefix = paths.prefixDir

        // Refresh resolv.conf if missing (DNS lookups will fail without it)
        val resolvConf = File(prefix, "etc/resolv.conf")
        if (!resolvConf.exists()) {
            try {
                resolvConf.parentFile?.mkdirs()
                resolvConf.writeText(
                    "nameserver 8.8.8.8\n" +
                    "nameserver 8.8.4.4\n" +
                    "nameserver 1.1.1.1\n" +
                    "options timeout:2 attempts:1\n"
                )
                Log.i(TAG, "Refreshed resolv.conf")
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh resolv.conf: ${e.message}")
            }
        }

        // Refresh timezone if missing (prevents interactive tzdata prompt)
        val timezoneFile = File(prefix, "etc/timezone")
        if (!timezoneFile.exists()) {
            try {
                timezoneFile.writeText("Etc/UTC\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh timezone: ${e.message}")
            }
        }

        // Refresh /etc/passwd if missing (dpkg/apt need it)
        val passwdFile = File(prefix, "etc/passwd")
        if (!passwdFile.exists()) {
            try {
                passwdFile.writeText(
                    "root:x:0:0:root:${paths.homeDir}:/bin/sh\n" +
                    "_apt:x:1:1:apt:/nonexistent:/bin/sh\n" +
                    "daemon:x:2:2:daemon:/nonexistent:/bin/sh\n"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh passwd: ${e.message}")
            }
        }
    }

    /**
     * Extract bootstrap-aarch64.zip from assets into the prefix directory.
     * Idempotent: if the prefix already exists, returns immediately.
     */
    fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ) {
        val paths = getPaths(context)
        val prefixFile = File(paths.prefixDir)

        if (prefixFile.isDirectory && File(prefixFile, "bin/sh").exists()) {
            Log.i(TAG, "Bootstrap already installed at ${paths.prefixDir}")
            return
        }

        onProgress("Extracting environment…")

        val stagingPath = "${paths.filesDir}/usr-staging"
        val stagingFile = File(stagingPath)

        if (stagingFile.exists()) {
            deleteRecursive(stagingFile)
        }

        val archName = determineArchName()
        val assetName = "bootstrap-$archName.zip"
        val termuxPrefix = "/data/data/com.termux/files/usr"

        Log.i(TAG, "Extracting $assetName to $stagingPath")

        val buffer = ByteArray(8192)
        val symlinks = mutableListOf<Pair<String, String>>()

        context.assets.open(assetName).use { assetStream ->
            ZipInputStream(assetStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "SYMLINKS.txt") {
                        val reader = BufferedReader(InputStreamReader(zip))
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split("←")
                            if (parts.size == 2) {
                                var target = parts[0]
                                if (target.startsWith(termuxPrefix)) {
                                    target = target.replace(termuxPrefix, paths.prefixDir)
                                }
                                val linkPath = "$stagingPath/${parts[1]}"
                                symlinks.add(target to linkPath)
                                val parentFile = File(linkPath).parentFile
                                if (parentFile != null) {
                                    ensureParentDir(parentFile)
                                }
                            }
                            line = reader.readLine()
                        }
                    } else {
                        val targetFile = File(stagingPath, entry.name)
                        val isDir = entry.isDirectory

                        ensureParentDir(if (isDir) targetFile else targetFile.parentFile!!)
                        if (isDir) {
                            targetFile.mkdirs()
                        } else {
                            FileOutputStream(targetFile).use { out ->
                                var len = zip.read(buffer)
                                while (len != -1) {
                                    out.write(buffer, 0, len)
                                    len = zip.read(buffer)
                                }
                            }
                            if (shouldBeExecutable(entry.name)) {
                                Os.chmod(targetFile.absolutePath, 0b111_000_000) // 0700
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }

        if (symlinks.isEmpty()) {
            throw RuntimeException("No SYMLINKS.txt found in bootstrap archive")
        }

        onProgress("Creating symlinks…")
        for ((target, linkPath) in symlinks) {
            try {
                val linkFile = File(linkPath)
                if (linkFile.exists() || linkFile.isDirectory) {
                    deleteRecursive(linkFile)
                }
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $linkPath -> $target: ${e.message}")
            }
        }

        if (prefixFile.exists()) {
            deleteRecursive(prefixFile)
        }
        if (!stagingFile.renameTo(prefixFile)) {
            throw RuntimeException("Failed to rename $stagingPath to ${paths.prefixDir}")
        }

        File(paths.homeDir).mkdirs()
        File(paths.tmpDir).mkdirs()

        onProgress("Configuring package manager…")
        fixTermuxPaths(paths)

        Log.i(TAG, "Bootstrap installed successfully at ${paths.prefixDir}")
    }

    /**
     * The bootstrap was built for com.termux with paths like
     * /data/data/com.termux/files/usr. Rewrite apt configuration
     * and dpkg metadata so they reference our actual prefix.
     */
    private fun fixTermuxPaths(paths: Paths) {
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        val aptConf = File(prefix, "etc/apt/apt.conf")
        aptConf.writeText(
            """
            Dir "/";
            Dir::State "$prefix/var/lib/apt/";
            Dir::State::status "$prefix/var/lib/dpkg/status";
            Dir::Cache "$prefix/var/cache/apt/";
            Dir::Log "$prefix/var/log/apt/";
            Dir::Etc "$prefix/etc/apt/";
            Dir::Etc::SourceList "$prefix/etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "$prefix/bin/dpkg";
            Dir::Bin::Methods "$prefix/lib/apt/methods/";
            Dir::Bin::apt-key "$prefix/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$prefix";
            Dpkg::Options:: "--force-not-root";
            Acquire::AllowInsecureRepositories "true";
            APT::Sandbox::Seccomp "false";
            APT::Sandbox::User "root";
            APT::Get::Assume-Yes "true";
            APT::Get::Allow-Unauthenticated "true";
            """.trimIndent() + "\n"
        )

        File(prefix, "var/log/apt").mkdirs()

        val sourcesList = File(prefix, "etc/apt/sources.list")
        if (sourcesList.exists()) {
            val content = sourcesList.readText()
            // Only downgrade https→http (Termux bootstrap's curl may not
            // have modern TLS). Do NOT replace com.termux in the URL —
            // Termux package repository URLs don't contain the applicationId;
            // they use packages.termux.dev which is applicationId-agnostic.
            sourcesList.writeText(content.replace("https://", "http://"))
        } else {
            // Write a default sources.list if missing
            sourcesList.writeText("deb http://packages.termux.dev/apt/termux-main/ stable main\n")
        }

        val dpkgStatus = File(prefix, "var/lib/dpkg/status")
        if (dpkgStatus.exists()) {
            val content = dpkgStatus.readText()
            dpkgStatus.writeText(content.replace(termuxPrefix, prefix))
        }

        File(prefix, "var/lib/dpkg/info").mkdirs()
        File(prefix, "var/lib/dpkg/updates").mkdirs()
        File(prefix, "var/lib/dpkg/triggers").mkdirs()
        File(prefix, "var/cache/apt/archives/partial").mkdirs()
        File(prefix, "var/lib/apt/lists/partial").mkdirs()

        val dpkgInfoDir = File(prefix, "var/lib/dpkg/info")
        if (dpkgInfoDir.isDirectory) {
            dpkgInfoDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".list")) {
                    try {
                        val text = file.readText()
                        if (text.contains(termuxPrefix)) {
                            file.writeText(text.replace(termuxPrefix, prefix))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fix ${file.name}: ${e.message}")
                    }
                }
            }
        }

        configureRootfsExtras(paths)

        Log.i(TAG, "Fixed Termux paths -> $prefix")
    }

    /**
     * Write tzdata, /etc/passwd, /etc/group, /etc/resolv.conf so that
     * dpkg/apt/pip don't hang on interactive prompts or fail DNS lookups.
     * Based on openclaw-termux BootstrapManager.configureRootfs().
     */
    private fun configureRootfsExtras(paths: Paths) {
        val prefix = paths.prefixDir

        // ── tzdata ──────────────────────────────────────────────────────
        // Without timezone config, Python3's tzdata package triggers an
        // interactive prompt during pip install, which hangs forever in
        // a non-interactive shell. Pre-set to UTC.
        try {
            val timezoneFile = File(prefix, "etc/timezone")
            timezoneFile.writeText("Etc/UTC\n")
            File(prefix, "etc/TZ").writeText("UTC0\n")
            // Link localtime if zoneinfo data exists in prefix
            val zoneinfoUtc = File(prefix, "share/zoneinfo/Etc/UTC")
            val localtimeFile = File(prefix, "etc/localtime")
            if (zoneinfoUtc.exists()) {
                localtimeFile.delete()
                Os.symlink(zoneinfoUtc.absolutePath, localtimeFile.absolutePath)
            } else {
                // No zoneinfo — write a minimal TZif2 stub (44-byte header
                // + newline) so Python's tzset() doesn't crash. Kotlin
                // doesn't support \0 in string literals, so build via
                // ByteArray.
                val stub = ByteArray(44) { 0 }.also { it[0] = 'T'.code.toByte() }
                localtimeFile.writeBytes(stub)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure timezone: ${e.message}")
        }

        // ── /etc/passwd + /etc/group ──────────────────────────────────
        // dpkg/apt need to resolve the current user. Without /etc/passwd,
        // getpwuid_r fails and apt tries to drop to _apt user, which
        // doesn't exist, causing "Operation not permitted" errors.
        try {
            val passwdFile = File(prefix, "etc/passwd")
            if (!passwdFile.exists()) {
                passwdFile.writeText(
                    "root:x:0:0:root:${paths.homeDir}:/bin/sh\n" +
                    "_apt:x:1:1:apt:/nonexistent:/bin/sh\n" +
                    "daemon:x:2:2:daemon:/nonexistent:/bin/sh\n"
                )
            }
            val groupFile = File(prefix, "etc/group")
            if (!groupFile.exists()) {
                groupFile.writeText(
                    "root:x:0:\n" +
                    "_apt:x:1:\n" +
                    "daemon:x:2:\n"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write passwd/group: ${e.message}")
        }

        // ── /etc/resolv.conf ───────────────────────────────────────────
        // Android doesn't have /etc/resolv.conf. Without it, DNS lookups
        // fail with "Temporary failure in name resolution". Write common
        // public DNS servers as a fallback — the app can also update this
        // later with the device's actual DNS servers.
        try {
            val resolvConf = File(prefix, "etc/resolv.conf")
            resolvConf.writeText(
                "nameserver 8.8.8.8\n" +
                "nameserver 8.8.4.4\n" +
                "nameserver 1.1.1.1\n" +
                "options timeout:2 attempts:1\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not write resolv.conf: ${e.message}")
        }
    }

    private fun shouldBeExecutable(entryName: String): Boolean {
        return entryName.startsWith("bin/") ||
            entryName.startsWith("libexec/") ||
            entryName.startsWith("lib/apt/methods/") ||
            entryName.startsWith("lib/bash/") ||
            entryName.endsWith(".so") ||
            entryName.contains("/bin/")
    }

    private fun ensureParentDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            if (!dir.isDirectory) {
                throw RuntimeException("Unable to create directory: ${dir.absolutePath}")
            }
        }
    }

    private fun determineArchName(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        throw RuntimeException(
            "Unsupported CPU architecture: ${Build.SUPPORTED_ABIS.joinToString()}"
        )
    }

    private fun deleteRecursive(fileOrDir: File) {
        // Safety: don't follow symlinks that might point outside the prefix.
        // If a symlink points to /sdcard or /storage, deleting it recursively
        // would delete the user's files. Just delete the link itself.
        // Based on openclaw-termux's symlink-safe deletion guard.
        val isSymlink = try {
            Os.readlink(fileOrDir.absolutePath)
            true  // readlink succeeded, so it's a symlink
        } catch (_: Exception) {
            false  // not a symlink
        }
        if (isSymlink) {
            fileOrDir.delete()
            return
        }
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        fileOrDir.delete()
    }
}
