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
                val downloadCmd = """
                    cd $prefix/tmp &&
                    apt-get update --allow-insecure-repositories 2>&1 | grep -v 'GPG error\|is not signed\|cannot be authenticated\|apt-key\|Ign:' || true;
                    apt-get download --allow-unauthenticated proot libtalloc 2>&1
                """.trimIndent()
                val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
                if (dlCode != 0) {
                    Log.e(TAG, "apt-get download proot failed with code $dlCode")
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
                val downloadCmd = """
                    cd $prefix/tmp &&
                    apt-get update --allow-insecure-repositories 2>&1 | grep -v 'GPG error\|is not signed\|cannot be authenticated\|apt-key\|Ign:' || true;
                    apt-get download --allow-unauthenticated python python-pip 2>&1
                """.trimIndent()
                val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
                if (dlCode != 0) {
                    Log.e(TAG, "apt-get download python failed with code $dlCode")
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
                chmod 700 "$prefix/bin/pip"* 2>/dev/null
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

            // apt-get update can fail transiently (Termux repo mirror flakiness).
            // Retry up to 3 times with exponential backoff before giving up.
            val updateOk = runWithRetry(
                maxAttempts = 3,
                baseDelayMs = 2000L,
                onProgress = onProgress,
                what = "apt-get update",
            ) {
                runInPrefix(
                    "cd $prefix/tmp && apt-get update --allow-insecure-repositories 2>&1 | grep -v 'GPG error\\|is not signed\\|cannot be authenticated\\|apt-key\\|Ign:' || true",
                    onOutput = { onProgress(it) },
                ) == 0
            }
            if (!updateOk) {
                Log.w(TAG, "apt-get update failed after 3 retries (non-fatal — proceeding with apt-get download anyway)")
            }

            // Official Hermes pkg list + transitive build tools needed to
            // compile native Python wheels (cryptography, cffi, etc).
            val pkgGroups = listOf(
                // Hermes official Termux pkg list
                "git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg",
                // Transitive native build toolchain (needed by rust + cffi + cryptography)
                "cmake binutils lld libllvm libedit ndk-sysroot ndk-multilib libcompiler-rt",
                // Shared libs that some Hermes extras link against.
                // libngtcp2 is a transitive dep of libcurl (HTTP/3 support) —
                // must be explicitly listed because `apt-get download` does NOT
                // resolve dependencies, unlike `apt-get install -d`.
                "libarchive libxml2 liblzma libcurl libuv libnghttp2 libnghttp3 libngtcp2",
                // Misc
                "rhash jsoncpp",
            )

            // Download each pkg group with its own retry — one group failing
            // shouldn't force the whole step to restart from scratch.
            for (group in pkgGroups) {
                val groupOk = runWithRetry(
                    maxAttempts = 3,
                    baseDelayMs = 2000L,
                    onProgress = onProgress,
                    what = "apt-get download $group",
                ) {
                    runInPrefix(
                        "cd $prefix/tmp && apt-get download --allow-unauthenticated $group 2>&1",
                        timeoutMs = 300000, // 5 min for large debs (rust ~96MB)
                        onOutput = { onProgress(it) },
                    ) == 0
                }
                if (!groupOk) {
                    Log.w(TAG, "apt-get download ($group) failed after retries (non-fatal)")
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
                val delay = baseDelayMs * (1L shl (attempt - 2))
                onProgress("Retry $attempt/$maxAttempts for $what (waiting ${delay}ms)…")
                try { Thread.sleep(delay) } catch (_: InterruptedException) {}
            }
            lastError = !action()
            if (!lastError) return true
        }
        return !lastError
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
            onProgress("Hermes repository already present, pulling latest…")
            runInPrefix("cd ${homeDir}/hermes-agent && git pull --ff-only 2>&1") { onProgress(it) }
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
        val wheelCacheDir = setupWheelCacheIfPresent(prefix, onProgress)
        val pipArgs = if (wheelCacheDir != null) {
            "--find-links=$wheelCacheDir"
        } else {
            ""
        }
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
            val cmd = """
                cd ${homeDir}/hermes-agent &&
                . .venv/bin/activate &&
                pip install --upgrade pip 2>&1 | tail -1 &&
                pip install $pipArgs -e '.[termux]' -c constraints-termux.txt 2>&1
            """.trimIndent()
            runInPrefix(cmd, onOutput = {
                onProgress(it)
                phase1Output.appendLine(it)
            }) == 0
        }

        // Phase 2 fallback: if wheel install failed, ask the user whether
        // to download rust+clang (~600MB) and compile from source. This is
        // needed when the pre-fetched aarch64 manylinux wheels don't work
        // on Android's bionic libc (they may segfault at runtime because
        // they're built against glibc). The user should know which path
        // they're on before burning 600MB of data.
        if (!installOk) {
            // Surface the last few pip error lines so the user can see
            // WHY Phase 1 failed (e.g. "ERROR: Could not build wheel for
            // cryptography" or "no matching distribution for ...").
            val tailLines = phase1Output.lines()
                .takeLast(15)
                .filter { it.isNotBlank() }
            onProgress("════════════════════════════════════════")
            onProgress("Phase 1 (wheel cache) FAILED. Last pip output:")
            tailLines.forEach { onProgress("  $it") }
            onProgress("════════════════════════════════════════")
            Log.w(TAG, "pip install from wheel cache failed — asking user about source compile")

            // Ask the user via callback. Default lambda returns true
            // (auto-approve) for headless/CI runs where no UI is present.
            val approved = onNeedCompile()
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
                    pip install $pipArgs --no-binary=:all: -e '.[termux]' -c constraints-termux.txt 2>&1
                """.trimIndent()
                runInPrefix(cmd, onOutput = { onProgress(it) }) == 0
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

        // apt-get update (with retry)
        val updateOk = runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 2000L,
            onProgress = onProgress,
            what = "apt-get update (for rust/clang)",
        ) {
            runInPrefix(
                "cd $prefix/tmp && apt-get update --allow-insecure-repositories 2>&1 | grep -v 'GPG error\\|is not signed\\|cannot be authenticated\\|apt-key\\|Ign:' || true",
                onOutput = { onProgress(it) },
            ) == 0
        }
        if (!updateOk) {
            Log.w(TAG, "apt-get update failed (non-fatal — proceeding with download anyway)")
        }

        // Download rust + clang + ffmpeg. These are big (~600MB) so retry.
        val pkgs = if (File("$prefix/bin/ffmpeg").exists()) "rust clang" else "rust clang ffmpeg"
        val dlOk = runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000L,
            onProgress = onProgress,
            what = "apt-get download $pkgs",
        ) {
            runInPrefix(
                "cd $prefix/tmp && apt-get download --allow-unauthenticated $pkgs 2>&1",
                onOutput = { onProgress(it) },
            ) == 0
        }
        if (!dlOk) {
            Log.e(TAG, "apt-get download ($pkgs) failed after retries")
            return false
        }

        // Extract them into the prefix
        onProgress("Extracting rust/clang/ffmpeg…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _compile_stage &&
            for deb in rust*.deb clang*.deb clang-*.deb ffmpeg*.deb liblldb*.deb libpolly*.deb libclang*.deb libunwind*.deb libcompiler-rt*.deb; do
                [ -f "${'$'}deb" ] || continue
                echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _compile_stage/ 2>&1
            done &&
            if [ -d "_compile_stage$termuxPrefix" ]; then
                cp -a _compile_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_compile_stage/usr" ]; then
                cp -a _compile_stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _compile_stage rust*.deb clang*.deb clang-*.deb ffmpeg*.deb liblldb*.deb libpolly*.deb libclang*.deb libunwind*.deb 2>/dev/null
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
