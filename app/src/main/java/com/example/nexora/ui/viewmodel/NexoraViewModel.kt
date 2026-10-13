package com.example.nexora.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexora.engine.experiment.ExperimentEngine
import com.example.nexora.engine.experiment.ExperimentProgress
import com.example.nexora.engine.memory.MemoryEngine
import com.example.nexora.engine.model.DeviceMemoryState
import com.example.nexora.engine.model.ModelContext
import com.example.nexora.engine.model.ModelInfo
import com.example.nexora.engine.model.ModelManager
import com.example.nexora.engine.neural.LocalNeuralTrainer
import com.example.nexora.engine.neural.LocalTrainingProgress
import com.example.nexora.engine.neural.OptimizerType
import com.example.nexora.engine.neural.TrainingRamProfile
import com.example.nexora.engine.strategy.StrategyEngine
import com.example.nexora.engine.tool.NexoraTool
import com.example.nexora.engine.tool.ToolExecutionResult
import com.example.nexora.engine.tool.ToolRegistry
import com.example.nexora.engine.training.TrainingBenchmarkResult
import com.example.nexora.engine.training.TrainingManager
import com.example.nexora.engine.dataset.HuggingFaceDatasetClient
import com.example.nexora.engine.dataset.HuggingFaceFetchResult
import com.example.nexora.engine.dataset.HuggingFaceSample
import com.example.nexora.engine.peft.PeftConfigManager
import com.example.nexora.engine.peft.PeftLoraConfig
import com.example.nexora.model.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HumanOversightPrompt(
    val toolName: String,
    val parameters: Map<String, String>,
    val riskReason: String,
    val onApprove: suspend () -> Unit,
    val onDeny: suspend () -> Unit
)

