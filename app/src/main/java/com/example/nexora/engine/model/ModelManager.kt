package com.example.nexora.engine.model

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceMemoryState(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isLowMemory: Boolean,
    val thresholdMb: Long
)

class ModelManager(private val context: Context) {

    private val _engines = mutableMapOf<String, ModelEngine>()
    private val _activeEngineId = MutableStateFlow("NEXORA-CORE-Q4")
    val activeEngineId: StateFlow<String> = _activeEngineId.asStateFlow()

    private val _activeEngine = MutableStateFlow<ModelEngine>(NexoraQuantizedLocalEngine())
    val activeEngine: StateFlow<ModelEngine> = _activeEngine.asStateFlow()

    private val _memoryState = MutableStateFlow(getDeviceMemory())
    val memoryState: StateFlow<DeviceMemoryState> = _memoryState.asStateFlow()

    private val _customModelUrls = mutableMapOf<String, String>()
    private val settingsStore = com.example.nexora.model.settings.NexoraSettingsStore.getInstance(context)

    private val _gpuEnabled = MutableStateFlow(settingsStore.gpuEnabled.value)
    val gpuEnabled: StateFlow<Boolean> = _gpuEnabled.asStateFlow()

    private val _cpuThreads = MutableStateFlow(settingsStore.cpuThreads.value)
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    private val _hardwareInfo = MutableStateFlow(com.example.nexora.engine.hardware.HardwareAcceleratorDetector.detect(context))
    val hardwareInfo: StateFlow<com.example.nexora.engine.hardware.HardwareAccelerationInfo> = _hardwareInfo.asStateFlow()

    init {
        // Register default model engines & new Qwen native thinking models
        val defaultCore = NexoraQuantizedLocalEngine()
        val qwenThinkingNative = TinyLlamaGGUFAdapter(
            modelId = "QWEN3-1.7B-THINKING",
            name = "Qwen3-1.7B-Abliterated (thinking native)",
            sizeMb = 1150,
            ramMb = 1450,
            quantization = "Q4_K_M",
            contextLength = 8192,
            description = "Native reasoning model with <think> reflection support. Uncensored and optimized for local reasoning.",
            supportsNativeThinking = true,
            initialCustomUrl = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/qwen3-1.7b-abliterated-q4_k_m.gguf"
        )
        val qwenBig = TinyLlamaGGUFAdapter(
            modelId = "QWEN2.5-3B-Q4",
            name = "Qwen2.5-3B-Instruct (GGUF Q4)",
            sizeMb = 1950,
            ramMb = 2450,
            quantization = "Q4_K_M",
            contextLength = 8192,
            description = "High-parameter multi-task model with advanced multilingual and reasoning capability.",
            supportsNativeThinking = true
        )
        val tinyLlama = TinyLlamaGGUFAdapter(
            modelId = "TINYLLAMA-1.1B-Q4",
            name = "TinyLlama 1.1B Chat (GGUF)",
            sizeMb = 640,
            ramMb = 850
        )
        val phi2 = TinyLlamaGGUFAdapter(
            modelId = "PHI-2-MOBILE-Q4",
            name = "Phi-2 Mobile Reasoning 2.7B",
            sizeMb = 1600,
            ramMb = 2100
        )

        _engines[defaultCore.modelInfo.id] = defaultCore
        _engines[qwenThinkingNative.modelInfo.id] = qwenThinkingNative
        _engines[qwenBig.modelInfo.id] = qwenBig
        _engines[tinyLlama.modelInfo.id] = tinyLlama
        _engines[phi2.modelInfo.id] = phi2
        _activeEngine.value = defaultCore
    }

