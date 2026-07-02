package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Hermes Agent 运行时生命周期管理。
 *
 * 全量参照 openclaw-termux：所有命令通过 [ProcessManager] 走 proot，在完整
 * Ubuntu rootfs 里执行。相比旧的 Termux prefix 方案，这里不再需要：
 *   - 手动 deb 解压（apt-get install 在 rootfs 里直接可用）
 *   - venv 手动创建绕过 _sysconfigdata bug（rootfs 里 python -m venv 正常工作）
 *   - chmod 修可执行位（proot 保留 rootfs 原始 mode）
 *   - apt 镜像 fallback（Ubuntu 官方/清华源稳定）
 *
 * 安装流程（每步都经 proot install 模式）：
 *   1. installProot  — 验证 proot 能跑（二进制已通过 jniLibs 打包）
 *   2. installPython — apt-get install python3 python3-pip python3-venv
 *   3. installHermesBuildDeps — apt-get install git make pkg-config ...
 *   4. installHermes — git clone + python -m venv + pip install -e '.[termux]'
 *
 * 目录（rootfs 内视角，对应 host 路径见 [BootstrapManager.Paths]）：
 *   /root/home/hermes-agent  ← 代码 + .venv（host: filesDir/home/hermes-agent）
 *   /root/.hermes            ← 配置（host: filesDir/home/.hermes）
 */
class HermesServerManager(private val context: Context) {

    companion object {
        private const val TAG = "HermesServerManager"
        const val HERMES_PORT = 18789
        private const val HERMES_REPO = "https://github.com/NousResearch/hermes-agent.git"

        /**
         * 旧 API 兼容：返回 proot 主机侧环境变量。
         * 新模型下命令统一经 ProcessManager 走 proot，这个 map 主要给
         * HermesEnvBackup 等需要直接 spawn 进程的调用方用。
         */
        @Suppress("unused")
        fun buildEnvMap(context: Context, paths: BootstrapManager.Paths): Map<String, String> {
            val pm = ProcessManager(context, paths.filesDir, paths.nativeLibDir)
            return pm.prootEnvPublic()
        }
    }

    private val paths: BootstrapManager.Paths by lazy { BootstrapManager.getPaths(context) }
    private val processManager: ProcessManager by lazy {
        ProcessManager(context, paths.filesDir, paths.nativeLibDir)
    }

    private var hermesProcess: Process? = null

    val isRunning: Boolean
        get() = hermesProcess?.let {
            try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        } ?: false

    // ── Shell 桥接 ──────────────────────────────────────────────────────────

    /**
     * 在 proot（install 模式）里执行 shell 命令，返回退出码。
     * 保留旧 API 签名（timeoutMs=0 表示不超时），内部委托给 ProcessManager。
     */
    fun runInPrefix(
        command: String,
        timeoutMs: Long = 0,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val timeoutSec = if (timeoutMs > 0) timeoutMs / 1000 else 1800L
        return processManager.runInProotExitCode(command, timeoutSec, onOutput)
    }

