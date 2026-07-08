package com.nous.hermes.mobile

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Hermes Agent 配置管理器 — 直接读写 ~/.hermes/config.yaml 和 .env，不依赖 WebUI。
 *
 * config.yaml 格式（简化 YAML）：
 *   model:
 *     provider: openrouter
 *     name: anthropic/claude-3.5-sonnet
 *
 *   # 自定义 provider（可选）
 *   custom_providers:
 *     - name: my-provider
 *       base_url: https://api.example.com/v1
 *       api_key_env: MY_PROVIDER_API_KEY
 *       models:
 *         - model-1
 *         - model-2
 *
 * .env 格式：
 *   OPENROUTER_API_KEY=sk-or-...
 *   OPENAI_API_KEY=sk-...
 *   ANTHROPIC_API_KEY=sk-ant-...
 */
class HermesConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "HermesConfigManager"

        /** host 路径：filesDir/home/.hermes/ */
        private val HERMES_DIR = "home/.hermes"
        private const val CONFIG_FILE = "config.yaml"
        private const val ENV_FILE = ".env"

        /** 内置 provider 目录 — 常见的 LLM 提供商 */
        data class BuiltinProvider(
            val key: String,
            val label: String,
            val baseUrl: String,
            val apiKeyEnv: String,
            val apiKeyLabel: String,
            val models: List<String>,
        )

        val BUILTIN_PROVIDERS = listOf(
            BuiltinProvider(
                "openrouter", "OpenRouter",
                "https://openrouter.ai/api/v1",
                "OPENROUTER_API_KEY", "OpenRouter API Key",
                listOf(
                    "anthropic/claude-3.5-sonnet",
                    "anthropic/claude-3.5-haiku",
                    "anthropic/claude-3-opus",
                    "openai/gpt-4o",
                    "openai/gpt-4o-mini",
                    "openai/gpt-4-turbo",
                    "google/gemini-flash-1.5",
                    "google/gemini-pro-1.5",
                    "meta-llama/llama-3.1-70b-instruct",
                    "meta-llama/llama-3.1-8b-instruct",
                    "mistralai/mistral-large",
                    "deepseek/deepseek-chat",
                    "qwen/qwen-2.5-72b-instruct",
                )
            ),
            BuiltinProvider(
                "openai", "OpenAI",
                "https://api.openai.com/v1",
                "OPENAI_API_KEY", "OpenAI API Key",
                listOf(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gpt-4-turbo",
                    "gpt-4",
                    "gpt-3.5-turbo",
                    "o1-preview",
                    "o1-mini",
                )
            ),
            BuiltinProvider(
                "anthropic", "Anthropic",
                "https://api.anthropic.com",
                "ANTHROPIC_API_KEY", "Anthropic API Key",
                listOf(
                    "claude-3-5-sonnet-20241022",
                    "claude-3-5-haiku-20241022",
                    "claude-3-opus-20240229",
                )
            ),
            BuiltinProvider(
                "google", "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta",
                "GOOGLE_API_KEY", "Google API Key",
                listOf(
                    "gemini-1.5-pro",
                    "gemini-1.5-flash",
                    "gemini-1.5-flash-8b",
                    "gemini-2.0-flash-exp",
                )
            ),
            BuiltinProvider(
                "groq", "Groq",
                "https://api.groq.com/openai/v1",
                "GROQ_API_KEY", "Groq API Key",
                listOf(
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant",
                    "mixtral-8x7b-32768",
                    "gemma2-9b-it",
                )
            ),
            BuiltinProvider(
                "deepseek", "DeepSeek",
                "https://api.deepseek.com",
                "DEEPSEEK_API_KEY", "DeepSeek API Key",
                listOf(
                    "deepseek-chat",
                    "deepseek-reasoner",
                )
            ),
            BuiltinProvider(
                "together", "Together AI",
                "https://api.together.xyz/v1",
                "TOGETHER_API_KEY", "Together API Key",
                listOf(
                    "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                    "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo",
                    "mistralai/Mistral-7B-Instruct-v0.3",
                    "Qwen/Qwen2.5-72B-Instruct-Turbo",
                )
            ),
            BuiltinProvider(
                "siliconflow", "SiliconFlow",
                "https://api.siliconflow.cn/v1",
                "SILICONFLOW_API_KEY", "SiliconFlow API Key",
                listOf(
                    "deepseek-ai/DeepSeek-V3",
                    "deepseek-ai/DeepSeek-R1",
                    "Qwen/Qwen2.5-72B-Instruct",
                    "meta-llama/Meta-Llama-3.1-405B-Instruct",
                )
            ),
        )
    }

    /** 自定义 provider 数据模型 */
    data class CustomProvider(
        val name: String,
        val baseUrl: String,
        val apiKeyEnv: String,
        val models: List<String>,
    )

    /** 当前配置 */
    data class Config(
        val defaultProvider: String,
        val defaultModel: String,
        val apiKeys: Map<String, String>,       // .env 中的 key=value
        val customProviders: List<CustomProvider>,
    )

    private val hermesDir: File by lazy { File(context.filesDir, HERMES_DIR) }
    private val configFile: File by lazy { File(hermesDir, CONFIG_FILE) }
    private val envFile: File by lazy { File(hermesDir, ENV_FILE) }

    /**
     * 读取完整配置。
     *
     * Hermes Agent config.yaml 格式：
     *   model:
     *     default: "anthropic/claude-opus-4.6"
     *     provider: "auto"
     *     base_url: "https://openrouter.ai/api/v1"
     *
     *   providers:
     *     my-proxy:
     *       base_url: "https://..."
     *       key_env: "MY_PROXY_API_KEY"
     *       models:
     *         model-1:
     *         model-2:
     */
    fun readConfig(): Config {
        val configText = if (configFile.exists()) configFile.readText() else ""
        val envText = if (envFile.exists()) envFile.readText() else ""

        var provider = ""
        var model = ""
        val customProviders = mutableListOf<CustomProvider>()

        val lines = configText.lines()
        var i = 0
        var inModel = false
        var inProviders = false
        var currentProviderName = ""
        var currentBaseUrl = ""
        var currentKeyEnv = ""
        var currentModels = mutableListOf<String>()

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                // 顶层 model: 段
                trimmed.startsWith("model:") && !line.startsWith(" ") -> {
                    inModel = true
                    inProviders = false
                    flushProvider(customProviders, currentProviderName, currentBaseUrl, currentKeyEnv, currentModels)
                    currentProviderName = ""; currentBaseUrl = ""; currentKeyEnv = ""; currentModels = mutableListOf()
                }
                // 顶层 providers: 段
                trimmed.startsWith("providers:") && !line.startsWith(" ") -> {
                    inModel = false
                    inProviders = true
                    flushProvider(customProviders, currentProviderName, currentBaseUrl, currentKeyEnv, currentModels)
                    currentProviderName = ""; currentBaseUrl = ""; currentKeyEnv = ""; currentModels = mutableListOf()
                }
                // providers: 下的子键（provider 名称）
                inProviders && trimmed.isNotEmpty() && !trimmed.startsWith("-") && trimmed.contains(":") -> {
                    val indent = line.takeWhile { it == ' ' }.length
                    if (indent == 2) {
                        // 新的 provider 名称
                        flushProvider(customProviders, currentProviderName, currentBaseUrl, currentKeyEnv, currentModels)
                        currentProviderName = trimmed.substringBefore(":").trim()
                        currentBaseUrl = ""
                        currentKeyEnv = ""
                        currentModels = mutableListOf()
                    } else if (indent >= 4 && currentProviderName.isNotEmpty()) {
                        // provider 的属性
                        val (k, v) = trimmed.split(":", limit = 2).map { it.trim() }
                        when (k) {
                            "base_url" -> currentBaseUrl = v.trim('"')
                            "key_env", "api_key_env" -> currentKeyEnv = v.trim('"')
                        }
                    }
                }
                // providers: > provider > models: 下的模型名
                inProviders && trimmed.isNotEmpty() && !trimmed.startsWith("-") && !trimmed.contains(": ") -> {
                    // models 子段下的模型名（如 "  model-name:"）
                    val indent = line.takeWhile { it == ' ' }.length
                    if (indent >= 6 && currentProviderName.isNotEmpty()) {
                        val modelName = trimmed.removeSuffix(":").trim().trim('"')
                        if (modelName.isNotEmpty()) {
                            currentModels.add(modelName)
                        }
                    }
                }
                // model: 段下的字段
                inModel && trimmed.isNotEmpty() && trimmed.contains(":") -> {
                    val (k, v) = trimmed.split(":", limit = 2).map { it.trim() }
                    when (k) {
                        "provider" -> provider = v.trim('"')
                        // Hermes 用 "default" 作为模型名键，兼容 "name" 和 "model"
                        "default", "name", "model" -> model = v.trim('"')
                    }
                }
                // 遇到新的顶层段，结束当前段
                trimmed.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#") && !trimmed.startsWith("-") -> {
                    if (inModel || inProviders) {
                        flushProvider(customProviders, currentProviderName, currentBaseUrl, currentKeyEnv, currentModels)
                        currentProviderName = ""; currentBaseUrl = ""; currentKeyEnv = ""; currentModels = mutableListOf()
                        inModel = false
                        inProviders = false
                    }
                }
            }
            i++
        }
        // 处理最后一个 provider
        flushProvider(customProviders, currentProviderName, currentBaseUrl, currentKeyEnv, currentModels)

        // 解析 .env
        val apiKeys = mutableMapOf<String, String>()
        for (envLine in envText.lines()) {
            val lt = envLine.trim()
            if (lt.isEmpty() || lt.startsWith("#")) continue
            val eqIdx = lt.indexOf('=')
            if (eqIdx > 0) {
                val key = lt.substring(0, eqIdx).trim()
                val value = lt.substring(eqIdx + 1).trim().trim('"')
                if (key.isNotEmpty()) apiKeys[key] = value
            }
        }

        return Config(provider, model, apiKeys, customProviders)
    }

    private fun flushProvider(
        list: MutableList<CustomProvider>,
        name: String,
        baseUrl: String,
        keyEnv: String,
        models: MutableList<String>,
    ) {
        if (name.isNotEmpty()) {
            list.add(CustomProvider(name, baseUrl, keyEnv, models.toList()))
        }
    }

    /** 设置默认模型和 provider */
    fun setDefaultModel(provider: String, model: String): Boolean {
        return try {
            val configText = if (configFile.exists()) configFile.readText() else ""
            val lines = configText.lines().toMutableList()
            var providerSet = false
            var modelSet = false
            var inModel = false
            var modelStartIdx = -1

            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("model:") && !lines[i].startsWith(" ")) {
                    inModel = true
                    modelStartIdx = i
                    continue
                }
                if (inModel && trimmed.isNotEmpty() && !lines[i].startsWith(" ") && !trimmed.startsWith("-")) {
                    inModel = false
                }
                if (inModel) {
                    if (trimmed.startsWith("provider:")) {
                        lines[i] = "  provider: \"$provider\""
                        providerSet = true
                    } else if (trimmed.startsWith("default:") || trimmed.startsWith("name:") || trimmed.startsWith("model:")) {
                        lines[i] = "  default: \"$model\""
                        modelSet = true
                    }
                }
            }

            // 如果没有 model 段，添加一个
            if (modelStartIdx < 0) {
                lines.add(0, "model:")
                lines.add(1, "  provider: \"$provider\"")
                lines.add(2, "  default: \"$model\"")
            } else if (!providerSet || !modelSet) {
                var insertIdx = modelStartIdx + 1
                if (!providerSet) {
                    lines.add(insertIdx, "  provider: \"$provider\"")
                    insertIdx++
                }
                if (!modelSet) {
                    lines.add(insertIdx, "  default: \"$model\"")
                }
            }

            configFile.writeText(lines.joinToString("\n"))
            Log.i(TAG, "Default model set: $provider / $model")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setDefaultModel failed", e)
            false
        }
    }

    /** 设置 API Key（写入 .env） */
    fun setApiKey(key: String, value: String): Boolean {
        return try {
            val envText = if (envFile.exists()) envFile.readText() else ""
            val lines = envText.lines().toMutableList()
            var found = false

            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("$key=") || trimmed.startsWith("$key =")) {
                    lines[i] = "$key=$value"
                    found = true
                    break
                }
            }

            if (!found) {
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
                lines.add("$key=$value")
            }

            envFile.writeText(lines.joinToString("\n"))
            Log.i(TAG, "API key set: $key")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setApiKey failed", e)
            false
        }
    }

    /**
     * 添加自定义 provider（写入 config.yaml 的 providers: 段）。
     * 格式：
     *   providers:
     *     provider-name:
     *       base_url: "https://..."
     *       key_env: "API_KEY_ENV"
     */
    fun addCustomProvider(name: String, baseUrl: String, apiKeyEnv: String, models: List<String>): Boolean {
        return try {
            val configText = if (configFile.exists()) configFile.readText() else ""
            val sb = StringBuilder(configText)
            if (!configText.endsWith("\n") && configText.isNotEmpty()) sb.append("\n")

            // 检查是否已有 providers: 段
            if (configText.contains("\nproviders:") || configText.startsWith("providers:")) {
                sb.append("  $name:\n")
            } else {
                sb.append("\nproviders:\n")
                sb.append("  $name:\n")
            }
            sb.append("    base_url: \"$baseUrl\"\n")
            sb.append("    key_env: \"$apiKeyEnv\"\n")
            if (models.isNotEmpty()) {
                sb.append("    models:\n")
                for (m in models) {
                    sb.append("      $m:\n")
                }
            }

            configFile.writeText(sb.toString())
            Log.i(TAG, "Custom provider added: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addCustomProvider failed", e)
            false
        }
    }

    /** 删除自定义 provider（从 config.yaml 的 providers: 段移除） */
    fun removeCustomProvider(name: String): Boolean {
        return try {
            val configText = if (configFile.exists()) configFile.readText() else ""
            val lines = configText.lines().toMutableList()
            val result = mutableListOf<String>()
            var inProviders = false
            var skipping = false

            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                val indent = lines[i].takeWhile { it == ' ' }.length

                if (trimmed.startsWith("providers:") && indent == 0) {
                    inProviders = true
                    result.add(lines[i])
                    continue
                }
                if (indent == 0 && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inProviders = false
                    skipping = false
                    result.add(lines[i])
                    continue
                }
                if (inProviders) {
                    if (indent == 2 && trimmed.isNotEmpty()) {
                        // provider 名称行
                        val pName = trimmed.substringBefore(":").trim()
                        if (pName == name) {
                            skipping = true
                            continue
                        } else {
                            skipping = false
                            result.add(lines[i])
                            continue
                        }
                    }
                    if (skipping) {
                        continue
                    }
                    result.add(lines[i])
                } else {
                    result.add(lines[i])
                }
            }

            configFile.writeText(result.joinToString("\n"))
            Log.i(TAG, "Custom provider removed: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "removeCustomProvider failed", e)
            false
        }
    }

    /** 检查配置目录是否存在 */
    fun isConfigAvailable(): Boolean {
        return hermesDir.exists() && (configFile.exists() || envFile.exists())
    }

    /** 获取 state.db 路径（用于会话历史直接读取） */
    fun getStateDbPath(): File {
        return File(hermesDir, "state.db")
    }
}