    fun getDeviceMemory(): DeviceMemoryState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)

        val total = memoryInfo.totalMem / (1024 * 1024)
        val avail = memoryInfo.availMem / (1024 * 1024)
        val thresh = memoryInfo.threshold / (1024 * 1024)

        return DeviceMemoryState(
            totalRamMb = total,
            availableRamMb = avail,
            isLowMemory = memoryInfo.lowMemory,
            thresholdMb = thresh
        )
    }

    fun refreshMemoryState() {
        _memoryState.value = getDeviceMemory()
    }

    suspend fun switchModel(modelId: String): Result<ModelEngine> {
        val engine = _engines[modelId] ?: return Result.failure(IllegalArgumentException("Model not registered: $modelId"))

        // 1. CRITICAL: Unload currently active engine first to free RAM before checking or loading large model
        if (_activeEngine.value.isLoaded()) {
            _activeEngine.value.unload()
        }
        System.gc() // Hint garbage collection to release previous tensor buffers
        refreshMemoryState()

        // 2. Check RAM feasibility after previous engine is unloaded
        val requiredRam = engine.modelInfo.requiredRamMb
        val availableRam = _memoryState.value.availableRamMb
        if (requiredRam > availableRam && availableRam > 0) {
            // Re-load previous engine if unable to load target
            _activeEngine.value.load()
            return Result.failure(
                IllegalStateException("Insufficient RAM to load ${engine.modelInfo.name}. Requires ${requiredRam}MB, but only ${availableRam}MB is free after unloading.")
            )
        }

        // 3. Strictly verify file existence on internal disk (DILARANG status AKTIF palsu tanpa file di disk)
        if (modelId != "NEXORA-CORE-Q4") {
            val modelsDir = java.io.File(context.filesDir, "models")
            val fileExists = modelsDir.listFiles()?.any { f ->
                f.length() > 0L && !f.name.endsWith(".part") && (
                    f.name.contains(modelId, ignoreCase = true) ||
                    f.name.contains(engine.modelInfo.name.take(6), ignoreCase = true)
                )
            } ?: false

            if (!fileExists) {
                _activeEngine.value.load()
                return Result.failure(
                    IllegalStateException("File model belum ada atau belum utuh di disk internal app. Harap unduh atau impor file model terlebih dahulu.")
                )
            }
        }

        // 4. Load new engine and synchronize active hardware acceleration
        when (engine) {
            is NexoraQuantizedLocalEngine -> {
                engine.setGpuEnabled(_gpuEnabled.value)
                engine.setCpuThreads(_cpuThreads.value)
            }
            is TinyLlamaGGUFAdapter -> {
                engine.setGpuEnabled(_gpuEnabled.value)
                engine.setCpuThreads(_cpuThreads.value)
            }
        }
        engine.load()
        _activeEngineId.value = modelId
        _activeEngine.value = engine
        refreshMemoryState()
        return Result.success(engine)
    }

    suspend fun unloadActiveModel() {
        _activeEngine.value.unload()
        System.gc()
        refreshMemoryState()
    }

    suspend fun loadActiveModel(): Boolean {
        val res = _activeEngine.value.load()
        refreshMemoryState()
        return res
    }

    fun getRegisteredEngines(): List<ModelInfo> {
        return _engines.values.map { engine ->
            val info = engine.modelInfo
            val customUrl = _customModelUrls[info.id] ?: info.customDownloadUrl
            info.copy(
                customDownloadUrl = customUrl,
                isGpuEnabled = _gpuEnabled.value,
                cpuThreads = _cpuThreads.value
            )
        }
    }

    fun updateCustomModelUrl(modelId: String, url: String) {
        _customModelUrls[modelId] = url
        val engine = _engines[modelId]
        if (engine is TinyLlamaGGUFAdapter) {
            engine.setCustomDownloadUrl(url)
        }
    }

    fun registerEngine(engine: ModelEngine) {
        _engines[engine.modelInfo.id] = engine
    }

    fun removeEngine(modelId: String): Boolean {
        if (modelId == _activeEngineId.value) return false
        return _engines.remove(modelId) != null
    }

    fun configureThreads(threads: Int) {
        val clamped = threads.coerceIn(1, 8)
        settingsStore.setCpuThreads(clamped)
        _cpuThreads.value = clamped
        for (engine in _engines.values) {
            when (engine) {
                is NexoraQuantizedLocalEngine -> engine.setCpuThreads(clamped)
                is TinyLlamaGGUFAdapter -> engine.setCpuThreads(clamped)
            }
        }
        _activeEngine.value = _engines[_activeEngineId.value] ?: _activeEngine.value
    }

    fun configureGpu(enabled: Boolean): Boolean {
        settingsStore.setGpuEnabled(enabled)
        _gpuEnabled.value = enabled
        for (engine in _engines.values) {
            when (engine) {
                is NexoraQuantizedLocalEngine -> engine.setGpuEnabled(enabled)
                is TinyLlamaGGUFAdapter -> engine.setGpuEnabled(enabled)
            }
        }
        _activeEngine.value = _engines[_activeEngineId.value] ?: _activeEngine.value
        return enabled
    }
}