    /**
     * 重试包装器。apt/pip/git 等网络操作可能因瞬时故障失败，重试 3 次。
     */
    fun <T> runWithRetry(
        maxAttempts: Int = 3,
        baseDelayMs: Long = 3000L,
        onProgress: ((String) -> Unit)? = null,
        what: String = "operation",
        block: () -> T,
    ): T {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "$what attempt ${attempt + 1}/$maxAttempts failed: ${e.message}")
                onProgress?.invoke("$what 第 ${attempt + 1} 次失败: ${e.message}")
                if (attempt < maxAttempts - 1) {
                    val delay = baseDelayMs * (attempt + 1)
                    onProgress?.invoke("${delay / 1000}s 后重试…")
                    Thread.sleep(delay)
                }
            }
        }
        throw lastError ?: RuntimeException("$what failed after $maxAttempts attempts")
    }

    // ── 安装状态检查 ─────────────────────────────────────────────────────────

    fun isProotInstalled(): Boolean {
        // proot 二进制通过 jniLibs 打包，检查 nativeLibDir 里存在即可。
        // rootfs 由 BootstrapManager 负责。
        return File(paths.nativeLibDir, "libproot.so").exists() &&
            BootstrapManager.isBootstrapInstalled(context)
    }

    fun isPythonInstalled(): Boolean {
        if (!isProotInstalled()) return false
        // 在 proot 里检查 python3 是否可用（apt install 后才有）
        val code = processManager.runInProotExitCode(
            "command -v python3 >/dev/null 2>&1", 30
        )
        return code == 0
    }

    fun isNodeInstalled(): Boolean {
        if (!isProotInstalled()) return false
        // hermes-web-ui@0.6.23 要求 node >=23.0.0，Ubuntu 24.04 apt 只有 18.x，
        // 所以必须用 Node.js 23 二进制 tarball。
        val code = processManager.runInProotExitCode(
            "node --version 2>/dev/null | grep -E '^v(2[3-9]|[3-9][0-9])\\.' >/dev/null", 15
        )
        return code == 0
    }

    fun isHermesInstalled(): Boolean {
        // host 侧检查 hermes-agent 代码 + venv 存在
        return File(paths.homeDir, "hermes-agent/pyproject.toml").exists() &&
            File(paths.homeDir, "hermes-agent/.venv/bin/activate").exists()
    }

    /**
     * 旧 API 兼容：新模型下不再有 deb bundle（apt 在 rootfs 里直接装）。
     * 返回 false 让 MainActivity 跳过这一步。
     */
    fun extractDebBundleIfPresent(onProgress: (String) -> Unit): Boolean {
        onProgress("跳过 deb bundle（新架构用 apt-get install）")
        return false
    }

    // ── Step 1: proot + rootfs ──────────────────────────────────────────────

    /**
     * 下载 Ubuntu rootfs（如果未装）+ 验证 proot 能在 rootfs 里执行命令。
     *
     * rootfs 下载放在这里（而非启动时 extractBootstrap），让"一键安装"
     * 进度条能反映 rootfs 下载 + 解压进度。
     */
    fun installProot(onProgress: (String) -> Unit): Boolean {
        onProgress("proot: 检查环境…")
        if (!File(paths.nativeLibDir, "libproot.so").exists()) {
            onProgress("错误：libproot.so 不存在（jniLibs 未解压）")
            return false
        }
        // rootfs 未装则下载 + 解压（最耗时的一步，~1-2min）
        if (!BootstrapManager.isBootstrapInstalled(context)) {
            onProgress("proot: 下载 Ubuntu rootfs（约 28MB）…")
            try {
                BootstrapManager.install(context) { onProgress(it) }
            } catch (e: Exception) {
                onProgress("错误：rootfs 下载/解压失败 — ${e.message}")
                return false
            }
        } else {
            onProgress("proot: rootfs 已存在，刷新配置…")
            BootstrapManager.ensureSystemConfig(context)
        }
        return try {
            onProgress("proot: 验证可执行…")
            val out = processManager.runInProotSync("echo proot-ok && uname -a", 60) { onProgress(it) }
            onProgress("✓ proot 可用: ${out.lineSequence().firstOrNull() ?: ""}")
            true
        } catch (e: Exception) {
            onProgress("错误：proot 验证失败 — ${e.message}")
            false
        }
    }

    // ── Step 2: Python ──────────────────────────────────────────────────────

    /**
     * apt-get install python3 python3-pip python3-venv。
     * rootfs 里 apt 完全可用，无需手动 deb 解压。
     */
    fun installPython(onProgress: (String) -> Unit): Boolean {
        if (isPythonInstalled()) {
            onProgress("Python: 已安装，跳过")
            return true
        }
        return try {
            runWithRetry(onProgress = onProgress, what = "install python") {
                onProgress("Python: apt-get update…")
                processManager.runInProotSync(
                    "apt-get update 2>&1 | tail -5", 600
                ) { onProgress(it) }
                onProgress("Python: apt-get install python3 python3-pip python3-venv…")
                processManager.runInProotSync(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                        "python3 python3-pip python3-venv 2>&1 | tail -20",
                    1200
                ) { onProgress(it) }
                isPythonInstalled()
            }
        } catch (e: Exception) {
            onProgress("错误：Python 安装失败 — ${e.message}")
            false
        }
    }

    // ── Step 3: build deps ──────────────────────────────────────────────────

    /**
     * apt-get install Hermes 编译依赖（git/make/pkg-config/libffi-dev 等）。
     * rootfs 里这些是标准 Ubuntu 包，apt 自动解析依赖。
     */
    fun installHermesBuildDeps(onProgress: (String) -> Unit): Boolean {
        // marker 文件，避免重复安装（apt install 本身幂等，但省一次 update）
        val marker = File(paths.configDir, ".build-deps-v1")
        if (marker.exists()) {
            onProgress("build deps: 已安装（缓存）")
            return true
        }
        return try {
            runWithRetry(onProgress = onProgress, what = "install build deps") {
                onProgress("build deps: apt-get update…")
                processManager.runInProotSync(
                    "apt-get update 2>&1 | tail -5", 600
                ) { onProgress(it) }
                onProgress("build deps: apt-get install build-essential git make pkg-config…")
                // build-essential 含 gcc/g++/make；libffi-dev/libssl-dev 给
                // cffi/cryptography 编译用；git 克隆代码；pkg-config 找库；
                // ripgrep 给 Hermes 搜索；nodejs/npm 可选运行时。
                processManager.runInProotSync(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                        "build-essential git make pkg-config " +
                        "libffi-dev libssl-dev libsqlite3-dev zlib1g-dev " +
                        "ripgrep 2>&1 | tail -20",
                    1800
                ) { onProgress(it) }
                marker.parentFile?.mkdirs()
                marker.writeText("ok")
                true
            }
        } catch (e: Exception) {
            onProgress("错误：build deps 安装失败 — ${e.message}")
            false
        }
    }

    // ── Step 4: Hermes Agent ────────────────────────────────────────────────

    /**
     * git clone hermes-agent + python -m venv + pip install -e '.[termux]'。
     *
     * rootfs 里 python -m venv 正常工作（无 _sysconfigdata bug），无需手动创建。
     * pip 用清华镜像加速（国内 PyPI 慢）。
     *
     * @param onNeedCompile Phase 1 失败时回调，询问用户是否同意下载工具链从源码编译。
     *                       新 rootfs 模型里 build-essential 已装，多数情况一次成功。
     */
    fun installHermes(
        onProgress: (String) -> Unit,
        @Suppress("UNUSED_PARAMETER") onNeedCompile: () -> Boolean = { true },
    ): Boolean {
        val homeDir = paths.homeDir
        val repoDir = File(homeDir, "hermes-agent")

        // 准备目录
        File(homeDir, ".hermes").mkdirs()

        // git clone（多镜像 fallback，GitHub 在国内可能被墙）
        if (!File(repoDir, "pyproject.toml").exists()) {
            val cloneOk = cloneHermesRepo(onProgress)
            if (!cloneOk) {
                onProgress("错误：hermes-agent 克隆失败")
                return false
            }
        } else if (File(repoDir, ".git").isDirectory) {
            onProgress("hermes-agent 已存在，git pull…")
            processManager.runInProotExitCode(
                "cd /root/home/hermes-agent && git pull --ff-only 2>&1", 120
            ) { onProgress(it) }
        }

        // python -m venv（rootfs 里正常工作，无需手动创建绕过 bug）
        val venvActivate = File(repoDir, ".venv/bin/activate")
        if (!venvActivate.exists()) {
            onProgress("创建 Python venv…")
            val venvCode = processManager.runInProotExitCode(
                "cd /root/home/hermes-agent && python3 -m venv .venv 2>&1",
                300
            ) { onProgress(it) }
            if (venvCode != 0 || !venvActivate.exists()) {
                onProgress("错误：venv 创建失败")
                return false
            }
        } else {
            onProgress("venv 已存在，复用…")
        }

            // pip install -e '.[termux]'
        return runWithRetry(onProgress = onProgress, what = "pip install hermes") {
            onProgress("pip install -e '.[termux]'（可能需要几分钟）…")
            // 清华 PyPI 镜像加速；--retries/--timeout 抗瞬时网络抖动
            // --progress-bar off 关掉每个包的进度条（日志太长），只保留
            // "Collecting"/"Downloading"/"Successfully installed" 关键行
            val cmd = """
                cd /root/home/hermes-agent &&
                . .venv/bin/activate &&
                pip install --progress-bar off --retries 3 --timeout 120 \
                    -i https://pypi.tuna.tsinghua.edu.cn/simple \
                    --trusted-host pypi.tuna.tsinghua.edu.cn \
                    -e '.[termux]' 2>&1
            """.trimIndent()
            val code = processManager.runInProotExitCode(cmd, 1200) { onProgress(it) }
            if (code != 0) {
                onProgress("pip install 失败 (exit=$code)")
                throw RuntimeException("pip install failed (exit=$code)")
            }
            // 验证 hermes 可执行
            val verify = processManager.runInProotExitCode(
                "cd /root/home/hermes-agent && . .venv/bin/activate && hermes --version 2>&1",
                120
            ) { onProgress(it) }
            if (verify != 0) {
                onProgress("警告：hermes --version 退出码 $verify")
            }
            verify == 0
        }
    }

    private fun cloneHermesRepo(onProgress: (String) -> Unit): Boolean {
        // 国内网络下 git clone 经常超时（每个镜像 30s+ TCP 超时，4个镜像
        // 串行浪费 2.5 分钟全失败）。tarball 下载更可靠（gh-proxy 秒级成功）。
        // 所以先试 tarball，失败再 git clone（海外用户可能 git 更快）。
        onProgress("尝试 tarball 下载（国内最稳定）…")
        if (downloadHermesTarball(onProgress)) {
            return true
        }

        onProgress("tarball 全部失败，尝试 git clone…")
        val cloneUrls = listOf(
            "https://gitclone.com/github.com/NousResearch/hermes-agent.git",
            "https://kkgithub.com/NousResearch/hermes-agent.git",
            HERMES_REPO,
            "https://bgithub.xyz/NousResearch/hermes-agent.git",
        )
        for (cloneUrl in cloneUrls) {
            val host = cloneUrl.substringAfter("://").substringBefore("/")
            onProgress("git clone from $host…")
            // GIT_HTTP_CONNECT_TIMEOUT=15 限制 TCP 连接超时（默认 30s+），
            // 避免单个镜像卡 30 秒以上。
            val code = processManager.runInProotExitCode(
                "cd /root/home && rm -rf hermes-agent && " +
                    "GIT_HTTP_CONNECT_TIMEOUT=15 GIT_HTTP_LOW_SPEED_LIMIT=1000 GIT_HTTP_LOW_SPEED_TIME=15 " +
                    "git clone --depth 1 $cloneUrl hermes-agent 2>&1",
                600
            ) { onProgress(it) }
            if (code == 0 && File(paths.homeDir, "hermes-agent/pyproject.toml").exists()) {
                onProgress("✓ 克隆成功（$host）")
                return true
            }
            onProgress("$host 克隆失败，尝试下一个镜像…")
        }
        return false
    }

    private fun downloadHermesTarball(onProgress: (String) -> Unit): Boolean {
        // gh-proxy 优先（日志证实它在国内最稳定），github 直连兜底。
        val tarballUrls = listOf(
            "https://gh-proxy.com/https://github.com/NousResearch/hermes-agent/archive/refs/heads/main.tar.gz",
            "https://kkgithub.com/NousResearch/hermes-agent/archive/refs/heads/main.tar.gz",
            "https://github.com/NousResearch/hermes-agent/archive/refs/heads/main.tar.gz",
        )
        val tarballFile = File(paths.tmpDir, "hermes-agent.tar.gz")
        for (url in tarballUrls) {
            val host = url.substringAfter("://").substringBefore("/")
            onProgress("下载 tarball from $host…")
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    onProgress("HTTP ${conn.responseCode} from $host")
                    conn.disconnect()
                    continue
                }
                conn.inputStream.use { inp ->
                    tarballFile.outputStream().use { inp.copyTo(it) }
                }
                conn.disconnect()
                if (tarballFile.length() < 1024) {
                    tarballFile.delete(); continue
                }
                onProgress("tarball 下载完成，解压…")
                // 在 proot 里解压。tmpDir 已 bind 到 /tmp（见 ProcessManager），
                // 所以用 /tmp 路径访问 host 下载的 tarball。
                val code = processManager.runInProotExitCode(
                    "cd /root/home && rm -rf hermes-agent hermes-agent-main && " +
                        "tar -xzf /tmp/hermes-agent.tar.gz -C /root/home 2>&1 && " +
                        "mv hermes-agent-main hermes-agent 2>/dev/null; " +
                        "test -f /root/home/hermes-agent/pyproject.toml",
                    300
                ) { onProgress(it) }
                tarballFile.delete()
                if (code == 0) {
                    onProgress("✓ tarball 解压成功")
                    return true
                }
            } catch (e: Exception) {
                onProgress("$host 下载失败: ${e.message}")
                tarballFile.delete()
            }
        }
        return false
    }

    // ── 配置 / 健康检查 ─────────────────────────────────────────────────────

    /**
     * 写最小 ~/.hermes/config.yaml 骨架，首次用户不用手动建。
     */
    fun configureHermesSkeleton() {
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

    fun healthCheck(onProgress: (String) -> Unit): Boolean {
        onProgress("验证 Hermes 安装…")
        val code = runInPrefix(
            "cd /root/home/hermes-agent && . .venv/bin/activate && hermes --version 2>&1",
            onOutput = { onProgress(it) },
        )
        return code == 0
    }

    // ── Hermes 生命周期 ─────────────────────────────────────────────────────

    /**
     * 启动长驻 Hermes gateway（gateway 模式）。
     * 进程由前台服务保活，stdout 转发到 logcat。
     */
    @Suppress("unused")
    fun startHermesGateway(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Hermes gateway already running")
            return true
        }
        val cmd = "cd /root/home/hermes-agent && . .venv/bin/activate && " +
            "exec hermes gateway run --port $HERMES_PORT 2>&1"
        val proc = processManager.startProotProcess(cmd)
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
        return isRunning
    }

    fun stopHermes() {
        hermesProcess?.destroy()
        hermesProcess = null
    }
}
