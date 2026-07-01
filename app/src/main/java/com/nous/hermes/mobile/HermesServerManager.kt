package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the lifecycle of the Hermes Agent runtime inside the Termux
 * bootstrap environment. Handles installation of proot, Node.js, Python,
 * Hermes build dependencies (rust/clang/make/etc), and the Hermes Agent
 * package itself (via `pip install -e '.[termux]'`).
 *
 * Based on AnyClaw's CodexServerManager; the OpenClaw/Codex/Proxy/Login
 * sections were removed and replaced with Hermes-specific install steps
 * following the official Termux guide at
 * https://hermes-agent.nousresearch.com/docs/getting-started/termux
 */
class HermesServerManager(private val context: Context) {

    companion object {
        private const val TAG = "HermesServerManager"
        const val HERMES_PORT = 18789
        private const val HERMES_REPO = "https://github.com/NousResearch/hermes-agent.git"

        /**
         * Return the LD_PRELOAD path if libtermux-exec.so exists, or empty
         * string if not. Setting LD_PRELOAD to a non-existent path causes
         * every process to fail with "cannot locate executable".
         */
        private fun libTermuxExecPath(prefixDir: String): String {
            val lib = File(prefixDir, "lib/libtermux-exec.so")
            return if (lib.exists()) lib.absolutePath else ""
        }

        /**
         * Build the Termux-prefix environment map for use by other classes
         * (e.g. HermesStudioInstaller) that need to spawn processes in the
         * prefix without going through runInPrefix (e.g. for long-running
         * server processes that need their stdout drained in a custom way).
         */
        fun buildEnvMap(context: Context, paths: BootstrapInstaller.Paths): Map<String, String> {
            return mapOf(
                "PREFIX" to paths.prefixDir,
                "HOME" to paths.homeDir,
                "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
                "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
                // Only set LD_PRELOAD if libtermux-exec.so actually exists.
                // Setting it to a non-existent path causes EVERY process to
                // fail with "cannot locate executable". We check at init time
                // and set it to empty string if the file doesn't exist.
                // (Termux's libtermux-exec.so provides command-not-found handler
                // and aliases — non-critical for Hermes.)
                "LD_PRELOAD" to libTermuxExecPath(paths.prefixDir),
                "TERMUX_PREFIX" to paths.prefixDir,
                "TERMUX__PREFIX" to paths.prefixDir,
                "LANG" to "en_US.UTF-8",
                "TMPDIR" to paths.tmpDir,
                "TMP" to paths.tmpDir,
                "TEMP" to paths.tmpDir,
                "PROOT_TMP_DIR" to paths.tmpDir,
                "TERM" to "xterm-256color",
                "ANDROID_DATA" to "/data",
                "ANDROID_ROOT" to "/system",
                "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
                "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
                "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
                "SSL_CERT_DIR" to "/system/etc/security/cacerts",
                "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
                "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_EXEC_PATH" to "${paths.prefixDir}/libexec/git-core",
                "GIT_TEMPLATE_DIR" to "${paths.prefixDir}/share/git-core/templates",
                "OPENSSL_CONF" to "${paths.prefixDir}/etc/tls/openssl.cnf",
                "CONTAINER" to "1",
                "CARGO_HOME" to "${paths.homeDir}/.cargo",
                "RUSTUP_HOME" to "${paths.homeDir}/.rustup",
            )
        }
    }

    private var hermesProcess: Process? = null

    val isRunning: Boolean
        get() = hermesProcess?.let {
            try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        } ?: false

    // ── Shell helpers ──────────────────────────────────────────────────────

