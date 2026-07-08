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

    /** 读取完整配置 */
    fun readConfig(): Config {
        val configText = if (configFile.exists()) configFile.readText() else ""
        val envText = if (envFile.exists()) envFile.readText() else ""

        // 解析 config.yaml
        var provider = ""
        var model = ""
        val customProviders = mutableListOf<CustomProvider>()

        // 简单 YAML 解析 — 只处理我们关心的字段
        val lines = configText.lines()
        var i = 0
        var inModel = false
        var inCustomProviders = false
        var currentProvider: MutableMap<String, Any>? = null
        var currentModels: MutableList<String>? = null

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                trimmed.startsWith("model:") -> {
                    inModel = true
                    inCustomProviders = false
                }
                trimmed.startsWith("custom_providers:") || trimmed.startsWith("providers:") -> {
                    inModel = false
                    inCustomProviders = true
                }
                trimmed.startsWith("- ") && inCustomProviders -> {
                    // 新 provider 开始
                    if (currentProvider != null) {
                        customProviders.add(CustomProvider(
                            name = currentProvider["name"] as? String ?: "",
                            baseUrl = currentProvider["base_url"] as? String ?: "",
                            apiKeyEnv = currentProvider["api_key_env"] as? String ?: "",
                            models = (currentProvider["models"] as? List<String>) ?: emptyList(),
                        ))
                    }
                    currentProvider = mutableMapOf()
                    currentModels = mutableListOf()
                    currentProvider["models"] = currentModels!!
                    // 解析同行内容 "- name: xxx"
                    val rest = trimmed.removePrefix("- ").trim()
                    if (rest.contains(":")) {
                        val (k, v) = rest.split(":", limit = 2).map { it.trim() }
                        currentProvider[k] = v
                    }
                }
                trimmed.contains(":") && inCustomProviders && currentProvider != null -> {
                    val (k, v) = trimmed.split(":", limit = 2).map { it.trim() }
                    when (k) {
                        "models" -> { /* 列表开始，currentModels 已初始化 */ }
                        else -> currentProvider[k] = v
                    }
                }
                trimmed.startsWith("- ") && currentModels != null -> {
                    currentModels.add(trimmed.removePrefix("- ").trim().trim('"'))
                }
                trimmed.contains(":") && inModel -> {
                    val (k, v) = trimmed.split(":", limit = 2).map { it.trim() }
                    when (k) {
                        "provider" -> provider = v
                        "name" -> model = v
                    }
                }
                trimmed.isEmpty() -> {
                    if (inCustomProviders && currentProvider != null) {
                        customProviders.add(CustomProvider(
                            name = currentProvider["name"] as? String ?: "",
                            baseUrl = currentProvider["base_url"] as? String ?: "",
                            apiKeyEnv = currentProvider["api_key_env"] as? String ?: "",
                            models = (currentProvider["models"] as? List<String>) ?: emptyList(),
                        ))
                        currentProvider = null
                        currentModels = null
                    }
                }
            }
            i++
        }
        // 处理最后一个 provider
        if (currentProvider != null) {
            customProviders.add(CustomProvider(
                name = currentProvider["name"] as? String ?: "",
                baseUrl = currentProvider["base_url"] as? String ?: "",
                apiKeyEnv = currentProvider["api_key_env"] as? String ?: "",
                models = (currentProvider["models"] as? List<String>) ?: emptyList(),
            ))
        }

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
                if (trimmed.startsWith("model:")) {
                    inModel = true
                    modelStartIdx = i
                    continue
                }
                if (inModel && trimmed.isNotEmpty() && !trimmed.startsWith(" ") && !trimmed.startsWith("-")) {
                    inModel = false
                }
                if (inModel) {
                    if (trimmed.startsWith("provider:")) {
                        lines[i] = lines[i].replaceBefore(':', "  provider:") + " $provider"
                        providerSet = true
                    } else if (trimmed.startsWith("name:")) {
                        lines[i] = lines[i].replaceBefore(':', "  name:") + " $model"
                        modelSet = true
                    }
                }
            }

            // 如果没有 model 段，添加一个
            if (modelStartIdx < 0) {
                lines.add(0, "model:")
                lines.add(1, "  provider: $provider")
                lines.add(2, "  name: $model")
            } else if (!providerSet || !modelSet) {
                // model 段存在但缺少字段，在 model: 行后插入
                var insertIdx = modelStartIdx + 1
                if (!providerSet) {
                    lines.add(insertIdx, "  provider: $provider")
                    insertIdx++
                }
                if (!modelSet) {
                    lines.add(insertIdx, "  name: $model")
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

    /** 添加自定义 provider（写入 config.yaml） */
    fun addCustomProvider(name: String, baseUrl: String, apiKeyEnv: String, models: List<String>): Boolean {
        return try {
            val configText = if (configFile.exists()) configFile.readText() else ""
            val sb = StringBuilder(configText)
            if (!configText.endsWith("\n") && configText.isNotEmpty()) sb.append("\n")

            sb.append("\ncustom_providers:\n")
            sb.append("  - name: $name\n")
            sb.append("    base_url: $baseUrl\n")
            sb.append("    api_key_env: $apiKeyEnv\n")
            sb.append("    models:\n")
            for (m in models) {
                sb.append("      - $m\n")
            }

            configFile.writeText(sb.toString())
            Log.i(TAG, "Custom provider added: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addCustomProvider failed", e)
            false
        }
    }

    /** 删除自定义 provider（从 config.yaml 移除） */
    fun removeCustomProvider(name: String): Boolean {
        return try {
            val configText = if (configFile.exists()) configFile.readText() else ""
            val lines = configText.lines().toMutableList()
            val result = mutableListOf<String>()
            var skipping = false

            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("- name:") && trimmed.contains(name)) {
                    skipping = true
                    continue
                }
                if (skipping) {
                    // 跳过直到下一个 - 或空行后非缩进行
                    if (trimmed.startsWith("- ") || (trimmed.isNotEmpty() && !lines[i].startsWith(" "))) {
                        skipping = false
                        result.add(lines[i])
                    }
                    // 否则跳过
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
