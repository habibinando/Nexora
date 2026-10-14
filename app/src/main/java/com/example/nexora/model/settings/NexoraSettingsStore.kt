package com.example.nexora.model.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NexoraSettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nexora_settings_v3", Context.MODE_PRIVATE)

    private val buildApiKey: String = try {
        val k = com.example.BuildConfig.GEMINI_API_KEY
        if (k.isNotBlank() && k != "MY_GEMINI_API_KEY") k else ""
    } catch (_: Exception) { "" }

    // 1. Strict Local-First (default: true, operates 100% on-device with zero API key)
    private val _offlineEnforced = MutableStateFlow(
        prefs.getBoolean(KEY_OFFLINE_ENFORCED, true)
    )
    val offlineEnforced: StateFlow<Boolean> = _offlineEnforced.asStateFlow()

    // 2. Non-thinking Mode (default: false)
    private val _nonThinkingMode = MutableStateFlow(prefs.getBoolean(KEY_NON_THINKING, false))
    val nonThinkingMode: StateFlow<Boolean> = _nonThinkingMode.asStateFlow()

    // 3. GPU Hardware Acceleration (default: false)
    private val _gpuEnabled = MutableStateFlow(prefs.getBoolean(KEY_GPU_ENABLED, false))
    val gpuEnabled: StateFlow<Boolean> = _gpuEnabled.asStateFlow()

    // 4. CPU Threads (default: 4)
    private val _cpuThreads = MutableStateFlow(prefs.getInt(KEY_CPU_THREADS, 4))
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    // 5. Max Retries (default: 4)
    private val _maxRetries = MutableStateFlow(prefs.getInt(KEY_MAX_RETRIES, 4))
    val maxRetries: StateFlow<Int> = _maxRetries.asStateFlow()

    // 6. Tool states map (toolName -> isEnabled)
    private val _toolStates = MutableStateFlow<Map<String, Boolean>>(loadAllToolStates())
    val toolStates: StateFlow<Map<String, Boolean>> = _toolStates.asStateFlow()

    // 7. Gemini API Key & Model for authentic live AI generation
    private val _geminiApiKey = MutableStateFlow(
        prefs.getString(KEY_GEMINI_API_KEY, null)?.ifBlank { null } ?: buildApiKey
    )
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _geminiModel = MutableStateFlow(
        prefs.getString(KEY_GEMINI_MODEL, "gemini-3.5-flash")?.let {
            if (it == "gemini-2.5-flash" || it == "gemini-2.0-flash" || it == "gemini-1.5-flash" || it.isBlank()) "gemini-3.5-flash" else it
        } ?: "gemini-3.5-flash"
    )
    val geminiModel: StateFlow<String> = _geminiModel.asStateFlow()

    // 8. Trained Weights Chat Mode (Default false until toggled or trained)
    private val _useTrainedWeights = MutableStateFlow(prefs.getBoolean(KEY_USE_TRAINED_WEIGHTS, false))
    val useTrainedWeights: StateFlow<Boolean> = _useTrainedWeights.asStateFlow()

    fun setUseTrainedWeights(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_TRAINED_WEIGHTS, enabled).commit()
        _useTrainedWeights.value = enabled
    }

    fun setGeminiApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).commit()
        _geminiApiKey.value = trimmed
    }

    fun setGeminiModel(model: String) {
        val trimmed = model.trim()
        prefs.edit().putString(KEY_GEMINI_MODEL, trimmed).commit()
        _geminiModel.value = trimmed
    }

    private fun loadAllToolStates(): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        val defaultTools = listOf(
            "CalculatorTool" to true,
            "FileManagerTool" to true,
            "LocalCodeExecutor" to true,
            "python_exec" to true,
            "TextProcessorTool" to true,
            "JsonProcessorTool" to true,
            "AndroidUtilityTool" to true,
            "WebSearchTool" to false // disabled by default in strict local-first
        )

        for ((name, defaultEnabled) in defaultTools) {
            val key = "$KEY_TOOL_PREFIX$name"
            val enabled = prefs.getBoolean(key, defaultEnabled)
            map[name] = enabled
        }
        return map
    }

    fun isToolEnabled(toolName: String): Boolean {
        val key = "$KEY_TOOL_PREFIX$toolName"
        val defaultVal = if (toolName == "WebSearchTool") false else true
        return prefs.getBoolean(key, defaultVal)
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_TOOL_PREFIX$toolName", enabled).commit()
        val current = _toolStates.value.toMutableMap()
        current[toolName] = enabled
        _toolStates.value = current

        // If WebSearchTool is explicitly turned ON, disengage strict offline
        if (toolName == "WebSearchTool" && enabled) {
            setOfflineEnforced(false)
        }
    }

    fun setOfflineEnforced(enforced: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_ENFORCED, enforced).commit()
        _offlineEnforced.value = enforced
        if (enforced) {
            // Turning on strict offline disables WebSearchTool
            setToolEnabled("WebSearchTool", false)
        }
    }

    fun setNonThinkingMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NON_THINKING, enabled).commit()
        _nonThinkingMode.value = enabled
    }

    fun setGpuEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GPU_ENABLED, enabled).commit()
        _gpuEnabled.value = enabled
    }

    fun setCpuThreads(threads: Int) {
        val clamped = threads.coerceIn(1, 8)
        prefs.edit().putInt(KEY_CPU_THREADS, clamped).commit()
        _cpuThreads.value = clamped
    }

    fun setMaxRetries(retries: Int) {
        val clamped = retries.coerceIn(1, 10)
        prefs.edit().putInt(KEY_MAX_RETRIES, clamped).commit()
        _maxRetries.value = clamped
    }

    companion object {
        private const val KEY_OFFLINE_ENFORCED = "pref_strict_local_first"
        private const val KEY_NON_THINKING = "pref_non_thinking_mode"
        private const val KEY_GPU_ENABLED = "pref_gpu_acceleration"
        private const val KEY_CPU_THREADS = "pref_cpu_threads"
        private const val KEY_MAX_RETRIES = "pref_max_retries"
        private const val KEY_TOOL_PREFIX = "pref_tool_enabled_"
        private const val KEY_GEMINI_API_KEY = "pref_gemini_api_key"
        private const val KEY_GEMINI_MODEL = "pref_gemini_model"
        private const val KEY_USE_TRAINED_WEIGHTS = "pref_use_trained_weights"

        @Volatile
        private var INSTANCE: NexoraSettingsStore? = null

        fun getInstance(context: Context): NexoraSettingsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NexoraSettingsStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