    /**
     * Run a shell command inside the Termux prefix environment.
     * Returns the exit code.
     */
    fun runInPrefix(
        command: String,
        timeoutMs: Long = 0,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val shell = "${paths.prefixDir}/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()

        // If timeout is set, start a watchdog thread that destroys the
        // process after the timeout. This prevents indefinite hangs on
        // DNS resolution, network timeouts, etc.
        var watchdog: Thread? = null
        if (timeoutMs > 0) {
            watchdog = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    if (proc.isAlive) {
                        Log.w(TAG, "runInPrefix timed out after ${timeoutMs}ms, killing process")
                        proc.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Normal — command finished before timeout
                }
            }.also { it.isDaemon = true; it.start() }
        }

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        val code = proc.waitFor()
        watchdog?.interrupt()
        return code
    }

    @Suppress("unused")
    private fun runCapture(command: String): String {
        val sb = StringBuilder()
        runInPrefix(command) { sb.appendLine(it) }
        return sb.toString().trim()
    }

    // ── Install checks ─────────────────────────────────────────────────────

    fun isProotInstalled(): Boolean =
        File(BootstrapInstaller.getPaths(context).prefixDir, "bin/proot").exists()

    fun isNodeInstalled(): Boolean =
        File(BootstrapInstaller.getPaths(context).prefixDir, "bin/node").exists()

    fun isPythonInstalled(): Boolean {
        val prefix = BootstrapInstaller.getPaths(context).prefixDir
        return File(prefix, "bin/python3").exists() || File(prefix, "bin/python").exists()
    }

    fun isHermesInstalled(): Boolean {
        // Only treat Hermes as "installed" when the wrapper script is on PATH.
        // git clone succeeding alone is NOT enough — pyproject.toml existing
        // doesn't mean `pip install -e .[termux]` finished. The wrapper is
        // the very last thing installHermes() creates, so its presence is a
        // reliable end-to-end success marker.
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/hermes").exists()
    }

    // ── proot ──────────────────────────────────────────────────────────────

    /**
     * Install proot from the Termux repository. proot uses ptrace to
     * intercept filesystem syscalls and remap hardcoded Termux paths
     * (e.g. /data/data/com.termux/files/usr) to our actual prefix,
     * enabling dpkg, apt-get install, and other tools that have
     * compiled-in path references.
     */
    fun installProot(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        // Retry the whole download+extract+verify cycle up to 3 times so a
        // transient Termux mirror blip doesn't surface to the user.
        return runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "install proot",
        ) {
            // Skip apt-get download if the bundle already staged debs in tmp/.
            if (!bundledDebsPresent()) {
                onProgress("Downloading proot…")
                val ok = aptGetDownloadWithMirrors(prefix, "proot libtalloc", onProgress)
                if (!ok) {
                    Log.e(TAG, "apt-get download proot failed on all mirrors")
                    return@runWithRetry false
                }
            } else {
                onProgress("Using bundled proot debs…")
            }

            onProgress("Extracting proot…")
            val extractCmd = """
                cd $prefix/tmp &&
                mkdir -p _proot_stage &&
                for deb in proot*.deb libtalloc*.deb; do
                    [ -f "${'$'}deb" ] || continue
                    dpkg-deb -x "${'$'}deb" _proot_stage/ 2>&1
                done &&
                if [ -d "_proot_stage$termuxPrefix" ]; then
                    cp -a _proot_stage$termuxPrefix/* "$prefix/" 2>&1
                elif [ -d "_proot_stage/usr" ]; then
                    cp -a _proot_stage/usr/* "$prefix/" 2>&1
                fi &&
                chmod 700 "$prefix/bin/proot" 2>/dev/null; rm -rf _proot_stage proot*.deb libtalloc*.deb 2>/dev/null
                echo "proot installed"
            """.trimIndent()
            val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
            if (extractCode != 0) {
                Log.e(TAG, "proot extract failed with code $extractCode")
                return@runWithRetry false
            }

            isProotInstalled()
        }
    }

    // ── Python ─────────────────────────────────────────────────────────────

    /**
     * Install Python + python-pip + all native transitive deps (libffi,
     * openssl, libsqlite, ncurses, libbz2, liblzma, libcrypt, libexpat,
     * readline, zlib, ...).
     *
     * Two code paths:
     *   1. BUNDLED (preferred): If the APK ships assets/python-bundle.tar.gz
     *      (pre-fetched at CI time via scripts/fetch-python-bundle.py),
     *      extract it to a staging dir and dpkg-deb -x every .deb in it.
     *      Fully offline — no apt-get needed at all.
     *   2. FALLBACK: `apt-get download python python-pip` + apt resolves
     *      transitive deps at install. Slower, requires network, only
     *      used when the bundle isn't bundled (e.g. local dev builds).
     *
     * Either path is wrapped in 3× retry to absorb transient failures.
     */
    fun installPython(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        return runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "install python",
        ) {
            // If extractDebBundleIfPresent() already staged debs in tmp/
            // (called once at setup start), skip apt-get download entirely.
            // Otherwise fall back to apt-get download python + python-pip.
            if (!bundledDebsPresent()) {
                onProgress("No bundled debs — downloading via apt-get…")
                // `apt-get download` does NOT resolve dependencies — every
                // transitive native lib python3 needs at runtime must be
                // listed explicitly. Missing any one causes an ImportError
                // at first `python -m pip` (e.g. libexpat.so.1 missing →
                // pyexpat.cpython-313-aarch64-linux-android.so fails to dlopen
                // → pip can't even start). This list mirrors what the full
                // flavor's fetch-python-bundle.py closure produces.
                val ok = aptGetDownloadWithMirrors(
                    prefix,
                    "python python-pip libffi openssl libsqlite ncurses " +
                        "libbz2 liblzma libcrypt readline zlib " +
                        "libexpat libandroid-shmem libtalloc",
                    onProgress,
                )
                if (!ok) {
                    Log.e(TAG, "apt-get download python failed on all mirrors")
                    return@runWithRetry false
                }
            }

            // Extract every .deb in $prefix/tmp into the prefix.
            // Works for both bundled (17 debs incl. transitive deps) and
            // apt-get-fetched (2 debs).
            onProgress("Extracting Python…")
            val extractCmd = """
                cd $prefix/tmp &&
                mkdir -p _python_stage &&
                for deb in *.deb; do
                    [ -f "${'$'}deb" ] || continue
                    echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _python_stage/ 2>&1
                done &&
                if [ -d "_python_stage$termuxPrefix" ]; then
                    cp -a _python_stage$termuxPrefix/* "$prefix/" 2>&1
                elif [ -d "_python_stage/usr" ]; then
                    cp -a _python_stage/usr/* "$prefix/" 2>&1
                fi &&
                chmod 700 "$prefix/bin/python"* 2>/dev/null
                # Make every pip* binary executable. Use a for-loop with a
                # nullglob-style guard (sh doesn't have nullglob, so check
                # existence) — `chmod 700 pip*` silently does nothing if the
                # glob doesn't expand, but the resulting `pip: Permission
                # denied` at install time is hard to diagnose. Iterate so we
                # also cover pip3, pip3.11, etc.
                for b in "$prefix/bin/pip" "$prefix/bin/pip3" "$prefix/bin/pip3.11"; do
                    [ -e "${'$'}b" ] && chmod 700 "${'$'}b" 2>/dev/null
                done
                rm -rf _python_stage *.deb 2>/dev/null
                echo "Python installed"
            """.trimIndent()
            val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
            if (extractCode != 0) {
                Log.e(TAG, "Python extract failed with code $extractCode")
                return@runWithRetry false
            }

            val fixCmd = """
                if [ -f "$prefix/bin/python3" ] && [ ! -f "$prefix/bin/python" ]; then
                    ln -sf python3 "$prefix/bin/python"
                fi
                echo "Python ready"
            """.trimIndent()
            runInPrefix(fixCmd, onOutput = { onProgress(it) })

            isPythonInstalled()
        }
    }

    /**
     * Extract the bundled deb cache (assets/deb-bundle.tar.gz) to $prefix/tmp/.
     * Called ONCE at the start of setup, before any install*() function runs.
     * After this returns true, every install*() function will find its .deb
     * files already in tmp/ and can skip the apt-get download phase entirely.
     *
     * Returns true if the bundle was extracted successfully, false if no
     * bundle is bundled (each install*() function will fall back to apt-get).
     */
    fun extractDebBundleIfPresent(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val assetManager = context.assets
        val tarballAsset = "deb-bundle.tar.gz"
        val hasTarball = try {
            assetManager.list("")?.any { it == tarballAsset } == true
        } catch (e: Exception) {
            false
        }
        if (!hasTarball) return false

        onProgress("Extracting bundled deb cache (deb-bundle.tar.gz)…")
        val tmpDir = File(prefix, "tmp")
        tmpDir.mkdirs()
        val outFile = File(tmpDir, tarballAsset)
        return try {
            assetManager.open(tarballAsset).use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            // Extract into tmp/ — all install*() functions' `for deb in *.deb`
            // loops will pick these up. --strip-components=1 drops the
            // top-level "deb-bundle/" dir created by `tar -czf ... -C $RUNNER_TEMP deb-bundle`.
            val code = runInPrefix(
                "cd $prefix/tmp && tar -xzf $tarballAsset --strip-components=1 2>&1",
                onOutput = { onProgress(it) },
            )
            outFile.delete()
            if (code != 0) {
                Log.w(TAG, "tar -xzf deb-bundle failed (code=$code) — falling back to apt-get per-step")
                return false
            }
            val debCount = tmpDir.listFiles { _, n -> n.endsWith(".deb") }?.size ?: 0
            onProgress("Deb bundle: $debCount debs staged in tmp/")
            debCount > 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract bundled deb-bundle.tar.gz: ${e.message}")
            outFile.delete()
            false
        }
    }

    /**
     * Check whether $prefix/tmp/ already contains .deb files (from a
     * previously-extracted bundle). Used by each install*() function to
     * decide whether to skip the apt-get download phase.
     */
    private fun bundledDebsPresent(): Boolean {
        val prefix = BootstrapInstaller.getPaths(context).prefixDir
        val tmpDir = File(prefix, "tmp")
        return tmpDir.listFiles { _, n -> n.endsWith(".deb") }?.isNotEmpty() == true
    }

    /**
     * apt-get download with mirror fallback. Used by proot/python/buildDeps
     * when no bundled debs are present.
     *
     * Mirror order:
     *   1. Official Termux CDN  (packages.termux.dev)
     *   2. Tsinghua mirror      (mirrors.tuna.tsinghua.edu.cn)
     *
     * For each mirror: rewrite sources.list → apt-get update → apt-get download.
     * Returns true if at least one .deb was downloaded successfully.
     */
    private fun aptGetDownloadWithMirrors(
        prefix: String,
        packages: String,
        onProgress: (String) -> Unit,
    ): Boolean {
        // 清华镜像优先（纯 HTTP，无 ca-certificates 证书问题）。
        // 官方 CDN 即使 sources.list 写 http:// 也会 301 重定向到 https://，
        // 而 bootstrap 没有 ca-certificates → 证书验证失败。
        val aptMirrors = listOf(
            "http://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/",
            "http://packages.termux.dev/apt/termux-main/",
        )
        for ((idx, mirror) in aptMirrors.withIndex()) {
            onProgress("apt-get: 尝试镜像 ${idx + 1}/${aptMirrors.size}: $mirror")
            runInPrefix("echo \"deb $mirror stable main\" > $prefix/etc/apt/sources.list")
            // 合并 update + download 为一条命令，不看 update 退出码。
            // 原因：apt-get update 对未签名仓库（--allow-insecure-repositories）
            // 即使成功下载了 InRelease + Packages，也可能因签名验证 warning
            // 返回非 0 退出码，导致误判失败。但只要 package list 实际更新了，
            // 后续 download 就能找到包 —— 所以以 download 退出码为准。
            // 另加 -o Acquire::https::Verify-Peer=false 处理官方 CDN 重定向
            // 到 HTTPS 的情况（无 ca-certificates 时跳过证书验证）。
            val cmd = """
                cd $prefix/tmp &&
                apt-get update \
                    --allow-insecure-repositories \
                    -o Acquire::https::Verify-Peer=false \
                    -o Acquire::https::Verify-Host=false 2>&1;
                apt-get download --allow-unauthenticated $packages 2>&1
            """.trimIndent()
            val code = runInPrefix(
                cmd,
                onOutput = { line ->
                    // Suppress noisy GPG/signature warnings
                    if (!line.contains("GPG error") &&
                        !line.contains("is not signed") &&
                        !line.contains("cannot be authenticated") &&
                        !line.contains("apt-key") &&
                        !line.startsWith("Ign:")
                    ) {
                        onProgress(line)
                    }
                },
            )
            if (code == 0) {
                onProgress("✓ 从 $mirror 下载成功")
                return true
            }
            Log.w(TAG, "apt-get download failed for $mirror (code=$code)")
            onProgress("镜像 $mirror 下载失败，尝试下一个…")
        }
        onProgress("错误：所有镜像均下载失败（$packages）")
        return false
    }

    // ── Node.js ───────────────────────────────────────────────────────────

    /**
     * Install Node.js LTS. Hermes declares nodejs as an optional runtime
     * (used for some MCP servers, Playwright, etc.). Same dpkg-deb manual
     * extraction approach as AnyClaw.
     */
    fun installNode(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir

        return runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "install node",
        ) {
            // Skip apt-get download if the bundle already staged debs in tmp/.
            if (!bundledDebsPresent()) {
                onProgress("Downloading Node.js packages…")
                val downloadCmd = """
                    cd $prefix/tmp &&
                    apt-get update --allow-insecure-repositories 2>&1 | grep -v 'GPG error\|is not signed\|cannot be authenticated\|apt-key\|Ign:' || true;
                    apt-get download --allow-unauthenticated c-ares libicu libsqlite nodejs npm 2>&1
                """.trimIndent()
                val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
                if (dlCode != 0) {
                    Log.e(TAG, "apt-get download failed with code $dlCode")
                    return@runWithRetry false
                }
            } else {
                onProgress("Using bundled node debs…")
            }

            onProgress("Extracting Node.js packages…")
            val termuxPrefix = "/data/data/com.termux/files/usr"
            val extractCmd = """
                cd $prefix/tmp &&
                mkdir -p _stage &&
                for deb in *.deb; do
                    [ -f "${'$'}deb" ] || continue
                    echo "Extracting ${'$'}deb..." &&
                    dpkg-deb -x "${'$'}deb" _stage/ 2>&1
                done &&
                if [ -d "_stage$termuxPrefix" ]; then
                    cp -a _stage$termuxPrefix/* "$prefix/" 2>&1
                elif [ -d "_stage/usr" ]; then
                    cp -a _stage/usr/* "$prefix/" 2>&1
                fi; rm -rf _stage *.deb 2>/dev/null
                echo "done"
            """.trimIndent()
            val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
            if (extractCode != 0) {
                Log.e(TAG, "dpkg-deb extract failed with code $extractCode")
                return@runWithRetry false
            }

            onProgress("Fixing npm wrapper script…")
            val fixCmd = """
                chmod 700 "$prefix/bin/node" 2>/dev/null

                NPM_CLI="$prefix/lib/node_modules/npm/bin/npm-cli.js"
                if [ -f "${'$'}NPM_CLI" ] && [ ! -f "$prefix/bin/npm" ]; then
                    cat > "$prefix/bin/npm" << WEOF
#!/system/bin/sh
exec ${'$'}{PREFIX}/bin/node ${'$'}{PREFIX}/lib/node_modules/npm/bin/npm-cli.js "\$@"
WEOF
                    chmod 700 "$prefix/bin/npm"
                fi

                echo "Wrapper scripts created"
            """.trimIndent()
            runInPrefix(fixCmd, onOutput = { onProgress(it) })

            isNodeInstalled()
        }
    }

    // ── Hermes build dependencies ─────────────────────────────────────────

    /**
     * Install all Termux packages needed for Hermes to build its
     * Python and Rust extensions. Follows the official Hermes Termux
     * guide:
     *
     *   pkg install -y git python clang rust make pkg-config libffi \
     *                  openssl nodejs ripgrep ffmpeg
     *
     * Plus the transitive build toolchain (cmake, lld, ndk-sysroot,
     * libllvm, libedit, libcompiler-rt) needed to compile native
     * Python wheels such as cffi, cryptography, etc.
     */
    fun installHermesBuildDeps(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        // Skip entirely if a previous run already finished this step
        // AND the marker version matches (package list may change between
        // releases — old marker without libngtcp2 must be invalidated).
        val depsMarker = File(prefix, "var/.hermes-deps-installed")
        val depsMarkerVersion = "v2"  // bump when package list changes
        if (depsMarker.exists()) {
            val markerContent = depsMarker.readText().trim()
            if (markerContent == depsMarkerVersion) {
                onProgress("Build dependencies already installed (cached, $depsMarkerVersion)")
                return true
            } else {
                onProgress("Build deps marker outdated ($markerContent → $depsMarkerVersion), re-downloading…")
            }
        }

        // If the bundle already staged debs in tmp/, skip the entire
        // apt-get update + download phase — just go to extract.
        // Otherwise fall back to the original apt-get download path.
        if (!bundledDebsPresent()) {
            onProgress("Downloading build dependencies…")

            // Official Hermes pkg list + transitive build tools needed to
            // compile native Python wheels (cryptography, cffi, etc).
            // clang/rust are included here but will be re-downloaded in
            // Phase 2 if wheel install fails — that's OK, the second download
            // is a no-op if the .deb is already in tmp/.
            val pkgGroups = listOf(
                // Hermes official Termux pkg list (ffmpeg skipped — Hermes core
                // doesn't need it, only the optional audio/video extras do,
                // and it's ~30MB we'd rather not download on lite).
                "git python clang rust make pkg-config libffi openssl nodejs ripgrep",
                // Transitive native build toolchain (needed by rust + cffi + cryptography)
                "cmake binutils lld libllvm libedit ndk-sysroot ndk-multilib libcompiler-rt",
                // Shared libs that some Hermes extras link against.
                // libngtcp2 is a transitive dep of libcurl (HTTP/3 support) —
                // must be explicitly listed because `apt-get download` does NOT
                // resolve dependencies, unlike `apt-get install -d`.
                // libexpat is needed by Python's pyexpat (pip imports it) —
                // without it `python -m pip` crashes with dlopen libexpat.so.1.
                "libarchive libxml2 liblzma libcurl libuv libnghttp2 libnghttp3 libngtcp2 libexpat",
                // Misc
                "rhash jsoncpp",
            )

            // Download each pkg group with mirror fallback. One group failing
            // doesn't abort the whole step — subsequent groups still get tried.
            for (group in pkgGroups) {
                val groupOk = aptGetDownloadWithMirrors(prefix, group, onProgress)
                if (!groupOk) {
                    Log.w(TAG, "apt-get download ($group) failed on all mirrors (non-fatal)")
                    onProgress("警告：$group 下载失败，继续尝试其他组…")
                }
            }

            // Verify downloaded debs are not corrupted. Large debs (rust ~96MB)
            // may be truncated on unstable networks. `dpkg-deb --info` parses
            // the control archive — if it fails, the deb is corrupted and
            // must be re-downloaded.
            onProgress("Verifying downloaded .deb files…")
            val verifyCmd = """
                cd $prefix/tmp
                for deb in *.deb; do
                    [ -f "${'$'}deb" ] || continue
                    if ! dpkg-deb --info "${'$'}deb" >/dev/null 2>&1; then
                        echo "CORRUPT: ${'$'}deb — re-downloading"
                        rm -f "${'$'}deb"
                        pkg=$(echo "${'$'}deb" | sed 's/_.*//')
                        apt-get download --allow-unauthenticated "${'$'}pkg" 2>&1 || echo "RE-DOWNLOAD FAILED: ${'$'}pkg"
                    fi
                done
                echo "Verification done"
            """.trimIndent()
            runInPrefix(verifyCmd, onOutput = { onProgress(it) })
        } else {
            // Bundle has most build deps, but rust/clang/ffmpeg were
            // excluded from the bundle (too big / too frequently updated).
            // They are now downloaded ON-DEMAND by installHermes() only if
            // the wheel-cache install path fails (i.e. a package couldn't
            // be installed from the pre-fetched manylinux wheels and needs
            // to be compiled from source). This avoids the ~600MB rust+clang
            // download in the common case where wheels work.
            onProgress("Using bundled build deps (rust/clang deferred to installHermes)")
        }

        onProgress("Extracting build dependencies…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _deps_stage &&
            _failed=0
            for deb in *.deb; do
                [ -f "${'$'}deb" ] || continue
                echo "Extracting ${'$'}deb..." 
                if ! dpkg-deb -x "${'$'}deb" _deps_stage/ 2>&1; then
                    echo "FAILED to extract ${'$'}deb — file may be corrupted"
                    _failed=1
                fi
            done
            if [ "${'$'}_failed" = "1" ]; then
                echo "EXTRACT_FAILED"
                exit 1
            fi
            if [ -d "_deps_stage$termuxPrefix" ]; then
                cp -a _deps_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_deps_stage/usr" ]; then
                cp -a _deps_stage/usr/* "$prefix/" 2>&1
            fi; rm -rf _deps_stage *.deb 2>/dev/null
            echo "Build deps installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            onProgress("Build deps extraction FAILED — some packages were corrupted during download")
            onProgress("Cleaning up stale marker and corrupted debs…")
            // Delete marker so next run re-downloads
            depsMarker.delete()
            // Clean up any partially extracted files
            runInPrefix("rm -rf $prefix/tmp/*.deb $prefix/tmp/_deps_stage 2>/dev/null") {}
            return false
        }

        // Verify critical binaries exist after extraction
        val gitBin = File(prefix, "bin/git")
        if (!gitBin.exists()) {
            onProgress("ERROR: git binary not found after build deps extraction")
            onProgress("The git .deb may have been corrupted or not downloaded")
            depsMarker.delete()
            return false
        }

        // Create symlinks for tools that expect different names
        runInPrefix("""
            [ ! -f "$prefix/bin/ar" ] && [ -f "$prefix/bin/llvm-ar" ] && ln -sf llvm-ar "$prefix/bin/ar"
            [ ! -f "$prefix/bin/ld" ] || [ -L "$prefix/bin/ld" ] && ln -sf ld.lld "$prefix/bin/ld"
            echo "Symlinks created"
        """.trimIndent())

        onProgress("Fixing git-core script shebangs…")
        fixGitCoreShebangs(prefix)

        onProgress("Patching make & cmake binaries…")
        patchBinaryTermuxPaths(prefix)

        onProgress("Creating header stubs…")
        createHeaderStubs(prefix)

        // Write the marker LAST — only after every fixup above succeeded.
        // On retry, the early-return check at the top of this function sees
        // the marker and skips the whole step.
        try {
            File(prefix, "var").mkdirs()
            depsMarker.writeText(depsMarkerVersion)
            onProgress("Marked build deps as installed ($depsMarkerVersion)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write deps marker: ${e.message}")
        }

        return true
    }

    /**
     * Retry helper with exponential backoff. Used for apt/pip operations
     * that fail transiently due to Termux repo mirror or PyPI flakiness.
     */
    internal fun runWithRetry(
        maxAttempts: Int,
        baseDelayMs: Long,
        onProgress: (String) -> Unit,
        what: String,
        action: () -> Boolean,
    ): Boolean {
        var lastError: Boolean = false
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                // A previous attempt likely died holding apt/dpkg locks
                // (apt-get can sit for minutes on a hung network read
                // before timing out). The next retry would immediately
                // hit "Could not get lock ... held by process N (apt-get)"
                // and fail the same way. Kill any leftover apt/dpkg and
                // remove the lock files before sleeping, so the retry
                // starts from a clean slate.
                killStaleAptProcesses(onProgress)
                val delay = baseDelayMs * (1L shl (attempt - 2))
                onProgress("Retry $attempt/$maxAttempts for $what (waiting ${delay}ms)…")
                try { Thread.sleep(delay) } catch (_: InterruptedException) {}
            }
            lastError = !action()
            if (!lastError) return true
        }
        return !lastError
    }

    /**
     * Kill any apt-get/dpkg processes still running from a previous attempt
     * and remove their lock files. Idempotent — safe to call when nothing
     * is running.
     */
    private fun killStaleAptProcesses(onProgress: (String) -> Unit) {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val cmd = """
            # Kill leftover apt-get / dpkg from a previous (failed) attempt.
            # `pgrep` is part of Termux's procps and is in the bootstrap.
            for p in ${'$'}(pgrep apt-get 2>/dev/null) ${'$'}(pgrep dpkg 2>/dev/null); do
                kill -9 "${'$'}p" 2>/dev/null
            done
            # Remove lock files they may have left behind.
            rm -f "$prefix/var/cache/apt/archives/lock" \
                  "$prefix/var/cache/apt/archives/lock-frontend" \
                  "$prefix/var/lib/apt/lists/lock" \
                  "$prefix/var/lib/dpkg/lock" \
                  "$prefix/var/lib/dpkg/lock-frontend" 2>/dev/null
            echo "cleaned apt locks"
        """.trimIndent()
        runInPrefix(cmd) { line -> Log.d(TAG, "[cleanup] $line") }
        onProgress("Cleared stale apt locks before retry")
    }

    private fun fixGitCoreShebangs(prefix: String) {
        val cmd = """
            cd "$prefix/libexec/git-core" 2>/dev/null || exit 0
            for f in git-*; do
                if head -1 "${'$'}f" 2>/dev/null | grep -q "com.termux"; then
                    sed -i "1s|/data/data/com.termux/files/usr|$prefix|" "${'$'}f"
                fi
            done
            echo "Git shebangs fixed"
        """.trimIndent()
        runInPrefix(cmd) { Log.d(TAG, "[fix-shebang] $it") }
    }

    private fun patchBinaryTermuxPaths(prefix: String) {
        val patchScript = """
            cat > "$prefix/tmp/_patchbin.py" << 'PYEOF'
import sys
with open(sys.argv[1], "rb") as f:
    data = f.read()
pairs = [
    (b"/data/data/com.termux/files/usr/bin/sh", b"/system/bin/sh"),
    (b"/data/data/com.termux/files/usr/bin/bash", b"/system/bin/sh"),
]
for old, new in pairs:
    padded = new + b"\x00" * (len(old) - len(new))
    data = data.replace(old, padded)
with open(sys.argv[1], "wb") as f:
    f.write(data)
print("patched " + sys.argv[1])
PYEOF
            for bin in "$prefix/bin/make" "$prefix/bin/cmake"; do
                [ -f "${'$'}bin" ] || continue
                python3 "$prefix/tmp/_patchbin.py" "${'$'}bin" 2>&1 && chmod 700 "${'$'}bin" 2>/dev/null
            done
            rm -f "$prefix/tmp/_patchbin.py"
        """.trimIndent()
        runInPrefix(patchScript) { Log.d(TAG, "[patch-bin] $it") }
    }

    private fun createHeaderStubs(prefix: String) {
        val cmd = """
            mkdir -p "$prefix/include/android"

            cat > "$prefix/include/android/api-level.h" << 'H1'
#pragma once
#define __ANDROID_API__ 24
H1

            cat > "$prefix/include/spawn.h" << 'H2'
#pragma once
#include <sys/types.h>
typedef struct { short __flags; pid_t __pgroup; } posix_spawnattr_t;
typedef struct { int __allocated; int __used; void **__actions; } posix_spawn_file_actions_t;
static inline int posix_spawn(pid_t *p,const char *path,const posix_spawn_file_actions_t *fa,const posix_spawnattr_t *a,char *const argv[],char *const envp[]){return -1;}
static inline int posix_spawnp(pid_t *p,const char *file,const posix_spawn_file_actions_t *fa,const posix_spawnattr_t *a,char *const argv[],char *const envp[]){return -1;}
static inline int posix_spawnattr_init(posix_spawnattr_t *a){return 0;}
static inline int posix_spawnattr_destroy(posix_spawnattr_t *a){return 0;}
static inline int posix_spawnattr_setflags(posix_spawnattr_t *a,short f){a->__flags=f;return 0;}
static inline int posix_spawnattr_setpgroup(posix_spawnattr_t *a,pid_t g){a->__pgroup=g;return 0;}
static inline int posix_spawn_file_actions_init(posix_spawn_file_actions_t *fa){return 0;}
static inline int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *fa){return 0;}
static inline int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *fa,int o,int n){return 0;}
static inline int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *fa,int f){return 0;}
#define POSIX_SPAWN_SETPGROUP 2
#define POSIX_SPAWN_SETSIGDEF 4
#define POSIX_SPAWN_SETSIGMASK 8
H2

            cat > "$prefix/include/renameat2_shim.h" << 'H3'
#pragma once
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <linux/fs.h>
static inline int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags) {
    return syscall(__NR_renameat2, olddirfd, oldpath, newdirfd, newpath, flags);
}
H3
            echo "Header stubs created"
        """.trimIndent()
        runInPrefix(cmd) { Log.d(TAG, "[headers] $it") }
    }

    // ── Hermes Agent ───────────────────────────────────────────────────────

    /**
     * Ensure libngtcp2 (and other libcurl transitive deps) are present.
     * If build deps were installed by an older APK that didn't include
     * libngtcp2 in the package list, git-remote-https crashes with
     * "cannot locate symbol ngtcp2_crypto_get_path_challenge_data2_cb".
     *
     * This function checks if libngtcp2.so exists in the prefix. If not,
     * it downloads and extracts just that one package (plus libngtcp2-crypto
     * if it exists as a separate package).
     */
    private fun ensureCurlDeps(prefix: String, onProgress: (String) -> Unit) {
        // Quick filesystem check — if libngtcp2.so exists, we're good
        val libngtcp2 = File(prefix, "lib/libngtcp2.so")
        if (libngtcp2.exists()) return

        onProgress("libngtcp2.so missing — downloading (libcurl HTTP/3 dependency)…")
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val cmd = """
            cd $prefix/tmp &&
            apt-get download --allow-unauthenticated libngtcp2 2>&1 &&
            for deb in libngtcp2*.deb; do
                [ -f "${'$'}deb" ] || continue
                dpkg-deb -x "${'$'}deb" _ngtcp2_stage/ 2>&1
            done &&
            if [ -d "_ngtcp2_stage$termuxPrefix" ]; then
                cp -a _ngtcp2_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_ngtcp2_stage/usr" ]; then
                cp -a _ngtcp2_stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _ngtcp2_stage libngtcp2*.deb 2>/dev/null
        """.trimIndent()
        val code = runInPrefix(cmd, onOutput = { onProgress(it) })
        if (code != 0) {
            Log.w(TAG, "ensureCurlDeps: libngtcp2 download failed (code=$code)")
            onProgress("Warning: libngtcp2 download failed — git clone may fail, will use tarball fallback")
        } else {
            onProgress("libngtcp2 installed")
        }
    }

    /**
     * Ensure Python's native runtime libraries are present.
     *
     * If Python was installed by an older APK version (or by a
     * runInstallAll that skipped installPython because bin/python3
     * already existed), critical .so files may be missing. The most
     * common one is libexpat.so.1 — without it `python -m pip` crashes
     * at startup because pip imports xmlrpc.client → pyexpat → libexpat.
     *
     * This checks each known-critical lib and downloads the matching
     * Termux package on demand if the .so is absent. Safe to call
     * repeatedly — it's a no-op once all libs are present.
     */
    private fun ensurePythonRuntimeDeps(prefix: String, onProgress: (String) -> Unit) {
        // Map of (missing .so path) -> (Termux package name to download).
        // These are the libs Python's stdlib dlopens at import time —
        // missing any one makes `python -m pip` (and thus the whole
        // Hermes install) fail before it can do anything useful.
        val requiredLibs = listOf(
            "lib/libexpat.so.1" to "libexpat",
            "lib/libffi.so" to "libffi",
            "lib/libssl.so" to "openssl",
            "lib/libsqlite.so" to "libsqlite",
            "lib/libcrypto.so" to "openssl",
            "lib/libncursesw.so" to "ncurses",
            "lib/libbz2.so.1.0" to "libbz2",
            "lib/liblzma.so" to "liblzma",
            "lib/libz.so.1" to "zlib",
            "lib/libreadline.so" to "readline",
        )
        val missing = requiredLibs.filter { !File(prefix, it.first).exists() }.map { it.second }.distinct()
        if (missing.isEmpty()) return

        onProgress("Python native libs missing: ${missing.joinToString()} — downloading…")
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val pkgsArg = missing.joinToString(" ")
        val cmd = """
            cd $prefix/tmp &&
            apt-get download --allow-unauthenticated $pkgsArg 2>&1 &&
            mkdir -p _pylibs_stage &&
            for deb in *.deb; do
                [ -f "${'$'}deb" ] || continue
                case "${'$'}deb" in
                    libexpat*|libffi*|openssl*|libsqlite*|ncurses*|libbz2*|liblzma*|zlib*|readline*) dpkg-deb -x "${'$'}deb" _pylibs_stage/ 2>&1 ;;
                esac
            done &&
            if [ -d "_pylibs_stage$termuxPrefix" ]; then
                cp -a _pylibs_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_pylibs_stage/usr" ]; then
                cp -a _pylibs_stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _pylibs_stage *.deb 2>/dev/null
            echo "Python native libs installed"
        """.trimIndent()
        val rc = runInPrefix(cmd, onOutput = { onProgress(it) })
        if (rc != 0) {
            Log.w(TAG, "ensurePythonRuntimeDeps: download failed (code=$rc)")
            onProgress("Warning: some Python native libs failed to install — pip may crash")
        } else {
            onProgress("Python native libs installed")
        }
    }

    /**
     * Configure git to rewrite SSH GitHub URLs to HTTPS (we don't have ssh
     * in our prefix). Required because Hermes git clone may pull submodules
     * over SSH.
     */
    private fun configureGitHttps(paths: BootstrapInstaller.Paths) {
        val gitconfigFile = File(paths.homeDir, ".gitconfig")
        val desired = """
            |[url "https://github.com/"]
            |	insteadOf = ssh://git@github.com/
            |	insteadOf = git@github.com:
        """.trimMargin()
        val existing = if (gitconfigFile.exists()) gitconfigFile.readText() else ""
        if (!existing.contains("insteadOf = ssh://git@github.com")) {
            gitconfigFile.appendText("\n$desired\n")
        }
    }

    /**
     * If the APK ships a bundled wheel cache (assets/wheels/ dir of .whl
     * files, or assets/wheels.tar.gz), extract it to a local directory and return
     * that path so installHermes can pass `--no-index --find-links=<dir>`
     * to pip. This lets the install run fully offline for Hermes deps.
     *
     * Returns null if no wheel cache is bundled (fall back to PyPI).
     */
    private fun setupWheelCacheIfPresent(prefix: String, onProgress: (String) -> Unit): String? {
        val assetManager = context.assets
        val wheelsDir = File(prefix, "var/wheels")

        // Check if wheel cache is bundled as a tarball first (preferred —
        // single file, smaller in APK because compressible).
        val tarballAsset = "wheels.tar.gz"
        val hasTarball = try {
            assetManager.list("")?.any { it == tarballAsset } == true
        } catch (e: Exception) {
            false
        }
        if (hasTarball) {
            onProgress("Extracting bundled wheel cache (wheels.tar.gz)…")
            wheelsDir.mkdirs()
            val outFile = File(wheelsDir, tarballAsset)
            try {
                assetManager.open(tarballAsset).use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                }
                // NOTE: outFile is a Kotlin File object, NOT a shell variable.
                // Earlier code used $outFile in the shell string, which expanded
                // to empty string and made tar fail silently. Use Kotlin string
                // interpolation to pass the absolute path directly.
                val tarCode = runInPrefix(
                    "tar -xzf ${outFile.absolutePath} -C ${wheelsDir.absolutePath} 2>&1",
                    onOutput = { onProgress(it) },
                )
                outFile.delete()
                if (tarCode != 0) {
                    Log.w(TAG, "tar -xzf wheels.tar.gz failed (code=$tarCode) — falling back to PyPI")
                    wheelsDir.deleteRecursively()
                    return null
                }
                val wheelCount = wheelsDir.listFiles { _, n -> n.endsWith(".whl") }?.size ?: 0
                onProgress("Wheel cache: $wheelCount wheels extracted")
                if (wheelCount == 0) {
                    Log.w(TAG, "Wheel cache extracted but contains 0 .whl files — ignoring")
                    wheelsDir.deleteRecursively()
                    return null
                }
                return wheelsDir.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract bundled wheels.tar.gz: ${e.message}")
                outFile.delete()
                wheelsDir.deleteRecursively()
            }
        }

        // Otherwise check for a plain directory of wheels in assets/wheels/.
        val hasWheelsDir = try {
            assetManager.list("wheels")?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
        if (hasWheelsDir) {
            onProgress("Copying bundled wheel cache (assets/wheels/)…")
            wheelsDir.mkdirs()
            try {
                assetManager.list("wheels")?.forEach { name ->
                    assetManager.open("wheels/$name").use { input ->
                        File(wheelsDir, name).outputStream().use { input.copyTo(it) }
                    }
                }
                val wheelCount = wheelsDir.listFiles { _, n -> n.endsWith(".whl") }?.size ?: 0
                onProgress("Wheel cache: $wheelCount wheels copied")
                return wheelsDir.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy bundled wheels/: ${e.message}")
                wheelsDir.deleteRecursively()
            }
        }

        return null
    }

    /**
     * Install Hermes Agent following the official Termux guide:
     *
     *   git clone https://github.com/NousResearch/hermes-agent.git
     *   cd hermes-agent
     *   python -m pip install -e '.[termux]' -c constraints-termux.txt
     *
     * The constraints file pins versions known to build cleanly on
     * Android. We rely on the in-repo constraints-termux.txt.
     */
    fun installHermes(
        onProgress: (String) -> Unit,
        onNeedCompile: () -> Boolean = { true },
    ): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val homeDir = paths.homeDir

        // Prepare directories Hermes expects
        runInPrefix("mkdir -p $prefix/tmp ${homeDir}/.hermes")

        // Configure git HTTPS rewrite
        configureGitHttps(paths)

        // systemctl stub (Hermes may check for systemd)
        val systemctlStub = File(prefix, "bin/systemctl")
        if (!systemctlStub.exists()) {
            systemctlStub.writeText(
                "#!/system/bin/sh\nexit 0\n"
            )
            systemctlStub.setExecutable(true)
        }

        // Ensure libngtcp2 is present — libcurl.so depends on it for HTTP/3.
        // If build deps were installed by an older APK version that didn't
        // include libngtcp2 in the package list, git-remote-https will crash
        // with "cannot locate symbol ngtcp2_crypto_get_path_challenge_data2_cb".
        // This is a targeted fix that downloads just the missing lib.
        ensureCurlDeps(prefix, onProgress)

        // Ensure Python's native runtime libs are present. If Python was
        // installed by an older APK (or skipped because bin/python3 already
        // existed), libexpat.so.1 may be missing — and `python -m pip`
        // crashes at startup importing pyexpat. Download on demand.
        ensurePythonRuntimeDeps(prefix, onProgress)

        // Clone Hermes (idempotent + retry). GitHub may be blocked in some
        // networks (e.g. GFW in China), so we try multiple clone URLs
        // including Chinese mirror sites. Falls back to tarball download
        // (also with mirror URLs) if git-remote-https is broken.
        if (!File(homeDir, "hermes-agent/pyproject.toml").exists()) {
            // Git clone URLs — try official first, then Chinese mirrors
            val cloneUrls = listOf(
                HERMES_REPO,
                "https://kkgithub.com/NousResearch/hermes-agent.git",
                "https://bgithub.xyz/NousResearch/hermes-agent.git",
            )
            var cloneOk = false
            for (cloneUrl in cloneUrls) {
                cloneOk = runWithRetry(
                    maxAttempts = 2,
                    baseDelayMs = 3000L,
                    onProgress = onProgress,
                    what = "git clone from ${cloneUrl.substringAfter("://").substringBefore("/")}",
                ) {
                    onProgress("Cloning Hermes Agent repository from $cloneUrl…")
                    runInPrefix(
                        "cd ${homeDir} && rm -rf hermes-agent && git clone --depth 1 $cloneUrl hermes-agent 2>&1",
                        onOutput = { onProgress(it) },
                    ) == 0 && File(homeDir, "hermes-agent/pyproject.toml").exists()
                }
                if (cloneOk) break
                onProgress("Clone from ${cloneUrl.substringAfter("://").substringBefore("/")} failed, trying next mirror…")
            }

            if (!cloneOk) {
                Log.w(TAG, "git clone failed from all mirrors — falling back to tarball download")
                onProgress("Git clone failed, trying tarball download…")
                val tarballOk = runWithRetry(
                    maxAttempts = 3,
                    baseDelayMs = 3000L,
                    onProgress = onProgress,
                    what = "tarball download hermes-agent",
                ) {
                    // Download tarball via Java HttpURLConnection (completely
                    // bypasses Termux's libcurl). Try multiple URLs —
                    // github.com may be blocked in some networks (GFW),
                    // so Chinese mirror proxies are tried as fallback.
                    val tarballUrls = listOf(
                        "https://github.com/NousResearch/hermes-agent/archive/refs/heads/main.tar.gz",
                        "https://gh-proxy.com/https://github.com/NousResearch/hermes-agent/archive/refs/heads/main.tar.gz",
                        "https://kkgithub.com/NousResearch/hermes-agent/archive/refs/heads/main.tar.gz",
                        "https://codeload.github.com/NousResearch/hermes-agent/tar.gz/refs/heads/main",
                    )
                    val tarballFile = File(paths.tmpDir, "hermes-agent.tar.gz")
                    var downloaded = false
                    for (url in tarballUrls) {
                        try {
                            val host = url.substringAfter("://").substringBefore("/")
                            onProgress("Downloading tarball from $host…")
                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 20000
                            conn.readTimeout = 120000
                            conn.instanceFollowRedirects = true
                            conn.requestMethod = "GET"
                            if (conn.responseCode != 200) {
                                onProgress("HTTP ${conn.responseCode} from $host")
                                conn.disconnect()
                                continue
                            }
                            conn.inputStream.use { input ->
                                tarballFile.outputStream().use { input.copyTo(it) }
                            }
                            conn.disconnect()
                            val tarballSize = tarballFile.length()
                            if (tarballSize < 1024) {
                                onProgress("Tarball too small (${tarballSize} bytes) — likely an error page")
                                tarballFile.delete()
                                continue
                            }
                            onProgress("Tarball downloaded (${tarballSize / 1024}KB) from $host")
                            downloaded = true
                            break
                        } catch (e: Exception) {
                            val host = url.substringAfter("://").substringBefore("/")
                            onProgress("Download from $host failed: ${e.message}")
                            Log.w(TAG, "Tarball download from $host failed: ${e.message}")
                            tarballFile.delete()
                        }
                    }
                    if (!downloaded) {
                        onProgress("All tarball download URLs failed")
                        return@runWithRetry false
                    }
                    onProgress("Extracting tarball…")
                    // Extract with prefix's tar (doesn't need libcurl)
                    val extractCode = runInPrefix(
                        "cd ${homeDir} && rm -rf hermes-agent && tar -xzf ${tarballFile.absolutePath} -C ${homeDir} 2>&1 && mv hermes-agent-main hermes-agent 2>/dev/null || true",
                        onOutput = { onProgress(it) },
                    )
                    if (extractCode != 0) {
                        onProgress("tar extract failed (code=$extractCode)")
                    }
                    tarballFile.delete()
                    val pyprojectExists = File(homeDir, "hermes-agent/pyproject.toml").exists()
                    if (!pyprojectExists) {
                        onProgress("pyproject.toml not found after extraction — listing ${homeDir}:")
                        runInPrefix("ls -la ${homeDir}/ 2>&1 | head -20") { onProgress(it) }
                    }
                    extractCode == 0 && pyprojectExists
                }
                if (!tarballOk) {
                    Log.e(TAG, "Both git clone and tarball download failed for hermes-agent")
                    return false
                }
                onProgress("Hermes Agent downloaded via tarball (git clone fallback)")
            }
        } else {
            // The existing hermes-agent/ dir may have come from `git clone`
            // (has a .git/) OR from a tarball fallback (no .git/). Only
            // attempt `git pull` when it's actually a git repo — otherwise
            // git prints "fatal: not a git repository" and the user thinks
            // something is broken. Tarball dirs are left as-is; reinstall
            // is handled by pip below.
            if (File("${homeDir}/hermes-agent/.git").isDirectory) {
                onProgress("Hermes repository already present, pulling latest…")
                runInPrefix("cd ${homeDir}/hermes-agent && git pull --ff-only 2>&1") { onProgress(it) }
            } else {
                onProgress("Hermes Agent already present (tarball), skipping git pull…")
            }
        }

        // Patch sys.platform for Python 3.13+ on Android.
        // Python 3.13 reports sys.platform="android", but many packages
        // (psutil, etc.) only recognize "linux" in their setup.py and fail
        // with "platform android is not supported".
        //
        // sitecustomize.py is auto-imported by Python at startup (via the
        // site module). Placing it in the SYSTEM site-packages (not the
        // venv's) ensures it's loaded by:
        //   - the venv python (venv was created with --system-site-packages)
        //   - pip's build-isolation subprocesses (which inherit sys.path
        //     from the parent interpreter, including system site-packages)
        // PYTHONPATH-based approach was unreliable because pip's build
        // isolation can strip PYTHONPATH from the subprocess environment.
        val sysSitePackages = File(prefix, "lib/python3.13/site-packages")
        sysSitePackages.mkdirs()
        val siteCustomize = File(sysSitePackages, "sitecustomize.py")
        try {
            siteCustomize.writeText(
                """
                import sys
                # Python 3.13+ on Android reports sys.platform as "android".
                # Many packages (psutil, etc.) only check for "linux" — patch
                # sys.platform so they build correctly.
                if sys.platform == "android":
                    sys.platform = "linux"
                """.trimIndent() + "\n",
            )
            Log.i(TAG, "sitecustomize.py written to ${siteCustomize.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write sitecustomize.py: ${e.message}")
            // Non-fatal — some packages may fail to build without this patch
        }

        // Reuse existing venv if it looks healthy. Recreating it would
        // throw away packages already installed by a previous (possibly
        // partial) run and force pip to redownload everything. pip install
        // is idempotent on an existing venv — already-installed packages
        // are skipped, so resuming on the existing venv is both safe and
        // faster. Only (re)create when the activate script is missing.
        val venvActivate = File("${homeDir}/hermes-agent/.venv/bin/activate")
        if (venvActivate.exists()) {
            onProgress("Python venv already exists, reusing…")
        } else {
            // NOTE: do NOT use plain `python -m venv .venv` here.
            // venv runs `ensurepip` internally, and ensurepip ships manylinux
            // x86_64 wheels that fail to install on Android aarch64 — the
            // venv ends up half-created with no bin/activate, and every
            // subsequent `pip install` fails with "cannot open .venv/bin/activate".
            // The well-known Termux workaround is `--without-pip` (skip the
            // broken ensurepip) + `--system-site-packages` (let the venv see
            // the system pip we installed via the python-pip deb). pip then
            // resolves to the system pip, and `pip install` still targets the
            // venv site-packages (venv takes precedence in sys.path), so
            // isolation is preserved for Hermes' deps.
            onProgress("Creating Python venv (--without-pip, using system pip)…")
            val venvCode = runInPrefix(
                "cd ${homeDir}/hermes-agent && rm -rf .venv && " +
                    "python -m venv --without-pip --system-site-packages .venv 2>&1",
                onOutput = { onProgress(it) },
            )
            if (venvCode != 0 || !venvActivate.exists()) {
                onProgress("venv creation failed (exit=$venvCode), retrying with --without-pip only…")
                runInPrefix(
                    "cd ${homeDir}/hermes-agent && rm -rf .venv && " +
                        "python -m venv --without-pip .venv 2>&1",
                    onOutput = { onProgress(it) },
                )
            }
            if (!venvActivate.exists()) {
                Log.e(TAG, "venv creation failed — .venv/bin/activate missing")
                onProgress("ERROR: venv creation failed — .venv/bin/activate is missing")
                return false
            }
            // Sanity-check that pip is reachable from the venv python.
            // With --system-site-packages this should always be true; with
            // plain --without-pip it may not be (no pip in venv, no system
            // fallback). Detect early instead of failing cryptically in Phase 1.
            val pipCheck = runInPrefix(
                "cd ${homeDir}/hermes-agent && .venv/bin/python -m pip --version 2>&1",
                onOutput = { onProgress(it) },
            )
            if (pipCheck != 0) {
                onProgress("venv has no pip — bootstrapping via get-pip.py…")
                val bootstrapOk = runInPrefix(
                    "cd ${homeDir}/hermes-agent && " +
                        "curl -fsSL https://bootstrap.pypa.io/get-pip.py -o get-pip.py && " +
                        ".venv/bin/python get-pip.py 2>&1 && rm -f get-pip.py",
                    timeoutMs = 120000,
                    onOutput = { onProgress(it) },
                )
                if (bootstrapOk != 0) {
                    Log.e(TAG, "pip bootstrap via get-pip.py failed (exit=$bootstrapOk)")
                    onProgress("ERROR: failed to bootstrap pip into venv")
                    return false
                }
            }
        }

        // Try installing from bundled wheel cache first (if the APK
        // shipped assets/wheels/), else fall back to PyPI download.
        // Either way: retry up to 3 times.
        //
        // We use --find-links WITHOUT --no-index so pip PREFERS the local
        // wheel cache (fast + reliable for the pure-python deps that make
        // up the bulk of the install) but still falls back to PyPI for
        // packages missing from the cache or whose only cached wheel is an
        // incompatible platform (e.g. manylinux x86_64 wheels can't install
        // on Android aarch64 — pip skips those and fetches the right one).
        // --no-index would make pip fail hard on those, breaking the install.
        //
        // PyPI mirror: use Tsinghua's mirror (pypi.tuna.tsinghua.edu.cn/simple)
        // by default. The default PyPI (pypi.org/simple) is slow / unreliable
        // from mainland China — connections to fastly.net CDN nodes often
        // time out or hang for minutes mid-download, which previously caused
        // the 10-minute pip watchdog to fire and fail the install. The
        // Tsinghua mirror is hosted inside China, fast, and pip-compatible.
        // --trusted-host is required because pip refuses to send credentials
        // to a non-HTTPS-default host (and the cert chain on Android's
        // system trust store may not match if it's intercepted).
        val wheelCacheDir = setupWheelCacheIfPresent(prefix, onProgress)
        val pipArgs = buildString {
            if (wheelCacheDir != null) append("--find-links=$wheelCacheDir ")
            append("-i https://pypi.tuna.tsinghua.edu.cn/simple ")
            append("--trusted-host pypi.tuna.tsinghua.edu.cn")
        }.trim()
        onProgress("Phase 1: try installing from wheel cache (no compile needed)…")
        val phase1Output = StringBuilder()
        val installOk = runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 5000L,
            onProgress = onProgress,
            what = "pip install hermes (termux)",
        ) {
            onProgress("Installing Hermes (pip install -e .[termux]) — this may take a minute…")
            phase1Output.clear()
            // Use `python -m pip` (NOT bare `pip`):
            // - The venv was created with --without-pip, so there's no
            //   `pip` binary inside .venv/bin/. Bare `pip` resolves via
            //   PATH to the system $prefix/bin/pip, which may lack the
            //   executable bit (chmod 700 pip* glob can miss it depending
            //   on the deb's filename), giving "pip: Permission denied".
            // - `python -m pip` invokes the pip *module* through the
            //   python interpreter, which only needs python's executable
            //   bit (always set correctly). It resolves pip from
            //   sys.path — the venv's --system-site-packages picks up the
            //   system pip module. Packages still install into the venv.
            val cmd = """
                cd ${homeDir}/hermes-agent &&
                . .venv/bin/activate &&
                python -m pip install --retries 3 --timeout 60 $pipArgs -e '.[termux]' -c constraints-termux.txt 2>&1
            """.trimIndent()
            // 10-minute timeout: PyPI downloads can be slow on mobile networks
            // but an indefinite hang (DNS stuck, mirror down) makes the app
            // look frozen. pip prints progress per-package, so 10 min is plenty.
            runInPrefix(cmd, timeoutMs = 10 * 60 * 1000L, onOutput = {
                onProgress(it)
                phase1Output.appendLine(it)
            }) == 0
        }

        // Phase 2 fallback: if Phase 1 failed, compile native packages from
        // source. This is needed because PyPI has NO Android-aarch64 wheels
        // for native deps (cryptography/cffi/pydantic-core/...) — they must
        // be built on-device with rust+clang.
        //
        // WHEN TO ASK THE USER:
        //   - full flavor (wheel cache present): Phase 1 failing is an
        //     anomaly (the cache should have covered pure-python deps, and
        //     native ones should have fallen back to PyPI). Worth asking
        //     before burning 600MB on rust/clang.
        //   - lite flavor (NO wheel cache): Phase 1 was guaranteed to fail
        //     on native packages — there are no Android wheels on PyPI, and
        //     there's no cache to fall back to. Source compile is the ONLY
        //     path forward, so asking is just noise. Auto-approve.
        if (!installOk) {
            // Surface the last few pip error lines so the user can see
            // WHY Phase 1 failed (e.g. "ERROR: Could not build wheel for
            // cryptography" or "no matching distribution for ...").
            val tailLines = phase1Output.lines()
                .takeLast(15)
                .filter { it.isNotBlank() }
            onProgress("════════════════════════════════════════")
            onProgress("Phase 1 FAILED (no Android-native wheels for crypto/cffi/...). Last pip output:")
            tailLines.forEach { onProgress("  $it") }
            onProgress("════════════════════════════════════════")

            val hadWheelCache = wheelCacheDir != null
            val approved = if (hadWheelCache) {
                // full flavor: Phase 1 failing is unexpected — let the user
                // decide whether to burn 600MB on the toolchain.
                Log.w(TAG, "pip install from wheel cache failed — asking user about source compile")
                onNeedCompile()
            } else {
                // lite flavor: no wheel cache → Phase 1 was always going to
                // fail on native packages → source compile is mandatory.
                // Skip the dialog and proceed directly.
                onProgress("Lite 版无预编译 wheel 缓存，native 包必须从源码编译 — 自动继续…")
                Log.i(TAG, "lite flavor: auto-approving source compile (no wheel cache, no Android wheels on PyPI)")
                true
            }
            if (!approved) {
                Log.e(TAG, "User declined source compile — install aborted")
                return false
            }

            onProgress("Phase 2: downloading rust+clang + compiling from source…")
            val compileDepsOk = downloadAndInstallCompileToolchain(prefix, onProgress)
            if (!compileDepsOk) {
                Log.e(TAG, "Failed to download rust/clang — cannot compile")
                return false
            }

            // Retry pip install, this time forcing sdist compile for the
            // native packages that failed before. --no-binary ensures pip
            // won't reuse the (broken) manylinux wheels.
            onProgress("Compiling native packages from source (may take 5-10 min)…")
            val compileOk = runWithRetry(
                maxAttempts = 2,
                baseDelayMs = 5000L,
                onProgress = onProgress,
                what = "pip install (source compile)",
            ) {
                val cmd = """
                    cd ${homeDir}/hermes-agent &&
                    . .venv/bin/activate &&
                    python -m pip install --retries 3 --timeout 60 $pipArgs --no-binary=:all: -e '.[termux]' -c constraints-termux.txt 2>&1
                """.trimIndent()
                // 30-minute timeout for source compile: building cryptography,
                // pydantic-core, cffi from source is CPU-heavy and can take
                // 5-10 min per package on a phone. 30 min covers the full set.
                runInPrefix(cmd, timeoutMs = 30 * 60 * 1000L, onOutput = { onProgress(it) }) == 0
            }
            if (!compileOk) {
                Log.e(TAG, "pip install (source compile) failed after retries")
                return false
            }
        }

        // Link hermes binary onto PATH so it's discoverable from any shell
        onProgress("Linking hermes binary…")
        val linkCmd = """
            cd ${homeDir}/hermes-agent &&
            . .venv/bin/activate &&
            HERMES_BIN="${'$'}(which hermes 2>/dev/null)" &&
            if [ -n "${'$'}HERMES_BIN" ] && [ ! -f "$prefix/bin/hermes" ]; then
                cat > "$prefix/bin/hermes" << WEOF
#!/system/bin/sh
exec ${'$'}HERMES_BIN "\$@"
WEOF
                chmod 700 "$prefix/bin/hermes"
                echo "hermes wrapper created at $prefix/bin/hermes"
            else
                echo "hermes binary not found in venv (install may have failed)"
            fi
        """.trimIndent()
        runInPrefix(linkCmd, onOutput = { onProgress(it) })

        return isHermesInstalled()
    }

    /**
     * Download + install rust + clang + ffmpeg via apt-get. Used ONLY as
     * a fallback when the wheel-cache install path fails and the user
     * approves the ~600MB download. Returns true on success.
     */
    private fun downloadAndInstallCompileToolchain(
        prefix: String,
        onProgress: (String) -> Unit,
    ): Boolean {
        val termuxPrefix = "/data/data/com.termux/files/usr"

        // clang + rust (~570MB) are NOT bundled in the APK — bundling them
        // would inflate the APK from ~50MB to ~350MB. Instead we download
        // via apt-get, with mirror fallback to absorb Termux CDN 403s.
        //
        // Mirror order:
        //   1. Official Termux CDN  (packages.termux.dev)
        //   2. Tsinghua mirror      (mirrors.tuna.tsinghua.edu.cn)
        // Official CDN is fastest when reachable; Tsinghua mirror is the
        // standard fallback used inside China / on networks where Termux
        // CDN returns 403.
        // 清华镜像优先（纯 HTTP，无 ca-certificates 证书问题）。
        // 官方 CDN 即使 sources.list 写 http:// 也会 301 重定向到 https://，
        // 而 bootstrap 没有 ca-certificates → 证书验证失败。
        val aptMirrors = listOf(
            "http://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/",
            "http://packages.termux.dev/apt/termux-main/",
        )

        onProgress("Downloading rust + clang (~570MB) via apt-get…")
        val pkgs = "rust clang"
        var downloadOk = false
        var lastMirror = ""
        for ((idx, mirror) in aptMirrors.withIndex()) {
            lastMirror = mirror
            onProgress("尝试镜像 ${idx + 1}/${aptMirrors.size}: $mirror")
            runInPrefix("echo \"deb $mirror stable main\" > $prefix/etc/apt/sources.list")

            // 合并 update + download，不看 update 退出码（详见
            // aptGetDownloadWithMirrors 中的注释），以 download 结果 +
            // deb 文件数校验为准。
            val code = runWithRetry(
                maxAttempts = 3,
                baseDelayMs = 5000L,
                onProgress = onProgress,
                what = "apt-get download rust clang ($mirror)",
            ) {
                val cmd = """
                    cd $prefix/tmp &&
                    apt-get update \
                        --allow-insecure-repositories \
                        -o Acquire::https::Verify-Peer=false \
                        -o Acquire::https::Verify-Host=false 2>&1;
                    apt-get download --allow-unauthenticated $pkgs 2>&1
                """.trimIndent()
                runInPrefix(
                    cmd,
                    onOutput = { line ->
                        if (!line.contains("GPG error") &&
                            !line.contains("is not signed") &&
                            !line.contains("cannot be authenticated") &&
                            !line.contains("apt-key") &&
                            !line.startsWith("Ign:")
                        ) {
                            onProgress(line)
                        }
                    },
                ) == 0
            }
            if (code) {
                // Sanity-check: ensure both rust*.deb and clang*.deb are present.
                // apt-get download can exit 0 even if one package was skipped.
                val sbCount = StringBuilder()
                runInPrefix(
                    "ls $prefix/tmp/rust*.deb $prefix/tmp/clang*.deb 2>/dev/null | wc -l",
                    onOutput = { sbCount.append(it) },
                )
                val n = sbCount.toString().trim().toIntOrNull() ?: 0
                if (n >= 2) {
                    downloadOk = true
                    onProgress("✓ 从 $mirror 下载成功（$n 个 deb）")
                    break
                } else {
                    Log.w(TAG, "download from $mirror returned 0 but only $n debs present")
                    onProgress("镜像 $mirror 下载不完整（$n 个 deb），尝试下一个…")
                }
            } else {
                Log.w(TAG, "apt-get download failed for $mirror — trying next mirror")
                onProgress("镜像 $mirror 下载失败，尝试下一个…")
            }
        }
        if (!downloadOk) {
            Log.e(TAG, "All apt mirrors failed for rust/clang download (last: $lastMirror)")
            onProgress("错误：所有镜像均下载失败（最后尝试：$lastMirror）")
            return false
        }

        // Extract them into the prefix
        onProgress("Extracting rust/clang…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _compile_stage &&
            for deb in rust*.deb clang*.deb clang-*.deb liblldb*.deb libpolly*.deb libclang*.deb libunwind*.deb libcompiler-rt*.deb; do
                [ -f "${'$'}deb" ] || continue
                echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _compile_stage/ 2>&1
            done &&
            if [ -d "_compile_stage$termuxPrefix" ]; then
                cp -a _compile_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_compile_stage/usr" ]; then
                cp -a _compile_stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _compile_stage rust*.deb clang*.deb clang-*.deb liblldb*.deb libpolly*.deb libclang*.deb libunwind*.deb 2>/dev/null;
            # Robust executability fix. Android cp -a loses execute bits.
            # chmod -R 755 on bin/ and libexec/ recursively sets execute bits
            # on all regular files (including clang-18, rustc, etc.).
            # For lib/*.so files, use find (chmod -R on all of lib/ is too broad).
            chmod -R 755 "$prefix/bin" 2>/dev/null;
            chmod -R 755 "$prefix/libexec" 2>/dev/null;
            find "$prefix/lib" -name '*.so*' -exec chmod 755 {} \; 2>/dev/null;
            echo "Compile toolchain installed"
        """.trimIndent()
        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "rust/clang extract failed with code $extractCode")
            return false
        }

        // Verify
        val rustOk = File(prefix, "bin/rustc").exists()
        val clangOk = File(prefix, "bin/clang").exists()
        if (!rustOk || !clangOk) {
            Log.e(TAG, "rust/clang missing after extract (rust=$rustOk, clang=$clangOk)")
            return false
        }
        // Smoke-test executability of ALL binaries pip/distutils might invoke.
        // pip calls 'aarch64-linux-android-clang' (a symlink), not 'clang'
        // itself — testing only 'clang' would pass while pip still fails.
        val testBinaries = listOf("clang", "aarch64-linux-android-clang", "rustc", "cargo")
        for (bin in testBinaries) {
            val testCode = runInPrefix("$prefix/bin/$bin --version >/dev/null 2>&1")
            if (testCode != 0) {
                Log.w(TAG, "$bin not executable (exit=$testCode) — retrying chmod")
                onProgress("$bin 权限异常，重新修复…")
                // Simple retry: chmod -R 755 on bin/ and libexec/ again.
                // (Previous version used a for loop with \${'$'} shell vars
                //  which caused "Bad substitution" in dash/sh — simplified.)
                runInPrefix(
                    "chmod -R 755 $prefix/bin 2>/dev/null; " +
                        "chmod -R 755 $prefix/libexec 2>/dev/null; " +
                        "find $prefix/lib -name '*.so*' -exec chmod 755 {} \\; 2>/dev/null; " +
                        "echo chmod-done",
                    onOutput = { onProgress(it) },
                )
                val retest = runInPrefix("$prefix/bin/$bin --version >/dev/null 2>&1")
                if (retest != 0) {
                    Log.e(TAG, "$bin still not executable after chmod retry")
                    onProgress("错误：$bin 无法执行（Permission denied）")
                    return false
                }
            }
        }
        onProgress("rust + clang ready")

        // Also ensure libngtcp2 is present — if the user is here, they went
        // through the tarball fallback path (git clone failed due to
        // libcurl/libngtcp2 mismatch). Installing rust/clang may have
        // overwritten libcurl, so re-check.
        ensureCurlDeps(prefix, onProgress)

        return true
    }

    /**
     * Write a minimal ~/.hermes/config.yaml skeleton so first-time users
     * don't have to create it manually. Users still need to run
     * `hermes setup --portal` (or `hermes model`) to populate API keys.
     */
    fun configureHermesSkeleton() {
        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".hermes")
        configDir.mkdirs()

        val configFile = File(configDir, "config.yaml")
        if (!configFile.exists()) {
            configFile.writeText(
                """
                # Hermes Agent configuration
                # Run `hermes setup` or `hermes model` to populate provider/keys.
                model:
                  provider: openrouter
                  name: anthropic/claude-3.5-sonnet
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "Wrote Hermes config skeleton to $configFile")
        }
    }

    /**
     * Run `hermes --version` to confirm the install is functional.
     */
    fun healthCheck(onProgress: (String) -> Unit): Boolean {
        onProgress("Verifying Hermes install…")
        val code = runInPrefix(
            "cd ${BootstrapInstaller.getPaths(context).homeDir}/hermes-agent && " +
                ". .venv/bin/activate && hermes --version 2>&1",
            onOutput = { onProgress(it) },
        )
        return code == 0
    }

    // ── Hermes lifecycle ───────────────────────────────────────────────────

    /**
     * Start a long-running Hermes Agent in the background (e.g. as a
     * gateway). The process is kept alive by the foreground service and
     * its stdout is forwarded to logcat for debugging.
     *
     * NOTE: by default we don't auto-start any background agent. Users
     * are expected to launch `hermes` interactively from a shell. This
     * method exists for future gateway mode support.
     */
    @Suppress("unused")
    fun startHermesGateway(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Hermes gateway already running")
            return true
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()

        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "cd ${paths.homeDir}/hermes-agent && . .venv/bin/activate && exec hermes gateway run --port $HERMES_PORT 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        hermesProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[hermes] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Hermes gateway exited with code: ${proc.waitFor()}")
        }.start()

        // Wait briefly and verify the process is still alive.
        // If it crashed immediately (e.g. missing shared lib, bad venv),
        // return false so the caller knows.
        Thread.sleep(3000)
        return isRunning
    }

    fun stopHermes() {
        hermesProcess?.destroy()
        hermesProcess = null
    }

    // ── Environment ─────────────────────────────────────────────────────────

    internal fun buildEnvironment(
        paths: BootstrapInstaller.Paths,
    ): Map<String, String> = buildEnvMap(context, paths)
}