data class MathOracleResult(
    val expression: String,
    val oracleExpected: String,
    val computedOutput: String,
    val latencyMs: Long,
    val isValid: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ThinkingStreamState(
    val rawText: String = "",
    val thinkingText: String = "",
    val answerText: String = "",
    val isThinking: Boolean = false,
    val isFinishedThinking: Boolean = false
)

class NexoraViewModel(application: Application) : AndroidViewModel(application) {

    private val db = NexoraDatabase.getDatabase(application, viewModelScope)
    val dao: NexoraDao = db.nexoraDao()

    val settingsStore = com.example.nexora.model.settings.NexoraSettingsStore.getInstance(application)
    val modelManager = ModelManager(application)
    val modelIngestionManager = com.example.nexora.engine.model.ModelIngestionManager(application, modelManager)
    val memoryEngine = MemoryEngine(dao)
    val strategyEngine = StrategyEngine(dao)
    val toolRegistry = ToolRegistry(application, dao)
    val experimentEngine = ExperimentEngine(dao, strategyEngine, toolRegistry, modelManager, memoryEngine)
    val trainingManager = TrainingManager(dao, application)
    val localNeuralTrainer = LocalNeuralTrainer(application)

    // Reactive StateFlows
    val allMemories: StateFlow<List<MemoryEntity>> = dao.getAllMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStrategies: StateFlow<List<StrategyEntity>> = dao.getAllStrategies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExperiments: StateFlow<List<ExperimentEntity>> = dao.getAllExperiments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatMessages: StateFlow<List<ChatMessageEntity>> = dao.getAllChatMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val toolsState: StateFlow<List<NexoraTool>> = toolRegistry.toolsState

    val gpuEnabled: StateFlow<Boolean> = settingsStore.gpuEnabled
    val cpuThreads: StateFlow<Int> = settingsStore.cpuThreads
    val hardwareInfo: StateFlow<com.example.nexora.engine.hardware.HardwareAccelerationInfo> = modelManager.hardwareInfo

    val activeModelInfo: StateFlow<ModelInfo> = combine(
        modelManager.activeEngine,
        settingsStore.gpuEnabled,
        settingsStore.cpuThreads
    ) { engine, gpu, threads ->
        engine.modelInfo.copy(isGpuEnabled = gpu, cpuThreads = threads)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), modelManager.activeEngine.value.modelInfo)

    val deviceMemory: StateFlow<DeviceMemoryState> = modelManager.memoryState

    val catalogModels: StateFlow<List<com.example.nexora.engine.model.CatalogModelEntry>> = modelIngestionManager.catalogEntries
    val isModelActivating: StateFlow<Boolean> = modelIngestionManager.isActivating
    val activatingModelName: StateFlow<String> = modelIngestionManager.activatingModelName
    val activationStage: StateFlow<String> = modelIngestionManager.activationStage

    val experimentProgress: StateFlow<ExperimentProgress> = experimentEngine.progress

    val trainingData: StateFlow<List<TrainingDataEntity>> = trainingManager.allTrainingData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val adapterModules: StateFlow<List<AdapterModuleEntity>> = trainingManager.allAdapters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelVersions: StateFlow<List<ModelVersionEntity>> = trainingManager.allModelVersions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val toolHistory: StateFlow<List<ToolHistoryEntity>> = dao.getRecentToolHistory(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Chat Streaming & Thinking State
    private val _streamingState = MutableStateFlow(ThinkingStreamState())
    val streamingState: StateFlow<ThinkingStreamState> = _streamingState.asStateFlow()

    val currentStreamingText: StateFlow<String> = _streamingState
        .map { if (it.isThinking) "${it.thinkingText} ▋" else it.answerText }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var skipThinkingRequested = false
    private var lastUserPrompt: String = ""

    // System Settings & Modes (Persisted Single Source of Truth)
    val offlineEnforced: StateFlow<Boolean> = settingsStore.offlineEnforced
    val nonThinkingMode: StateFlow<Boolean> = settingsStore.nonThinkingMode
    val maxRetries: StateFlow<Int> = settingsStore.maxRetries
    val geminiApiKey: StateFlow<String> = settingsStore.geminiApiKey
    val geminiModel: StateFlow<String> = settingsStore.geminiModel
    val useTrainedWeights: StateFlow<Boolean> = settingsStore.useTrainedWeights

    // Local Neural Trainer Flows
    val localTrainingProgress: StateFlow<LocalTrainingProgress> = localNeuralTrainer.progress
    val localTrainedInferenceOutput: StateFlow<String> = localNeuralTrainer.trainedInferenceOutput

    // Human Oversight
    private val _pendingHumanOversight = MutableStateFlow<HumanOversightPrompt?>(null)
    val pendingHumanOversight: StateFlow<HumanOversightPrompt?> = _pendingHumanOversight.asStateFlow()

    // Tool Test Output & Math Oracle
    private val _toolTestResult = MutableStateFlow<ToolExecutionResult?>(null)
    val toolTestResult: StateFlow<ToolExecutionResult?> = _toolTestResult.asStateFlow()

    private val _mathOracleResult = MutableStateFlow<MathOracleResult?>(null)
    val mathOracleResult: StateFlow<MathOracleResult?> = _mathOracleResult.asStateFlow()

    private val _benchmarkResult = MutableStateFlow<TrainingBenchmarkResult?>(null)
    val benchmarkResult: StateFlow<TrainingBenchmarkResult?> = _benchmarkResult.asStateFlow()

    // Hugging Face Datasets Integration
    private val _huggingFaceFetchResult = MutableStateFlow<HuggingFaceFetchResult?>(null)
    val huggingFaceFetchResult: StateFlow<HuggingFaceFetchResult?> = _huggingFaceFetchResult.asStateFlow()

    private val _isFetchingHuggingFace = MutableStateFlow(false)
    val isFetchingHuggingFace: StateFlow<Boolean> = _isFetchingHuggingFace.asStateFlow()

    // PEFT LoRA Config Integration
    private val _peftConfig = MutableStateFlow(PeftLoraConfig())
    val peftConfig: StateFlow<PeftLoraConfig> = _peftConfig.asStateFlow()

    fun setPeftConfig(config: PeftLoraConfig) {
        _peftConfig.value = config
    }

    fun fetchHuggingFaceDataset(datasetId: String, split: String = "train", limit: Int = 15) {
        viewModelScope.launch {
            _isFetchingHuggingFace.value = true
            try {
                val res = HuggingFaceDatasetClient.fetchDatasetRows(
                    datasetId = datasetId,
                    split = split,
                    limit = limit
                )
                _huggingFaceFetchResult.value = res
            } catch (e: Exception) {
                _huggingFaceFetchResult.value = HuggingFaceFetchResult(
                    isSuccess = false,
                    datasetId = datasetId,
                    samples = emptyList(),
                    totalFetched = 0,
                    sourceUrl = "",
                    errorMessage = e.message ?: "Kesalahan mengambil dataset dari Hugging Face"
                )
            } finally {
                _isFetchingHuggingFace.value = false
            }
        }
    }

    fun importHuggingFaceSamplesToDataset(samples: List<HuggingFaceSample>, onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            for (s in samples) {
                if (s.prompt.isNotBlank() && s.completion.isNotBlank()) {
                    trainingManager.addManualTrainingData(
                        prompt = s.prompt,
                        completion = s.completion,
                        category = s.category
                    )
                    count++
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(count)
            }
        }
    }

    fun setOfflineEnforced(enforced: Boolean) {
        settingsStore.setOfflineEnforced(enforced)
    }

    fun setNonThinkingMode(enabled: Boolean) {
        settingsStore.setNonThinkingMode(enabled)
    }

    fun setMaxRetries(retries: Int) {
        settingsStore.setMaxRetries(retries)
    }

    fun setGeminiApiKey(key: String) {
        settingsStore.setGeminiApiKey(key)
    }

    fun setGeminiModel(model: String) {
        settingsStore.setGeminiModel(model)
    }

    fun updateCustomModelUrl(modelId: String, url: String) {
        modelIngestionManager.updateModelUrl(modelId, url)
    }

    fun importModel(uri: android.net.Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                modelIngestionManager.importModelFromUri(uri) { success, msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        onResult(success, msg)
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    onResult(false, "Gagal mengimpor: ${e.message}")
                }
            }
        }
    }

    fun downloadModel(modelId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                modelIngestionManager.downloadModel(modelId) { success, msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        onResult(success, msg)
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    onResult(false, "Gagal mengunduh: ${e.message}")
                }
            }
        }
    }

    fun activateModel(modelId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            modelIngestionManager.activateModel(modelId) { success, msg ->
                if (success) {
                    viewModelScope.launch {
                        dao.setActiveModelVersion(modelId)
                    }
                }
                onResult(success, msg)
            }
        }
    }

    fun approveHumanOversight() {
        val prompt = _pendingHumanOversight.value
        _pendingHumanOversight.value = null
        if (prompt != null) {
            viewModelScope.launch {
                prompt.onApprove()
            }
        }
    }

    fun denyHumanOversight() {
        val prompt = _pendingHumanOversight.value
        _pendingHumanOversight.value = null
        if (prompt != null) {
            viewModelScope.launch {
                prompt.onDeny()
            }
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        viewModelScope.launch {
            val isAttachedDoc = userText.contains("[KONTEKS_RAHASIA_DOKUMEN]")
            val displayMessage = if (isAttachedDoc) {
                userText.substringBefore("[KONTEKS_RAHASIA_DOKUMEN]").trim()
            } else {
                userText.trim()
            }
            val reasoningPrompt = if (isAttachedDoc) {
                val header = userText.substringBefore("[KONTEKS_RAHASIA_DOKUMEN]").trim()
                val hiddenContent = userText.substringAfter("[KONTEKS_RAHASIA_DOKUMEN]").trim().take(4000)
                val userQ = header.substringAfter("\n").trim().ifBlank { "Tolong baca dan analisis berkas ini." }
                "Pengguna melampirkan berkas teks:\n\"\"\"\n$hiddenContent\n\"\"\"\n\nPertanyaan/Instruksi Pengguna: $userQ"
            } else {
                displayMessage
            }

            val trimmed = reasoningPrompt
            lastUserPrompt = displayMessage
            val lower = displayMessage.lowercase()

            // 1. Record User Message (Hanya pesan bersih yang disimpan ke riwayat tampilan, tidak membanjiri chat)
            dao.insertChatMessage(ChatMessageEntity(sender = "USER", text = displayMessage))

            // 2. Identify Intent & High Risk Checks
            val isExperiment = lower.startsWith("experiment:") || lower.startsWith("run experiment:") ||
                    lower.startsWith("uji coba:") || lower.startsWith("jalankan eksperimen:")

            if (isExperiment) {
                val goal = trimmed.substringAfter(":").trim().ifBlank { trimmed }
                dao.insertChatMessage(ChatMessageEntity(sender = "SYSTEM", text = "Memulai Autonomous Experiment Engine: \"$goal\""))
                launchExperiment(goal = goal, input = goal)
                return@launch
            }

            // Check if user specifically requested a high-risk tool
            val isPythonIntent = lower.startsWith("python:") || lower.startsWith("run python:") ||
                    lower.startsWith("jalankan python:")
            if (isPythonIntent) {
                val code = trimmed.substringAfter(":").trim()
                val result = toolRegistry.executeTool("python_exec", mapOf("code" to code))
                val reply = if (result.success) {
                    "Hasil eksekusi Python lokal (Chaquopy/Nexora Engine):\n```\n${result.output}\n```"
                } else {
                    "Eksekusi Python gagal:\n```\n${result.output.ifBlank { result.error ?: "" }}\n```"
                }
                dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = reply))
                return@launch
            }

            val isCodeExecutionIntent = lower.startsWith("run code:") || lower.startsWith("jalankan kode:") ||
                    lower.startsWith("execute code:")
            val isFileWriteOrDeleteIntent = lower.startsWith("file write:") || lower.startsWith("file delete:") ||
                    lower.startsWith("tulis file:") || lower.startsWith("hapus file:")
            val isWebSearchIntent = lower.startsWith("search:") || lower.startsWith("cari:") || lower.startsWith("web:")

            if (isCodeExecutionIntent) {
                val code = trimmed.substringAfter(":").trim()
                requestHumanOversight(
                    toolName = "LocalCodeExecutor",
                    parameters = mapOf("code" to code),
                    riskReason = "Eksekusi kode lokal berisiko komputasi atau memori."
                ) {
                    val result = toolRegistry.executeTool("LocalCodeExecutor", mapOf("code" to code))
                    val reply = if (result.success) {
                        "Luaran eksekusi kode lokal:\n```\n${result.output}\n```"
                    } else {
                        "Gagal mengeksekusi kode: ${result.error}"
                    }
                    dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = reply))
                }
                return@launch
            }

            if (isFileWriteOrDeleteIntent) {
                val action = if (lower.contains("delete") || lower.contains("hapus")) "delete" else "write"
                val payload = trimmed.substringAfter(":").trim()
                val filename = payload.substringBefore(",").trim().substringBefore(" ").trim()
                val content = if (action == "write") payload.substringAfter(",").trim() else ""

                requestHumanOversight(
                    toolName = "FileManagerTool",
                    parameters = mapOf("action" to action, "filename" to filename, "content" to content),
                    riskReason = "Operasi modifikasi sistem berkas sandbox ($action berkas '$filename')."
                ) {
                    val result = toolRegistry.executeTool("FileManagerTool", mapOf("action" to action, "filename" to filename, "content" to content))
                    dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = result.output.ifBlank { result.error ?: "Selesai" }))
                }
                return@launch
            }

            if (isWebSearchIntent) {
                val query = trimmed.substringAfter(":").trim()
                requestHumanOversight(
                    toolName = "WebSearchTool",
                    parameters = mapOf("query" to query),
                    riskReason = "Koneksi jaringan keluar (egress) melanggar mode Strict Local-First."
                ) {
                    if (offlineEnforced.value) {
                        dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = "WebSearch dibatalkan: Mode Strict Local-First sedang aktif di Settings."))
                    } else {
                        val result = toolRegistry.executeTool("WebSearchTool", mapOf("query" to query))
                        dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = result.output.ifBlank { result.error ?: "Tidak ada hasil pencarian." }))
                    }
                }
                return@launch
            }

            // Real Memory Storage Command
            val isMemoryStoreIntent = lower.startsWith("simpan memori:") || lower.startsWith("ingat bahwa:") ||
                    lower.startsWith("catat memori:") || lower.startsWith("remember:")
            if (isMemoryStoreIntent) {
                val content = trimmed.substringAfter(":").trim()
                val title = content.take(30).replace("\n", " ").trim()
                dao.insertMemory(
                    com.example.nexora.model.database.MemoryEntity(
                        type = "LONG_TERM_KNOWLEDGE",
                        title = title,
                        content = content,
                        tags = "user_input,offline,on_device",
                        confidence = 1.0f
                    )
                )
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "NEXORA",
                        text = "🧠 **Memori Tersimpan On-Device**\n\nPengetahuan berhasil dicatat ke database SQLite lokal:\n• **Judul**: $title\n• **Isi**: $content\n• Status: Tersimpan permanen & siap direcall oleh model."
                    )
                )
                return@launch
            }

            // Real Memory Query Command
            val isMemoryQueryIntent = lower.startsWith("cari memori:") || lower.startsWith("search memory:")
            if (isMemoryQueryIntent) {
                val query = trimmed.substringAfter(":").trim()
                val memories = dao.searchMemoriesList(query)
                val reply = if (memories.isEmpty()) {
                    "🔍 Tidak ditemukan memori terkait \"$query\" di basis data lokal."
                } else {
                    buildString {
                        appendLine("🔍 **Ditemukan ${memories.size} Memori Lokal**:")
                        for (m in memories) {
                            appendLine("• **${m.title}** (${m.type})")
                            appendLine("  ${m.content}")
                        }
                    }
                }
                dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = reply))
                return@launch
            }

            // Real On-Device Training Command
            val isTrainIntent = lower.startsWith("latih adapter:") || lower.startsWith("train adapter:") ||
                    lower == "latih adapter" || lower == "train adapter" || lower == "latih model"
            if (isTrainIntent) {
                val name = if (trimmed.contains(":")) trimmed.substringAfter(":").trim() else "Adaptive User LoRA"
                dao.insertChatMessage(ChatMessageEntity(sender = "SYSTEM", text = "Memulai pelatihan on-device untuk modul: \"$name\"..."))
                val trainResult = trainingManager.trainNewAdapter(name = name, description = "Dilatih dari pengalaman chat & memori")
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "NEXORA",
                        text = "📊 **Hasil Pelatihan On-Device Nyata**:\n\n${trainResult.summary}"
                    )
                )
                return@launch
            }

            // Real Incremental Step-by-Step Training ("Makan Sebutir-sebutir") Command
            val isIncrementalTrainIntent = lower == "latih sebutir" || lower == "cerna sebutir" ||
                    lower.startsWith("latih inkremental") || lower.startsWith("train incremental")
            if (isIncrementalTrainIntent) {
                dao.insertChatMessage(ChatMessageEntity(sender = "SYSTEM", text = "Memulai Pelatihan Inkremental langsung ke Bobot Model Utama (Base Model)..."))
                val dataset = dao.getIncludedTrainingDataList()
                val trainResult = localNeuralTrainer.trainIncrementalDirectToBaseModel(
                    dataset = dataset,
                    learningRate = 0.005f,
                    epochs = 1,
                    onProgress = { _, _, _, _ -> }
                )
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "NEXORA",
                        text = "🧠 **Hasil Pelatihan Inkremental Model Utama (Base Model)**:\n\n${trainResult.summary}\n\n*(Catatan: Bobot neural model utama diperbarui langsung tanpa membuat modul adapter terpisah)*"
                    )
                )
                return@launch
            }

            // Real Check Model File & Storage Command ("cek model" / "status storage")
            val isCheckModelIntent = lower == "cek model" || lower == "status model" ||
                    lower == "cek storage" || lower == "status storage" || lower == "cek berkas"
            if (isCheckModelIntent) {
                val modelsDir = modelIngestionManager.modelsDir
                val files = modelsDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: emptyList()
                val reply = buildString {
                    appendLine("📁 **Status Berkas & Penyimpanan Model On-Device**:")
                    appendLine("• **Direktori Fisik HP**: `${modelsDir.absolutePath}`")
                    appendLine("• **Total Berkas Model di Disk**: ${files.size} berkas")
                    appendLine()
                    if (files.isEmpty()) {
                        appendLine("*(Belum ada berkas model yang diimpor atau diunduh ke disk internal app)*")
                    } else {
                        files.forEachIndexed { i, f ->
                            val sizeMb = f.length() / (1024 * 1024)
                            val inspection = if (f.name.endsWith(".gguf") || f.name.contains("qwen") || f.name.contains("llama")) {
                                com.example.nexora.engine.model.GGUFMetadataParser.parse(f)
                            } else null
                            appendLine("${i + 1}. **${f.name}**")
                            appendLine("   - Ukuran Nyata: ${sizeMb} MB (%,d bytes)".format(f.length()))
                            appendLine("   - Status: Tersimpan Permanen di HP (Private Storage)")
                            if (inspection != null && inspection.isValid) {
                                appendLine("   - GGUF: ${inspection.architecture} | ${inspection.parameterCountFormatted} | ${inspection.detectedQuantization} | ${inspection.tensorCount} tensor")
                            }
                        }
                    }
                }
                dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = reply))
                return@launch
            }

            // Real Add Dataset Command ("tambah dataset: Prompt: ... | Solusi: ...")
            val isAddDatasetIntent = lower.startsWith("tambah dataset:") || lower.startsWith("add dataset:")
            if (isAddDatasetIntent) {
                val raw = trimmed.substringAfter(":").trim()
                val p = if (raw.contains("|")) raw.substringBefore("|").replace("prompt:", "", ignoreCase = true).trim() else raw
                val c = if (raw.contains("|")) raw.substringAfter("|").replace("solusi:", "", ignoreCase = true).replace("target:", "", ignoreCase = true).trim() else "Target jawaban terverifikasi"
                trainingManager.addManualTrainingData(p, c, "CHAT_INPUT")
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "NEXORA",
                        text = "📥 **Pasangan Dataset Berhasil Ditambahkan ke SQLite On-Device**:\n\n• **Prompt**: $p\n• **Solusi/Target**: $c\n• Siap dicerna oleh proses pelatihan adapter lokal."
                    )
                )
                return@launch
            }

            // Real Correction Command ("koreksi: <jawaban yang benar>")
            val isCorrectionIntent = lower.startsWith("koreksi:") || lower.startsWith("correct:")
            if (isCorrectionIntent) {
                val correctionText = trimmed.substringAfter(":").trim()
                val promptToUse = lastUserPrompt.ifBlank { "Prompt Percakapan Sebelumnya" }
                trainingManager.addManualTrainingData(promptToUse, correctionText, "CORRECTION_FEEDBACK")
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "NEXORA",
                        text = "✍️ **Koreksi Berhasil Disimpan ke Dataset Pelatihan**:\n\n• **Konteks Masukan**: $promptToUse\n• **Koreksi Pengguna**: $correctionText\n• Disimpan di database untuk dipelajari pada pelatihan adapter berikutnya."
                    )
                )
                return@launch
            }

            // 3. PURE GENERATIVE STREAMING INFERENCE WITH NATIVE THINKING BAR
            _isGenerating.value = true
            skipThinkingRequested = false
            _streamingState.value = ThinkingStreamState()

            // Check if Trained Weights Chat Mode is active
            if (settingsStore.useTrainedWeights.value && localNeuralTrainer.hasTrainedWeights()) {
                activeGenerationJob = launch(Dispatchers.Default) {
                    try {
                        val header = "⚡ [Bobot Neural Terlatih On-Device]\n"
                        val fullStringBuilder = StringBuilder(header)
                        _streamingState.value = ThinkingStreamState(
                            rawText = header,
                            answerText = header,
                            isThinking = false,
                            isFinishedThinking = true
                        )
                        localNeuralTrainer.runTrainedInferenceStream(trimmed, maxTokens = 150).collect { charToken ->
                            fullStringBuilder.append(charToken)
                            val currentText = fullStringBuilder.toString()
                            _streamingState.value = ThinkingStreamState(
                                rawText = currentText,
                                answerText = currentText,
                                isThinking = false,
                                isFinishedThinking = true
                            )
                        }
                        val finalResponse = fullStringBuilder.toString()
                        dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = finalResponse))
                    } catch (e: Exception) {
                        dao.insertChatMessage(
                            ChatMessageEntity(sender = "SYSTEM", text = "Inferensi bobot terlatih terhenti: ${e.message}")
                        )
                    } finally {
                        _isGenerating.value = false
                        _streamingState.value = ThinkingStreamState()
                    }
                }
                return@launch
            }

            val recentMemories = dao.getAllMemoriesList().take(4).map { "${it.title}: ${it.content}" }
            val chatHistory = dao.getAllChatMessagesList().takeLast(6).map { it.sender to it.text }
            activeGenerationJob = launch {
                try {
                    val context = ModelContext(
                        systemPrompt = "Nexora adalah asisten cerdas yang jujur, analitis, dan adaptif.",
                        conversationHistory = chatHistory,
                        recalledMemories = recentMemories,
                        availableTools = toolRegistry.getAllTools().filter { it.isEnabled }.map { it.name },
                        nonThinkingMode = nonThinkingMode.value,
                        apiKey = settingsStore.geminiApiKey.value,
                        preferredModel = settingsStore.geminiModel.value
                    )

                    val fullStringBuilder = StringBuilder()

                    modelManager.activeEngine.value.generateStream(trimmed, context).collect { token ->
                        fullStringBuilder.append(token)
                        val raw = fullStringBuilder.toString()

                        // Parse <think>...</think> on the fly
                        val hasOpenThink = raw.contains("<think>")
                        val hasCloseThink = raw.contains("</think>")

                        if (hasOpenThink && !hasCloseThink && !skipThinkingRequested) {
                            val thinking = raw.substringAfter("<think>").trim()
                            _streamingState.value = ThinkingStreamState(
                                rawText = raw,
                                thinkingText = thinking,
                                answerText = "",
                                isThinking = true,
                                isFinishedThinking = false
                            )
                        } else if (hasCloseThink || skipThinkingRequested) {
                            val thinking = if (hasOpenThink) raw.substringAfter("<think>").substringBefore("</think>").trim() else ""
                            val answer = if (hasCloseThink) raw.substringAfter("</think>").trimStart() else raw
                            _streamingState.value = ThinkingStreamState(
                                rawText = raw,
                                thinkingText = thinking,
                                answerText = answer,
                                isThinking = false,
                                isFinishedThinking = thinking.isNotBlank()
                            )
                        } else {
                            _streamingState.value = ThinkingStreamState(
                                rawText = raw,
                                thinkingText = "",
                                answerText = raw,
                                isThinking = false,
                                isFinishedThinking = false
                            )
                        }
                    }

                    val finalRaw = fullStringBuilder.toString()
                    dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = finalRaw))

                    // If this was a math calculation, ensure CalculatorTool record is kept in Call Logs
                    val isMath = trimmed.matches(Regex(".*[0-9]+\\s*(?:[+\\-*/^×÷xX]|kali|bagi|tambah|kurang)\\s*[0-9]+.*"))
                    if (isMath) {
                        toolRegistry.executeTool("CalculatorTool", mapOf("expression" to trimmed))
                    }

                } catch (e: Exception) {
                    dao.insertChatMessage(
                        ChatMessageEntity(sender = "SYSTEM", text = "Inferensi terhenti: ${e.message}")
                    )
                } finally {
                    _isGenerating.value = false
                    _streamingState.value = ThinkingStreamState()
                }
            }
        }
    }

    fun regenerateLastMessage() {
        if (!_isGenerating.value && lastUserPrompt.isNotBlank()) {
            sendMessage(lastUserPrompt)
        }
    }

    private fun requestHumanOversight(
        toolName: String,
        parameters: Map<String, String>,
        riskReason: String,
        onApprovedAction: suspend () -> Unit
    ) {
        _pendingHumanOversight.value = HumanOversightPrompt(
            toolName = toolName,
            parameters = parameters,
            riskReason = riskReason,
            onApprove = {
                onApprovedAction()
            },
            onDeny = {
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "SYSTEM",
                        text = "Aksi alat berisiko tinggi '$toolName' DITOLAK oleh pengawas manusia (Human Oversight Denied)."
                    )
                )
            }
        )
    }

    fun runMathOracleSelfTest() {
        viewModelScope.launch {
            val a = kotlin.random.Random.nextInt(120, 899)
            val b = kotlin.random.Random.nextInt(14, 98)
            val expr = "$a * $b"
            val expectedLong = a.toLong() * b.toLong()
            val expectedFormatted = String.format(java.util.Locale.US, "%,d", expectedLong)

            val start = System.currentTimeMillis()
            val res = toolRegistry.executeTool("CalculatorTool", mapOf("expression" to expr))
            val latency = System.currentTimeMillis() - start

            val cleanOutput = res.output.replace(",", "").trim()
            val isValid = res.success && (cleanOutput == expectedLong.toString() || res.output == expectedFormatted)

            _mathOracleResult.value = MathOracleResult(
                expression = "$a × $b",
                oracleExpected = expectedFormatted,
                computedOutput = res.output,
                latencyMs = latency,
                isValid = isValid
            )
        }
    }

    fun stopGeneration() {
        activeGenerationJob?.cancel()
        _isGenerating.value = false
        val current = _streamingState.value
        if (current.rawText.isNotBlank()) {
            viewModelScope.launch {
                dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = current.rawText + " [Dihentikan Pengguna]"))
                _streamingState.value = ThinkingStreamState()
            }
        }
    }

    fun skipThinking() {
        if (!skipThinkingRequested && _isGenerating.value) {
            skipThinkingRequested = true
            val current = _streamingState.value
            val savedThinking = current.thinkingText
            _streamingState.value = current.copy(
                isThinking = false,
                isFinishedThinking = true
            )
            // Cancel active thinking stream and force immediate final response generation from context
            activeGenerationJob?.cancel()
            activeGenerationJob = viewModelScope.launch {
                try {
                    val prompt = lastUserPrompt
                    val recentMemories = dao.getAllMemoriesList().take(4).map { "${it.title}: ${it.content}" }
                    val context = ModelContext(
                        systemPrompt = "Nexora adalah agen lokal otonom serba bisa (matematika, coding, analisis, menulis, manajemen file, dan keamanan).",
                        recalledMemories = recentMemories,
                        availableTools = toolRegistry.getAllTools().filter { it.isEnabled }.map { it.name },
                        nonThinkingMode = true
                    )
                    val result = modelManager.activeEngine.value.generate(prompt, context)
                    val thinkPrefix = if (savedThinking.isNotBlank()) "<think>\n$savedThinking\n[Dilewati oleh pengguna]\n</think>\n\n" else ""
                    val finalRaw = "$thinkPrefix${result.text}"
                    _streamingState.value = ThinkingStreamState(
                        rawText = finalRaw,
                        thinkingText = savedThinking,
                        answerText = result.text,
                        isThinking = false,
                        isFinishedThinking = true
                    )
                    dao.insertChatMessage(ChatMessageEntity(sender = "NEXORA", text = finalRaw))

                    val isMath = prompt.matches(Regex(".*[0-9]+\\s*(?:[+\\-*/^×÷xX]|kali|bagi|tambah|kurang)\\s*[0-9]+.*"))
                    if (isMath) {
                        toolRegistry.executeTool("CalculatorTool", mapOf("expression" to prompt))
                    }
                } catch (e: Exception) {
                    dao.insertChatMessage(ChatMessageEntity(sender = "SYSTEM", text = "Gagal memproses jawaban langsung: ${e.message}"))
                } finally {
                    _isGenerating.value = false
                    _streamingState.value = ThinkingStreamState()
                }
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            dao.clearChatMessages()
        }
    }

    fun launchExperiment(
        goal: String,
        input: String,
        expectedResult: String = "",
        forceFailureForTesting: Boolean = false
    ) {
        viewModelScope.launch {
            val experiment = experimentEngine.runExperiment(
                goal = goal,
                inputPayload = input,
                expectedResult = expectedResult,
                maxRetries = maxRetries.value,
                forceInitialFailureForTesting = forceFailureForTesting
            )

            val statusLabel = if (experiment.isSuccess) "Terverifikasi SUKSES" else "GAGAL"
            val summaryMsg = "Eksperimen untuk **${experiment.goal}** telah selesai.\n\n" +
                    "Hasil Terobservasi: **${experiment.finalHumanReadableAnswer}** ($statusLabel, Skor: ${experiment.evaluationScore}/100).\n" +
                    "Status Verifikasi: ${experiment.verificationStatus}."

            dao.insertChatMessage(
                ChatMessageEntity(
                    sender = "SYSTEM",
                    text = summaryMsg,
                    experimentId = experiment.experimentId
                )
            )
        }
    }

    // Memory operations
    fun addMemory(type: String, title: String, content: String, tags: String) {
        viewModelScope.launch {
            memoryEngine.addMemory(type, title, content, tags)
        }
    }

    fun updateMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryEngine.updateMemory(memory)
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryEngine.deleteMemory(id)
        }
    }

    suspend fun exportMemoriesJson(): String {
        return memoryEngine.exportMemoriesToJson()
    }

    suspend fun importMemoriesJson(json: String): Int {
        return memoryEngine.importMemoriesFromJson(json)
    }

    // Model operations
    fun switchModel(modelId: String, onResult: (Boolean, String) -> Unit) {
        activateModel(modelId, onResult)
    }

    fun setCpuThreads(threads: Int) {
        modelManager.configureThreads(threads)
    }

    fun setGpuEnabled(enabled: Boolean) {
        modelManager.configureGpu(enabled)
    }

    // Tool operations
    fun toggleTool(name: String, enabled: Boolean) {
        toolRegistry.setToolEnabled(name, enabled)
    }

    fun executeTestTool(name: String, params: Map<String, String>) {
        viewModelScope.launch {
            _toolTestResult.value = toolRegistry.executeTool(name, params)
        }
    }

    // Training operations
    fun curateTrainingDataset() {
        viewModelScope.launch {
            val added = trainingManager.curateExperiencesIntoDataset()
            dao.insertChatMessage(
                ChatMessageEntity(
                    sender = "SYSTEM",
                    text = "Berhasil mengkurasi $added pasangan data pelatihan baru dari pengalaman persisten ke dalam dataset."
                )
            )
        }
    }

    fun trainNewAdapter(name: String, desc: String) {
        viewModelScope.launch {
            val result = trainingManager.trainNewAdapter(name, desc)
            _benchmarkResult.value = result
            dao.insertChatMessage(
                ChatMessageEntity(
                    sender = "SYSTEM",
                    text = "Pelatihan Adapter LoRA: ${result.summary}"
                )
            )
        }
    }

    fun toggleAdapter(moduleId: String, enabled: Boolean) {
        viewModelScope.launch {
            trainingManager.toggleAdapter(moduleId, enabled)
        }
    }

    fun addManualTrainingData(prompt: String, completion: String, category: String = "MANUAL_INPUT") {
        viewModelScope.launch {
            trainingManager.addManualTrainingData(prompt, completion, category)
        }
    }

    fun deleteTrainingData(id: Long) {
        viewModelScope.launch {
            trainingManager.deleteTrainingData(id)
        }
    }

    fun deleteTrainingDataList(ids: List<Long>, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            trainingManager.deleteTrainingDataByIds(ids)
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun clearAllTrainingData(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            trainingManager.clearAllTrainingData()
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun importJsonlDataset(jsonlContent: String, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val count = trainingManager.importDatasetFromJsonl(jsonlContent)
            withContext(Dispatchers.Main) {
                onComplete(count)
            }
        }
    }

    fun saveChatMessagePairToDataset(userPrompt: String, assistantResponse: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                trainingManager.addManualTrainingData(
                    prompt = userPrompt.trim(),
                    completion = assistantResponse.substringAfter("</think>").trim(),
                    category = "CHAT_SAVED"
                )
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        }
    }

    fun trainIncrementalStepByStep(
        saveAsAdapter: Boolean = false,
        adapterName: String = "Incremental LoRA",
        learningRate: Float = 0.005f,
        epochs: Int = 1,
        onProgress: (Int, Int, String, Float) -> Unit,
        onFinish: (TrainingBenchmarkResult) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                var lastUpdate = 0L
                val safeProgress: (Int, Int, String, Float) -> Unit = { step, total, label, loss ->
                    val now = System.currentTimeMillis()
                    if (step == 1 || step == total || now - lastUpdate >= 80L) {
                        lastUpdate = now
                        viewModelScope.launch(Dispatchers.Main) {
                            onProgress(step, total, label, loss)
                        }
                    }
                }

                val result = if (saveAsAdapter) {
                    trainingManager.trainIncrementalStepByStep(
                        adapterName = adapterName,
                        learningRate = learningRate,
                        epochs = epochs,
                        saveAsAdapter = true,
                        onProgress = safeProgress
                    )
                } else {
                    val dataset = dao.getIncludedTrainingDataList()
                    localNeuralTrainer.trainIncrementalDirectToBaseModel(
                        dataset = dataset,
                        learningRate = learningRate,
                        epochs = epochs,
                        onProgress = safeProgress
                    )
                }

                _benchmarkResult.value = result
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "SYSTEM",
                        text = if (saveAsAdapter) "🥣 Pelatihan Inkremental (Adapter) Selesai: ${result.summary}"
                               else "🧠 Pelatihan Inkremental Model Utama Selesai: ${result.summary}"
                    )
                )
                withContext(Dispatchers.Main) {
                    onFinish(result)
                }
            } catch (e: Exception) {
                val errResult = TrainingBenchmarkResult(
                    adapterId = if (saveAsAdapter) "ERR-ADAPTER" else "BASE-MODEL-DIRECT",
                    initialLoss = 0f,
                    finalLoss = 0f,
                    benchmarkScore = 0f,
                    durationMs = 0L,
                    isAccepted = false,
                    summary = "Pelatihan terhenti aman: ${e.message ?: "Memori atau thread dibatasi sistem"}"
                )
                withContext(Dispatchers.Main) {
                    onFinish(errResult)
                }
            }
        }
    }

    fun ingestDocumentToDataset(
        fileName: String,
        fileContent: String,
        chunkSizeWords: Int = 100,
        onComplete: (Int) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val words = fileContent.split(Regex("\\s+")).filter { it.isNotBlank() }
            var createdCount = 0
            if (words.size <= chunkSizeWords) {
                if (fileContent.trim().length >= 10) {
                    val prompt = "Informasi penting dari dokumen '$fileName':"
                    trainingManager.addManualTrainingData(
                        prompt = prompt,
                        completion = fileContent.trim(),
                        category = "DOC_INGEST"
                    )
                    createdCount++
                }
            } else {
                var i = 0
                var chunkIndex = 1
                while (i < words.size) {
                    val end = (i + chunkSizeWords).coerceAtMost(words.size)
                    val chunkText = words.subList(i, end).joinToString(" ")
                    val prompt = "Informasi Bagian #$chunkIndex dari berkas '$fileName':"
                    trainingManager.addManualTrainingData(
                        prompt = prompt,
                        completion = chunkText,
                        category = "DOC_INGEST"
                    )
                    createdCount++
                    chunkIndex++
                    i += (chunkSizeWords - 20).coerceAtLeast(1)
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(createdCount)
            }
        }
    }

    fun setUseTrainedWeights(enabled: Boolean) {
        settingsStore.setUseTrainedWeights(enabled)
    }

    fun configureNeuralTrainingArchitecture(
        embedDim: Int,
        hiddenDim: Int,
        optimizer: OptimizerType = OptimizerType.ADAMW,
        ramProfileName: String = "Kustom",
        chunkSize: Int = 40,
        batchSize: Int = 1
    ) {
        localNeuralTrainer.configureModelArchitecture(
            embedDim = embedDim,
            hiddenDim = hiddenDim,
            optimizer = optimizer,
            ramProfileName = ramProfileName,
            chunkSize = chunkSize,
            batchSize = batchSize
        )
    }

    fun startLocalNeuralTraining(
        epochs: Int = 25,
        learningRate: Float = 0.05f,
        optimizer: OptimizerType = OptimizerType.ADAMW,
        batchSize: Int = 1,
        chunkSize: Int = 40,
        earlyStoppingEnabled: Boolean = true,
        earlyStoppingPatience: Int = 3
    ) {
        viewModelScope.launch {
            val dataset = dao.getIncludedTrainingDataList()
            localNeuralTrainer.trainOnDataset(
                dataset = dataset,
                epochs = epochs,
                learningRate = learningRate,
                optimizerType = optimizer,
                batchSize = batchSize,
                chunkSize = chunkSize,
                earlyStoppingEnabled = earlyStoppingEnabled,
                earlyStoppingPatience = earlyStoppingPatience
            )
        }
    }

    fun testTrainedModelInference(prompt: String): String {
        return localNeuralTrainer.runTrainedInference(prompt)
    }

    fun exportLocalTrainedWeights(): String {
        return localNeuralTrainer.exportWeightsJson()
    }

    fun importLocalTrainedWeights(jsonString: String): Boolean {
        return localNeuralTrainer.importWeightsJson(jsonString)
    }

    fun getModelStorageInfo(modelId: String): com.example.nexora.engine.model.ModelStorageInfo {
        return modelIngestionManager.getModelStorageInfo(modelId)
    }
}
