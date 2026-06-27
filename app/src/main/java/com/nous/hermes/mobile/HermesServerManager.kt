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
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
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
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/hermes").exists() ||
            File(paths.homeDir, "hermes-agent/pyproject.toml").exists()
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

        onProgress("Downloading proot…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated proot libtalloc 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download proot failed with code $dlCode")
            return false
        }

        onProgress("Extracting proot…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _proot_stage &&
            for deb in proot*.deb libtalloc*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _proot_stage/ 2>&1
            done &&
            if [ -d "_proot_stage$termuxPrefix" ]; then
                cp -a _proot_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_proot_stage/usr" ]; then
                cp -a _proot_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/proot" 2>/dev/null
            rm -rf _proot_stage proot*.deb libtalloc*.deb 2>/dev/null
            echo "proot installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "proot extract failed with code $extractCode")
            return false
        }

        return isProotInstalled()
    }

    // ── Python ─────────────────────────────────────────────────────────────

    /**
     * Install Python using proot to handle dpkg's hardcoded Termux paths.
     * proot bind-mounts our prefix onto the compiled-in Termux prefix so
     * dpkg postinst scripts and shared library lookups resolve correctly.
     */
    fun installPython(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading Python packages…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated python python-pip 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download python failed with code $dlCode")
        }

        onProgress("Extracting Python…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _python_stage &&
            for deb in python*.deb; do
                [ -f "${'$'}deb" ] && echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _python_stage/ 2>&1
            done &&
            if [ -d "_python_stage$termuxPrefix" ]; then
                cp -a _python_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_python_stage/usr" ]; then
                cp -a _python_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/python"* 2>/dev/null
            chmod 700 "$prefix/bin/pip"* 2>/dev/null
            rm -rf _python_stage python*.deb 2>/dev/null
            echo "Python installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "Python extract failed with code $extractCode")
            return false
        }

        val fixCmd = """
            if [ -f "$prefix/bin/python3" ] && [ ! -f "$prefix/bin/python" ]; then
                ln -sf python3 "$prefix/bin/python"
            fi
            echo "Python ready"
        """.trimIndent()
        runInPrefix(fixCmd, onOutput = { onProgress(it) })

        return isPythonInstalled()
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

        onProgress("Downloading Node.js packages…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated c-ares libicu libsqlite nodejs-lts npm 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download failed with code $dlCode")
        }

        onProgress("Extracting Node.js packages…")
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _stage &&
            for deb in *.deb; do
                echo "Extracting ${'$'}deb..." &&
                dpkg-deb -x "${'$'}deb" _stage/ 2>&1
            done &&
            if [ -d "_stage$termuxPrefix" ]; then
                cp -a _stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_stage/usr" ]; then
                cp -a _stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _stage *.deb 2>/dev/null
            echo "done"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "dpkg-deb extract failed with code $extractCode")
            return false
        }

        onProgress("Fixing npm wrapper script…")
        val fixCmd = """
            chmod 700 "$prefix/bin/node" 2>/dev/null

            NPM_CLI="$prefix/lib/node_modules/npm/bin/npm-cli.js"
            if [ -f "${'$'}NPM_CLI" ] && [ ! -f "$prefix/bin/npm" ]; then
                cat > "$prefix/bin/npm" << 'WEOF'
#!/data/user/0/com.nous.hermes.mobile/files/usr/bin/sh
exec /data/user/0/com.nous.hermes.mobile/files/usr/bin/node /data/user/0/com.nous.hermes.mobile/files/usr/lib/node_modules/npm/bin/npm-cli.js "${'$'}@"
WEOF
                chmod 700 "$prefix/bin/npm"
            fi

            echo "Wrapper scripts created"
        """.trimIndent()
        runInPrefix(fixCmd, onOutput = { onProgress(it) })

        return isNodeInstalled()
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

        onProgress("Downloading build dependencies…")

        // Official Hermes pkg list + transitive build tools needed to
        // compile native Python wheels (cryptography, cffi, etc).
        val pkgGroups = listOf(
            // Hermes official Termux pkg list
            "git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg",
            // Transitive native build toolchain (needed by rust + cffi + cryptography)
            "cmake binutils lld libllvm libedit ndk-sysroot ndk-multilib libcompiler-rt",
            // Shared libs that some Hermes extras link against
            "libarchive libxml2 liblzma libcurl libuv libnghttp2 libnghttp3",
            // Misc
            "rhash jsoncpp",
        )

        for (group in pkgGroups) {
            val dlCode = runInPrefix(
                "cd $prefix/tmp && apt-get download --allow-unauthenticated $group 2>&1",
                onOutput = { onProgress(it) },
            )
            if (dlCode != 0) {
                Log.w(TAG, "apt-get download ($group) failed with code $dlCode (non-fatal)")
            }
        }

        onProgress("Extracting build dependencies…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _deps_stage &&
            for deb in *.deb; do
                [ -f "${'$'}deb" ] && echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _deps_stage/ 2>&1
            done &&
            if [ -d "_deps_stage$termuxPrefix" ]; then
                cp -a _deps_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_deps_stage/usr" ]; then
                cp -a _deps_stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _deps_stage *.deb 2>/dev/null
            echo "Build deps installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.w(TAG, "Deps extract failed with code $extractCode (non-fatal)")
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

        return true
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
                [ -f "${'$'}bin" ] && python3 "$prefix/tmp/_patchbin.py" "${'$'}bin" && chmod 700 "${'$'}bin"
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
     * Install Hermes Agent following the official Termux guide:
     *
     *   git clone https://github.com/NousResearch/hermes-agent.git
     *   cd hermes-agent
     *   python -m pip install -e '.[termux]' -c constraints-termux.txt
     *
     * The constraints file pins versions known to build cleanly on
     * Android. We rely on the in-repo constraints-termux.txt.
     */
    fun installHermes(onProgress: (String) -> Unit): Boolean {
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
                "#!/data/user/0/com.nous.hermes.mobile/files/usr/bin/sh\nexit 0\n"
            )
            systemctlStub.setExecutable(true)
        }

        // Clone Hermes (idempotent)
        if (!File(homeDir, "hermes-agent/pyproject.toml").exists()) {
            onProgress("Cloning Hermes Agent repository…")
            val cloneCode = runInPrefix(
                "cd ${homeDir} && git clone --depth 1 $HERMES_REPO hermes-agent 2>&1",
                onOutput = { onProgress(it) },
            )
            if (cloneCode != 0) {
                Log.e(TAG, "git clone hermes-agent failed with code $cloneCode")
                return false
            }
        } else {
            onProgress("Hermes repository already present, pulling latest…")
            runInPrefix("cd ${homeDir}/hermes-agent && git pull --ff-only 2>&1") { onProgress(it) }
        }

        // Create venv (Hermes recommends isolation)
        onProgress("Creating Python venv…")
        runInPrefix(
            "cd ${homeDir}/hermes-agent && python -m venv .venv 2>&1",
            onOutput = { onProgress(it) },
        )

        // Install Hermes with termux extras
        onProgress("Installing Hermes (pip install -e .[termux])…", "This may take several minutes")
        val installCmd = """
            cd ${homeDir}/hermes-agent &&
            . .venv/bin/activate &&
            pip install --upgrade pip 2>&1 | tail -1 &&
            pip install -e '.[termux]' -c constraints-termux.txt 2>&1
        """.trimIndent()
        val installCode = runInPrefix(installCmd, onOutput = { onProgress(it) })
        if (installCode != 0) {
            Log.e(TAG, "pip install hermes failed with code $installCode")
            return false
        }

        // Link hermes binary onto PATH so it's discoverable from any shell
        onProgress("Linking hermes binary…")
        val linkCmd = """
            cd ${homeDir}/hermes-agent &&
            . .venv/bin/activate &&
            HERMES_BIN="${'$'}(which hermes 2>/dev/null)" &&
            if [ -n "${'$'}HERMES_BIN" ] && [ ! -f "$prefix/bin/hermes" ]; then
                cat > "$prefix/bin/hermes" << WEOF
#!/data/user/0/com.nous.hermes.mobile/files/usr/bin/sh
exec ${'$'}HERMES_BIN "\${'$'}@"
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

        Thread.sleep(3000)
        return true
    }

    fun stopHermes() {
        hermesProcess?.destroy()
        hermesProcess = null
    }

    // ── Environment ─────────────────────────────────────────────────────────

    private fun buildEnvironment(
        paths: BootstrapInstaller.Paths,
    ): Map<String, String> {
        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
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
            // Rust toolchain env (helps maturin/cargo on Termux)
            "CARGO_HOME" to "${paths.homeDir}/.cargo",
            "RUSTUP_HOME" to "${paths.homeDir}/.rustup",
        )
    }
}
