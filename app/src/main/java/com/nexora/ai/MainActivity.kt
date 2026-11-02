package com.nexora.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val MAX_VISION_IMAGE_BYTES = 20 * 1024 * 1024

internal data class LocalModel(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val projectorPath: String? = null,
    val projectorName: String? = null
)
internal data class ChatMessage(val role: String, val content: String)
internal enum class ChatMode(val label: String) { CHAT("Chat"), CODING("Coding") }
internal enum class AppPage(val label: String) { CHAT("Chat"), MODELS("Models"), SKILLS("Skills"), HARDWARE("Hardware") }

class MainActivity : ComponentActivity() {
    private val viewModel: NexoraViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this)[NexoraViewModel::class.java]
    }
    private val models get() = viewModel.uiState.value.models
    private val messages get() = viewModel.uiState.value.messages

    private var activeModelId: String?
        get() = viewModel.uiState.value.activeModelId
        set(value) = viewModel.updateState { copy(activeModelId = value) }
    private var page: AppPage
        get() = viewModel.uiState.value.page
        set(value) = viewModel.updateState { copy(page = value) }
    private var chatMode: ChatMode
        get() = viewModel.uiState.value.chatMode
        set(value) = viewModel.updateState { copy(chatMode = value) }
    private var engineReady: Boolean
        get() = viewModel.uiState.value.engineReady
        set(value) = viewModel.updateState { copy(engineReady = value) }
    private var isBusy: Boolean
        get() = viewModel.uiState.value.isBusy
        set(value) = viewModel.updateState { copy(isBusy = value) }
    private var canStopGeneration: Boolean
        get() = viewModel.uiState.value.canStopGeneration
        set(value) = viewModel.updateState { copy(canStopGeneration = value) }
    private var busyLabel: String
        get() = viewModel.uiState.value.busyLabel
        set(value) = viewModel.updateState { copy(busyLabel = value) }
    private var busyProgress: Float
        get() = viewModel.uiState.value.busyProgress
        set(value) = viewModel.updateState { copy(busyProgress = value) }
    private var progressDetail: String
        get() = viewModel.uiState.value.progressDetail
        set(value) = viewModel.updateState { copy(progressDetail = value) }
    private var benchmarkResult: String
        get() = viewModel.uiState.value.benchmarkResult
        set(value) = viewModel.updateState { copy(benchmarkResult = value) }
    private var maxOutputTokens: Int
        get() = viewModel.uiState.value.maxOutputTokens
        set(value) = viewModel.updateState { copy(maxOutputTokens = value) }
    private var generationPreview: String
        get() = viewModel.uiState.value.generationPreview
        set(value) = viewModel.updateState { copy(generationPreview = value) }
    private var statusText: String
        get() = viewModel.uiState.value.statusText
        set(value) = viewModel.updateState { copy(statusText = value) }
    private var systemInfo: String
        get() = viewModel.uiState.value.systemInfo
        set(value) = viewModel.updateState { copy(systemInfo = value) }
    private var hardwareInfo: String
        get() = viewModel.uiState.value.hardwareInfo
        set(value) = viewModel.updateState { copy(hardwareInfo = value) }
    private var errorText: String?
        get() = viewModel.uiState.value.errorText
        set(value) = viewModel.updateState { copy(errorText = value) }
    private var visionSupported: Boolean
        get() = viewModel.uiState.value.visionSupported
        set(value) = viewModel.updateState { copy(visionSupported = value) }
    private var visionImageName: String?
        get() = viewModel.uiState.value.visionImageName
        set(value) = viewModel.updateState { copy(visionImageName = value) }
    private var visionImageBytes: ByteArray?
        get() = viewModel.uiState.value.visionImageBytes
        set(value) = viewModel.updateState { copy(visionImageBytes = value) }
    private var visionResult: String
        get() = viewModel.uiState.value.visionResult
        set(value) = viewModel.updateState { copy(visionResult = value) }

    private var pendingProjectorModelId: String? = null

    private val importModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importModel(uri)
        }

    private val importProjectorLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importProjector(uri)
        }

    private val importVisionImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importVisionImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadModelIndex()
        hardwareInfo = readHardwareInfo()
        maxOutputTokens = getSharedPreferences("nexora_settings", MODE_PRIVATE)
            .getInt("max_output_tokens", 4096).coerceIn(1, 100000)
        viewModel.updateState {
            copy(networkToolsEnabled = getSharedPreferences("nexora_skills", MODE_PRIVATE)
                .getBoolean("network_tools_enabled", false))
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            NexoraTheme {
                NexoraScreen(
                    models = uiState.models,
                    activeModelId = uiState.activeModelId,
                    currentPage = uiState.page,
                    chatMode = uiState.chatMode,
                    messages = uiState.messages,
                    engineReady = uiState.engineReady,
                    isBusy = uiState.isBusy,
                    canStopGeneration = uiState.canStopGeneration,
                    onStopGeneration = ::stopGeneration,
                    busyLabel = uiState.busyLabel,
                    busyProgress = uiState.busyProgress,
                    progressDetail = uiState.progressDetail,
                    generationPreview = uiState.generationPreview,
                    maxOutputTokens = uiState.maxOutputTokens,
                    onMaxOutputTokensChange = ::updateMaxOutputTokens,
                    statusText = uiState.statusText,
                    errorText = uiState.errorText,
                    systemInfo = uiState.systemInfo,
                    hardwareInfo = uiState.hardwareInfo,
                    benchmarkResult = uiState.benchmarkResult,
                    networkToolsEnabled = uiState.networkToolsEnabled,
                    onNetworkToolsChanged = ::setNetworkToolsEnabled,
                    onRunBenchmark = ::runHardwareBenchmark,
                    onPageSelected = { page = it },
                    onModeSelected = ::changeMode,
                    onImportModel = ::launchModelPicker,
                    onLoadModel = ::loadModel,
                    onUnloadModel = ::unloadModel,
                    onRemoveModel = ::removeModel,
                    onImportProjector = ::launchProjectorPicker,
                    visionReady = uiState.visionSupported,
                    visionImageName = uiState.visionImageName,
                    visionResult = uiState.visionResult,
                    isVisionImageSelected = uiState.visionImageBytes != null,
                    onPickVisionImage = ::launchVisionImagePicker,
                    onAnalyzeVision = ::analyzeVisionImage,
                    onSendMessage = ::sendMessage,
                    onNewChat = ::newChat,
                    onDismissError = { errorText = null }
                )
            }
        }

        lifecycleScope.launch {
            try {
                systemInfo = withContext(Dispatchers.IO) {
                    NativeLlama.initialize(applicationInfo.nativeLibraryDir)
                }.ifBlank { "llama.cpp aktif." }
                engineReady = true

                val nativeModelLoaded = withContext(Dispatchers.IO) { NativeLlama.isModelLoaded() }
                if (nativeModelLoaded) {
                    val nativePath = withContext(Dispatchers.IO) { NativeLlama.activePath() }
                    val restoredModel = models.firstOrNull {
                        File(it.path).absolutePath == File(nativePath).absolutePath
                    }
                    if (restoredModel != null) {
                        activeModelId = restoredModel.id
                        visionSupported = withContext(Dispatchers.IO) { NativeLlama.hasVision() }
                        if (!viewModel.uiState.value.isBusy) synchronizeNativeConversation(restoredModel.id)
                        val contextSize = withContext(Dispatchers.IO) { NativeLlama.contextSize() }
                        statusText = "Model aktif: ${restoredModel.name} · Context ${contextSize} token"
                    } else {
                        withContext(Dispatchers.IO) { NativeLlama.unload() }
                        activeModelId = null
                        statusText = "Runtime siap. Model aktif sebelumnya tidak lagi terdaftar."
                    }
                } else {
                    activeModelId = null
                    statusText = if (models.isEmpty()) {
                        "Runtime siap. Impor model GGUF untuk memulai."
                    } else {
                        "Runtime siap. Pilih model yang ingin dimuat."
                    }
                }
            } catch (error: Throwable) {
                engineReady = false
                statusText = "Runtime native gagal dimuat."
                errorText = error.message ?: error.javaClass.simpleName
            }
        }
    }

    private fun launchModelPicker() {
        if (engineReady && !isBusy) {
            importModelLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
        }
    }

    private fun importModel(uri: Uri) {
        if (isBusy) return
        lifecycleScope.launch {
            isBusy = true
            busyLabel = "Mengimpor model…"
            busyProgress = 0f
            progressDetail = "Mempersiapkan penyalinan file GGUF"
            errorText = null
            try {
                val name = displayName(uri)
                val file = copyGgufAssetWithProgress(uri, name, "model")
                val model = LocalModel(UUID.randomUUID().toString(), name, file.absolutePath, file.length())
                viewModel.addModel(model)
                saveModelIndex()
                page = AppPage.MODELS
                statusText = "Model ${model.name} berhasil diimpor. Model belum dimuat."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal mengimpor model."
            } finally {
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            else null
        } ?: "model.gguf"

    private fun queryDocumentSize(uri: Uri): Long? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else {
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && !cursor.isNull(column)) cursor.getLong(column).takeIf { it > 0L }
                    else null
                }
            }
        }.getOrNull()

    private fun copyGgufAsset(
        uri: Uri,
        requestedName: String,
        prefix: String,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit
    ): File {
        require(ModelFileValidator.isSupportedSingleFile(requestedName)) {
            "Pilih satu file GGUF. File GGUF shard/terpisah belum didukung."
        }

        val totalBytes = queryDocumentSize(uri)
        val directory = File(filesDir, "models").apply { mkdirs() }
        val safeName = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val uniqueName = "${prefix}-${System.currentTimeMillis()}-${safeName}"
        val temporary = File(directory, "${uniqueName}.partial")
        val destination = File(directory, uniqueName)

        try {
            val source = contentResolver.openInputStream(uri) ?: error("File sumber tidak dapat dibaca.")
            var copiedBytes = 0L
            source.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        onProgress(copiedBytes, totalBytes)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            val header = ByteArray(4)
            FileInputStream(temporary).use { input ->
                require(input.read(header) == 4) { "File terlalu kecil untuk menjadi GGUF." }
            }
            require(ModelFileValidator.isGgufFile(requestedName, header)) {
                "Header file bukan GGUF yang valid. Impor dibatalkan."
            }
            require(temporary.length() > 4L) { "File GGUF kosong atau rusak." }
            check(temporary.renameTo(destination)) { "Tidak dapat menyimpan file ke penyimpanan aplikasi." }
            onProgress(copiedBytes, totalBytes)
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    private suspend fun CoroutineScope.copyGgufAssetWithProgress(
        uri: Uri,
        requestedName: String,
        prefix: String
    ): File {
        val copiedBytes = AtomicLong(0L)
        val totalBytes = AtomicLong(-1L)
        val copyTask = async(Dispatchers.IO) {
            copyGgufAsset(uri, requestedName, prefix) { copied, total ->
                copiedBytes.set(copied)
                if (total != null && total > 0L) totalBytes.set(total)
            }
        }

        while (!copyTask.isCompleted) {
            val copied = copiedBytes.get()
            val total = totalBytes.get()
            busyProgress = if (total > 0L) {
                (copied.toFloat() / total.toFloat()).coerceIn(0f, 0.995f)
            } else {
                -1f
            }
            progressDetail = if (total > 0L) {
                "${formatBytes(copied)} / ${formatBytes(total)}"
            } else {
                "${formatBytes(copied)} disalin"
            }
            delay(100)
        }

        val result = copyTask.await()
        busyProgress = 1f
        progressDetail = "Penyalinan dan validasi GGUF selesai · ${formatBytes(result.length())}"
        return result
    }

    private fun loadModel(model: LocalModel) {
        if (!engineReady || isBusy) return
        val file = File(model.path)
        if (!file.isFile || !file.canRead()) {
            errorText = "File model tidak ditemukan. Hapus entri ini lalu impor ulang."
            return
        }

        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val projectorFile = model.projectorPath?.let(::File)
        if (projectorFile != null && (!projectorFile.isFile || !projectorFile.canRead())) {
            errorText = "File mmproj tidak ditemukan. Impor ulang projector vision untuk model ini."
            return
        }
        val projectorBytes = projectorFile?.length() ?: 0L
        val estimatedNeed = ((file.length() + projectorBytes) * 1.20).toLong() +
            512L * 1024L * 1024L
        if (memory.availMem < estimatedNeed) {
            errorText = "RAM tersedia belum cukup untuk memuat model ini dengan aman. " +
                "Estimasi kebutuhan: ${formatBytes(estimatedNeed)}; tersedia: ${formatBytes(memory.availMem)}."
            return
        }

        lifecycleScope.launch {
            isBusy = true
            busyLabel = "Memuat model ke RAM…"
            busyProgress = 0f
            progressDetail = "Membaca bobot model dengan llama.cpp"
            errorText = null
            try {
                val projectorPath = model.projectorPath
                if (projectorPath != null && !File(projectorPath).isFile) {
                    error("File mmproj tidak ditemukan. Impor ulang projector vision untuk model ini.")
                }
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
                val memoryHeadroom = (memory.availMem - estimatedNeed).coerceAtLeast(0L)
                val requestedContext = when {
                    memoryHeadroom >= 2L * 1024L * 1024L * 1024L -> 32768
                    memoryHeadroom >= 1024L * 1024L * 1024L -> 16384
                    memoryHeadroom >= 512L * 1024L * 1024L -> 8192
                    else -> 4096
                }
                progressDetail = "Menyiapkan context hingga $requestedContext token berdasarkan RAM tersedia"
                val loadTask = async(Dispatchers.IO) {
                    NativeLlama.loadModel(file.absolutePath, projectorPath, requestedContext, threads)
                }
                while (!loadTask.isCompleted) {
                    val nativeProgress = NativeLlama.loadProgress()
                    if (nativeProgress >= 0f) {
                        busyProgress = nativeProgress.coerceIn(0f, 0.995f)
                        progressDetail = "${(busyProgress * 100f).toInt()}% · Memuat tensor GGUF"
                    }
                    delay(100)
                }

                val result = loadTask.await()
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Gagal memuat model (kode $result). $detail")
                }
                busyProgress = 1f
                val activeContextSize = withContext(Dispatchers.IO) { NativeLlama.contextSize() }
                progressDetail = "Model dan sampler siap · Context aktual ${activeContextSize.coerceAtLeast(0)} token"
                activeModelId = model.id
                benchmarkResult = ""
                visionSupported = withContext(Dispatchers.IO) { NativeLlama.hasVision() }
                val templateFallback = withContext(Dispatchers.IO) {
                    NativeLlama.chatTemplateWarning().isNotBlank()
                }
                visionImageBytes = null
                visionImageName = null
                synchronizeNativeConversation(model.id)
                visionResult = ""
                statusText = buildString {
                    append("Model aktif: ${model.name} · Context ${activeContextSize.coerceAtLeast(0)} token")
                    if (visionSupported) append(" · Vision siap")
                    if (templateFallback) append(" · ").append(getString(R.string.chat_template_fallback_warning))
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.IO) { NativeLlama.unload() }
                activeModelId = null
                visionSupported = false
                errorText = error.message ?: "Gagal memuat model."
            } finally {
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
    }

    private fun unloadModel() {
        if (isBusy || activeModelId == null) return
        lifecycleScope.launch {
            isBusy = true
            busyLabel = "Melepas model…"
            try {
                withContext(Dispatchers.IO) { NativeLlama.unload() }
                activeModelId = null
                benchmarkResult = ""
                visionSupported = false
                visionImageBytes = null
                visionImageName = null
                visionResult = ""
                statusText = "Model dilepas dari RAM. Riwayat lokal tetap tersimpan."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal melepas model."
            } finally {
                isBusy = false
                busyLabel = ""
            }
        }
    }

    private fun removeModel(model: LocalModel) {
        if (isBusy) return
        lifecycleScope.launch {
            isBusy = true
            try {
                if (activeModelId == model.id) {
                    withContext(Dispatchers.IO) { NativeLlama.unload() }
                    activeModelId = null
                    visionSupported = false
                }
                if (viewModel.hasConversationForModel(model.id)) {
                    viewModel.clearConversation(null, chatMode)
                }
                withContext(Dispatchers.IO) {
                    File(model.path).delete()
                    model.projectorPath?.let { File(it).delete() }
                }
                viewModel.removeModel(model.id)
                saveModelIndex()
                statusText = "Model dihapus dari penyimpanan aplikasi."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal menghapus model."
            } finally {
                isBusy = false
            }
        }
    }

    private fun launchProjectorPicker(model: LocalModel) {
        if (!engineReady || isBusy) return
        pendingProjectorModelId = model.id
        importProjectorLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
    }

    private fun importProjector(uri: Uri) {
        val targetId = pendingProjectorModelId
        pendingProjectorModelId = null
        if (targetId == null || isBusy) return
        lifecycleScope.launch {
            isBusy = true
            busyLabel = "Memasang projector vision…"
            busyProgress = 0f
            progressDetail = "Menyalin file projector"
            errorText = null
            var reload: LocalModel? = null
            try {
                val targetIndex = models.indexOfFirst { it.id == targetId }
                require(targetIndex >= 0) { "Model tujuan tidak ditemukan." }
                val name = displayName(uri)
                val projector = copyGgufAssetWithProgress(uri, name, "mmproj")
                val previous = models[targetIndex]
                val updated = previous.copy(projectorPath = projector.absolutePath, projectorName = name)
                viewModel.updateModel(updated)
                saveModelIndex()
                if (activeModelId == targetId) {
                    withContext(Dispatchers.IO) { NativeLlama.unload() }
                    activeModelId = null
                    visionSupported = false
                    reload = updated
                }
                statusText = "Projector tersimpan. Model harus dimuat ulang untuk mengaktifkan vision."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal memasang projector vision."
            } finally {
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
            reload?.let(::loadModel)
        }
    }

    private fun launchVisionImagePicker() {
        if (!isBusy && activeModelId != null && visionSupported) {
            importVisionImageLauncher.launch(arrayOf("image/jpeg", "image/png", "image/bmp", "image/gif"))
        }
    }

    private fun importVisionImage(uri: Uri) {
        if (isBusy) return
        lifecycleScope.launch {
            isBusy = true
            busyLabel = "Membaca gambar…"
            errorText = null
            try {
                val mime = contentResolver.getType(uri)?.lowercase()
                require(mime in setOf("image/jpeg", "image/png", "image/bmp", "image/gif")) {
                    "Gunakan gambar JPG, PNG, BMP, atau GIF."
                }
                val bytes = withContext(Dispatchers.IO) {
                    val output = ByteArrayOutputStream()
                    val input = contentResolver.openInputStream(uri) ?: error("Gambar tidak dapat dibaca.")
                    input.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size().toLong() + count <= MAX_VISION_IMAGE_BYTES) {
                                "Ukuran gambar melebihi batas 20 MB."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toByteArray()
                }
                require(bytes.isNotEmpty()) { "File gambar kosong." }
                visionImageName = displayName(uri)
                visionImageBytes = bytes
                visionResult = ""
                statusText = "Gambar siap dianalisis secara lokal."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal membaca gambar."
            } finally {
                isBusy = false
                busyLabel = ""
            }
        }
    }

    private fun stopGeneration() {
        if (!canStopGeneration) return
        NativeLlama.cancelGeneration()
        canStopGeneration = false
        busyLabel = getString(R.string.generation_stopping)
        statusText = getString(R.string.generation_stop_requested)
    }

    private fun analyzeVisionImage(question: String) {
        val trimmed = question.trim().ifBlank { "Jelaskan isi gambar ini secara detail." }
        val image = visionImageBytes
        val modelId = activeModelId ?: return
        if (isBusy) return
        if (!visionSupported) {
            errorText = "Model aktif tidak menyediakan vision. Impor mmproj yang cocok lalu muat ulang model."
            return
        }
        if (image == null) {
            errorText = "Pilih gambar terlebih dahulu."
            return
        }

        val imageLabel = visionImageName ?: "gambar"
        viewModel.appendMessage(
            modelId,
            chatMode,
            ChatMessage("user", trimmed + "\n\n[Gambar terlampir: " + imageLabel + "]")
        )
        isBusy = true
        busyLabel = "Menyiapkan analisis gambar…"
        busyProgress = 0f
        progressDetail = "Memproses input multimodal di perangkat"
        errorText = null
        visionResult = ""
        canStopGeneration = false

        lifecycleScope.launch {
            var nativeWorkSettled = false
            try {
                val systemPrompt =
                    "You are Nexora, a precise multimodal on-device assistant. " +
                    "Answer in the user's language. Describe only visual evidence present in the image. " +
                    "If something is unclear, say so. Do not claim to have generated or edited an image."
                val startedAt = SystemClock.elapsedRealtime()
                val task = async(Dispatchers.IO) {
                    NativeLlama.startVisionPrompt(systemPrompt, trimmed, image, 512)
                }
                while (!task.isCompleted) {
                    val stats = NativeLlama.inferenceStats()
                    if ((stats.getOrNull(0) ?: 0) != 0) canStopGeneration = true
                    updateInferenceProgress(stats, startedAt, "Memproses input vision")
                    delay(80)
                }
                val result = task.await()
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Vision gagal dimulai (kode $result). $detail")
                }

                canStopGeneration = true
                val output = StringBuilder()
                while (true) {
                    val token = withContext(Dispatchers.IO) { NativeLlama.nextToken() } ?: break
                    output.append(token)
                    visionResult = output.toString()
                    updateInferenceProgress(NativeLlama.inferenceStats(), startedAt, "Menghasilkan jawaban vision")
                }
                nativeWorkSettled = true

                val cancelled = withContext(Dispatchers.IO) { NativeLlama.generationWasCancelled() }
                val generationError = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                val response = when {
                    output.isNotEmpty() -> output.toString()
                    cancelled -> getString(R.string.generation_stopped_no_output)
                    generationError.isNotBlank() -> "Analisis gambar gagal: " + generationError
                    else -> "Model tidak menghasilkan jawaban untuk gambar ini."
                }
                viewModel.appendMessage(modelId, chatMode, ChatMessage("assistant", response))
                if (generationError.isNotBlank() && !cancelled) errorText = generationError
                busyProgress = if (cancelled) 0f else 1f
                busyLabel = if (cancelled) getString(R.string.generation_stopped) else "Analisis gambar selesai"
                progressDetail = "Token output aktual: " + (NativeLlama.inferenceStats().getOrNull(3) ?: 0)
                statusText = if (cancelled) getString(R.string.generation_stopped)
                    else "Analisis gambar selesai. Pertanyaan dan jawaban tersimpan di chat."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val wasCancelled = withContext(Dispatchers.IO) { NativeLlama.generationWasCancelled() }
                val response = if (wasCancelled) getString(R.string.generation_stopped_no_output)
                    else "Analisis gambar gagal: " + (error.message ?: error.javaClass.simpleName)
                if (messages.lastOrNull()?.role == "user") {
                    viewModel.appendMessage(modelId, chatMode, ChatMessage("assistant", response))
                }
                if (wasCancelled) statusText = getString(R.string.generation_stopped)
                else errorText = error.message ?: "Analisis gambar gagal."
            } finally {
                if (!nativeWorkSettled) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        NativeLlama.cancelGeneration()
                        NativeLlama.nextToken()
                    }
                }
                if (messages.lastOrNull()?.role == "assistant") {
                    runCatching { synchronizeNativeConversation(modelId) }
                        .onFailure { failure ->
                            errorText = "Riwayat multimodal tidak tersinkron: " +
                                (failure.message ?: failure.javaClass.simpleName)
                        }
                }
                canStopGeneration = false
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
    }

    private fun runHardwareBenchmark() {
        if (isBusy || !engineReady) return
        val activeModel = models.firstOrNull { it.id == activeModelId }
        if (activeModel == null) {
            errorText = getString(R.string.benchmark_model_required)
            return
        }

        val prompt = getString(R.string.benchmark_prompt)
        benchmarkResult = ""
        errorText = null
        isBusy = true
        busyLabel = getString(R.string.benchmark_running)
        busyProgress = 0f
        progressDetail = getString(R.string.benchmark_prompt_description)

        lifecycleScope.launch {
            try {
                val task = async(Dispatchers.IO) {
                    NativeLlama.runBenchmark(prompt, 128)
                }
                while (!task.isCompleted) {
                    updateBenchmarkProgress(NativeLlama.inferenceStats())
                    delay(100)
                }

                val result = task.await()
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Benchmark gagal (kode $result). $detail")
                }

                val metrics = withContext(Dispatchers.IO) { NativeLlama.benchmarkStats() }
                if (metrics.size < 11) error("Data benchmark native tidak lengkap.")

                val loadNs = metrics[0]
                val prefillStart = metrics[1]
                val prefillEnd = metrics[2]
                val promptTokens = metrics[3].coerceAtLeast(0L)
                val decodeStart = metrics[4]
                val decodeEnd = metrics[5]
                val generatedTokens = metrics[6].coerceAtLeast(0L)
                val peakRssBytes = metrics[7]
                val stopFlags = metrics[8]
                val contextSize = metrics[9].coerceAtLeast(0L)
                val threadCount = metrics[10].coerceAtLeast(0L)

                val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase(java.util.Locale.ROOT) }
                    .joinToString(" ")
                    .ifBlank { getString(R.string.not_available) }
                val socName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.SOC_MODEL
                } else {
                    ""
                }.takeIf { it.isNotBlank() }
                    ?: Build.HARDWARE.takeIf { it.isNotBlank() }
                    ?: getString(R.string.not_available)
                val memoryInfo = ActivityManager.MemoryInfo()
                (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
                val totalRam = if (memoryInfo.totalMem > 0L) {
                    formatBytes(memoryInfo.totalMem)
                } else {
                    getString(R.string.not_available)
                }
                val quantization = withContext(Dispatchers.IO) {
                    NativeLlama.benchmarkModelQuantization()
                }.ifBlank { getString(R.string.not_available) }

                benchmarkResult = getString(
                    R.string.benchmark_result,
                    deviceName,
                    socName,
                    totalRam,
                    activeModel.name,
                    quantization,
                    contextSize.toString(),
                    threadCount.toString(),
                    benchmarkRate(promptTokens, prefillStart, prefillEnd),
                    benchmarkDuration(prefillEnd - prefillStart),
                    promptTokens.toString(),
                    benchmarkRate(generatedTokens, decodeStart, decodeEnd),
                    generatedTokens.toString(),
                    benchmarkDuration(decodeEnd - decodeStart),
                    benchmarkStopReason(stopFlags),
                    benchmarkDuration(loadNs),
                    if (peakRssBytes > 0L) formatBytes(peakRssBytes) else getString(R.string.not_available)
                )
                busyProgress = 1f
                busyLabel = getString(R.string.benchmark_finished)
                progressDetail = getString(R.string.benchmark_actual_tokens, generatedTokens.toString())
                statusText = getString(R.string.benchmark_finished)
            } catch (error: Throwable) {
                errorText = error.message ?: getString(R.string.benchmark_failed)
                benchmarkResult = ""
            } finally {
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
    }

    private fun benchmarkStopReason(flags: Long): String {
        val reachedEos = (flags and 1L) != 0L
        val reachedOutputLimit = (flags and 2L) != 0L
        val reachedContextLimit = (flags and 4L) != 0L

        return when {
            reachedEos -> getString(R.string.benchmark_stop_eos)
            reachedOutputLimit && reachedContextLimit ->
                getString(R.string.benchmark_stop_output_and_context)
            reachedContextLimit -> getString(R.string.benchmark_stop_context)
            reachedOutputLimit -> getString(R.string.benchmark_stop_output_limit)
            else -> getString(R.string.not_available)
        }
    }

    private fun updateBenchmarkProgress(stats: IntArray) {
        val phase = stats.getOrNull(0) ?: 0
        val promptProcessed = stats.getOrNull(1) ?: 0
        val promptTotal = stats.getOrNull(2) ?: 0
        val generated = stats.getOrNull(3) ?: 0
        val outputTarget = stats.getOrNull(4) ?: 128

        when (phase) {
            1 -> {
                val ratio = if (promptTotal > 0) promptProcessed.toFloat() / promptTotal else 0f
                busyProgress = (ratio.coerceIn(0f, 1f) * 0.45f).coerceIn(0f, 0.45f)
                progressDetail = getString(
                    R.string.benchmark_prefill_progress,
                    promptProcessed,
                    promptTotal
                )
            }
            2 -> {
                val ratio = if (outputTarget > 0) generated.toFloat() / outputTarget else 0f
                busyProgress = (0.45f + ratio.coerceIn(0f, 1f) * 0.55f).coerceIn(0.45f, 0.995f)
                progressDetail = getString(
                    R.string.benchmark_decode_progress,
                    generated,
                    outputTarget
                )
            }
        }
    }

    private fun benchmarkRate(tokens: Long, startedNs: Long, endedNs: Long): String {
        val elapsedNs = endedNs - startedNs
        if (tokens <= 0L || elapsedNs <= 0L) return getString(R.string.not_available)
        val rate = tokens.toDouble() * 1_000_000_000.0 / elapsedNs.toDouble()
        return String.format(java.util.Locale.US, "%.2f tok/s", rate)
    }

    private fun benchmarkDuration(durationNs: Long): String {
        if (durationNs <= 0L) return getString(R.string.not_available)
        return String.format(java.util.Locale.US, "%.2f s", durationNs / 1_000_000_000.0)
    }

    private fun updateInferenceProgress(stats: IntArray, startedAt: Long, inputLabel: String) {
        val phase = stats.getOrNull(0) ?: 0
        val inputProcessed = stats.getOrNull(1) ?: 0
        val inputTotal = stats.getOrNull(2) ?: 0
        val generated = stats.getOrNull(3) ?: 0
        val target = stats.getOrNull(4) ?: 0

        when (phase) {
            1 -> {
                val ratio = if (inputTotal > 0) inputProcessed.toFloat() / inputTotal else 0f
                busyProgress = (ratio.coerceIn(0f, 1f) * 0.2f).coerceIn(0f, 0.2f)
                busyLabel = "$inputLabel: $inputProcessed / $inputTotal"
                val reused = stats.getOrNull(5)?.coerceAtLeast(0) ?: 0
                progressDetail = getString(R.string.prompt_cache_reuse_progress, reused)
            }
            2 -> {
                val ratio = if (target > 0) generated.toFloat() / target else 0f
                busyProgress = (0.2f + ratio.coerceIn(0f, 1f) * 0.8f).coerceIn(0.2f, 0.995f)
                val elapsed = ((SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)) / 1000.0
                val rate = generated / elapsed
                busyLabel = "Menghasilkan token $generated / $target"
                progressDetail = String.format(java.util.Locale.getDefault(), "%.1f token/detik", rate)
            }
        }
    }

    private fun changeMode(newMode: ChatMode) {
        if (isBusy || newMode == chatMode) return
        chatMode = newMode
        viewModel.clearConversation(activeModelId, chatMode)
        resetNativeConversation("Menyiapkan mode ${newMode.label}…")
    }

    private fun newChat() {
        if (isBusy) return
        viewModel.clearConversation(activeModelId, chatMode)
        visionResult = ""
        resetNativeConversation("Membuat percakapan baru…")
    }

    private fun resetNativeConversation(label: String) {
        if (activeModelId == null) {
            statusText = "Percakapan dibersihkan."
            return
        }
        isBusy = true
        busyLabel = label
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { NativeLlama.resetChat() }
                statusText = "Konteks percakapan telah direset."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal mereset percakapan."
            } finally {
                isBusy = false
                busyLabel = ""
            }
        }
    }

    private fun systemPromptForMode(mode: ChatMode): String {
        val base = when (mode) {
            ChatMode.CHAT ->
                "You are Nexora, a precise and helpful on-device AI assistant. " +
                    "Answer in the user's language. Never claim to use tools or data you do not have."
            ChatMode.CODING ->
                "You are Nexora Code, a senior software engineer. " +
                    "Produce complete, correct, maintainable, modern code. Preserve all required imports and supporting functions. " +
                    "Do not stop mid-file; if the model context prevents completion, state exactly what remains. " +
                    "Explain assumptions and never fabricate test results."
        }
        return base + "\n\n" + AgentToolRuntime.promptInstructions()
    }

    private suspend fun synchronizeNativeConversation(modelId: String) {
        val hasMatchingHistory = viewModel.hasConversationForModel(modelId)
        val history = if (hasMatchingHistory) messages.toList() else emptyList()
        val result = withContext(Dispatchers.IO) {
            NativeLlama.restoreHistory(
                systemPromptForMode(chatMode),
                history.map { it.role }.toTypedArray(),
                history.map { it.content }.toTypedArray()
            )
        }
        if (result != 0) {
            val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
            error("Gagal menyinkronkan riwayat native (kode $result). $detail")
        }
        if (!hasMatchingHistory) viewModel.clearConversation(modelId, chatMode)
    }

    private fun setNetworkToolsEnabled(enabled: Boolean) {
        getSharedPreferences("nexora_skills", MODE_PRIVATE)
            .edit().putBoolean("network_tools_enabled", enabled).apply()
        viewModel.updateState { copy(networkToolsEnabled = enabled) }
        statusText = if (enabled) {
            "Web Search aktif. Nexora hanya mengakses web saat model meminta tool."
        } else {
            "Web Search dinonaktifkan. Tool lokal tetap tersedia."
        }
    }

    private fun updateMaxOutputTokens(value: Int) {
        val safeValue = value.coerceIn(1, 100000)
        maxOutputTokens = safeValue
        getSharedPreferences("nexora_settings", MODE_PRIVATE)
            .edit().putInt("max_output_tokens", safeValue).apply()
    }

    private fun sendMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || isBusy) return
        if (activeModelId == null) {
            errorText = "Muat model dari halaman Models sebelum memulai percakapan."
            page = AppPage.MODELS
            return
        }
        val modelId = activeModelId ?: return
        viewModel.appendMessage(modelId, chatMode, ChatMessage("user", trimmed))
        generationPreview = ""
        isBusy = true
        busyLabel = "Menyiapkan prompt…"
        busyProgress = 0f
        progressDetail = "Inference lokal · Tools hanya dijalankan saat diminta model"
        errorText = null
        canStopGeneration = false

        lifecycleScope.launch {
            var nativeWorkSettled = false
            try {
                val systemPrompt = systemPromptForMode(chatMode)
                val startedAt = SystemClock.elapsedRealtime()
                nativeWorkSettled = false
                var outputText = generateNativeText(systemPrompt, trimmed, startedAt, "Memproses prompt")
                nativeWorkSettled = true

                var toolCalls = 0
                while (toolCalls < 3) {
                    if (withContext(Dispatchers.IO) { NativeLlama.generationWasCancelled() }) break
                    val call = AgentToolRuntime.extractToolCall(outputText) ?: break
                    toolCalls++
                    canStopGeneration = false
                    busyProgress = -1f
                    busyLabel = "Menjalankan skill: " + call.name
                    progressDetail = "Validasi izin dan eksekusi tool"
                    statusText = "Nexora sedang menggunakan skill " + call.name + "."
                    val toolResult = AgentToolRuntime.execute(
                        call,
                        viewModel.uiState.value.networkToolsEnabled
                    )
                    val toolPrompt = buildString {
                        append("Tool result from ").append(call.name).append(":\n")
                        append(toolResult)
                        append("\n\nUse this result to answer the original user request. ")
                        append("Do not repeat the tool envelope. Call another available tool only if necessary.")
                    }
                    nativeWorkSettled = false
                    outputText = generateNativeText(systemPrompt, toolPrompt, startedAt, "Menyusun jawaban dari hasil skill")
                    nativeWorkSettled = true
                }

                if (toolCalls == 3 && AgentToolRuntime.extractToolCall(outputText) != null) {
                    nativeWorkSettled = false
                    outputText = generateNativeText(
                        systemPrompt,
                        "The tool-call budget for this turn is exhausted. Give the best final answer using the results already available. Do not request another tool.",
                        startedAt,
                        "Menyelesaikan jawaban"
                    )
                    nativeWorkSettled = true
                }

                val wasCancelled = withContext(Dispatchers.IO) { NativeLlama.generationWasCancelled() }
                val generationError = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                val stats = NativeLlama.inferenceStats()
                val producedTokens = stats.getOrNull(3) ?: 0
                val effectiveLimit = stats.getOrNull(4) ?: maxOutputTokens
                val finalContent = when {
                    outputText.isNotEmpty() -> outputText
                    wasCancelled -> getString(R.string.generation_stopped_no_output)
                    generationError.isNotBlank() -> "Generasi berhenti: $generationError"
                    else -> "Model tidak menghasilkan teks untuk prompt ini."
                }
                viewModel.appendMessage(modelId, chatMode, ChatMessage("assistant", finalContent))
                generationPreview = ""
                if (generationError.isNotBlank() && !wasCancelled) errorText = generationError
                busyProgress = if (wasCancelled) 0f else 1f
                busyLabel = if (wasCancelled) getString(R.string.generation_stopped) else "Inference selesai"
                progressDetail = "Token output aktual: $producedTokens · Tool dipakai: $toolCalls"
                statusText = when {
                    wasCancelled -> getString(R.string.generation_stopped)
                    effectiveLimit < maxOutputTokens ->
                        "Inference selesai secara lokal. Output dibatasi oleh sisa context: $effectiveLimit dari $maxOutputTokens token yang diminta."
                    toolCalls > 0 -> "Inference selesai. $toolCalls skill dijalankan dalam percakapan lokal."
                    else -> "Inference selesai secara lokal."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val wasCancelled = withContext(Dispatchers.IO) { NativeLlama.generationWasCancelled() }
                if (messages.lastOrNull()?.role == "user") {
                    viewModel.appendMessage(
                        modelId,
                        chatMode,
                        ChatMessage("assistant", if (wasCancelled) getString(R.string.generation_stopped_no_output) else "Generasi gagal. Periksa pesan kesalahan.")
                    )
                }
                if (wasCancelled) statusText = getString(R.string.generation_stopped)
                else errorText = error.message ?: "Inference gagal."
                generationPreview = ""
            } finally {
                if (!nativeWorkSettled) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        NativeLlama.cancelGeneration()
                        NativeLlama.nextToken()
                    }
                }
                canStopGeneration = false
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
    }

    private suspend fun generateNativeText(
        systemPrompt: String,
        userPrompt: String,
        startedAt: Long,
        phaseLabel: String
    ): String {
        val task = lifecycleScope.async(Dispatchers.IO) {
            NativeLlama.startPrompt(systemPrompt, userPrompt, maxOutputTokens)
        }
        while (!task.isCompleted) {
            val stats = NativeLlama.inferenceStats()
            if ((stats.getOrNull(0) ?: 0) != 0) canStopGeneration = true
            updateInferenceProgress(stats, startedAt, phaseLabel)
            delay(80)
        }
        val startResult = task.await()
        if (startResult != 0) {
            val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
            error("Prompt ditolak (kode $startResult). $detail")
        }

        canStopGeneration = true
        return withContext(Dispatchers.IO) {
            val output = StringBuilder()
            var lastUiUpdate = SystemClock.elapsedRealtime()
            var tokenCount = 0
            while (true) {
                val token = NativeLlama.nextToken() ?: break
                output.append(token)
                tokenCount++
                val now = SystemClock.elapsedRealtime()
                if (tokenCount % 16 == 0 || now - lastUiUpdate >= 150L) {
                    val preview = output.takeLast(2_000).toString()
                    val stats = NativeLlama.inferenceStats()
                    withContext(Dispatchers.Main) {
                        generationPreview = preview
                        updateInferenceProgress(stats, startedAt, "Menghasilkan jawaban")
                    }
                    lastUiUpdate = now
                }
            }
            val finalText = output.toString()
            val finalStats = NativeLlama.inferenceStats()
            withContext(Dispatchers.Main) {
                generationPreview = output.takeLast(2_000).toString()
                updateInferenceProgress(finalStats, startedAt, "Menyelesaikan jawaban")
            }
            finalText
        }
    }

    private fun loadModelIndex() {
        if (viewModel.hasLoadedModelIndex()) return
        val loadedModels = mutableListOf<LocalModel>()
        val stored = getSharedPreferences("nexora_models", MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(stored)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val projectorPath = item.optString("projectorPath").takeIf { it.isNotBlank() }
                val model = LocalModel(
                    item.getString("id"), item.getString("name"), item.getString("path"),
                    item.getLong("sizeBytes"), projectorPath,
                    item.optString("projectorName").takeIf { it.isNotBlank() }
                )
                if (File(model.path).isFile) {
                    loadedModels += if (model.projectorPath != null && !File(model.projectorPath).isFile) {
                        model.copy(projectorPath = null, projectorName = null)
                    } else model
                }
            }
        }.onFailure { errorText = "Daftar model lokal rusak dan perlu diimpor ulang." }
        viewModel.replaceModels(loadedModels)
    }

    private fun saveModelIndex() {
        val array = JSONArray()
        models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("name", model.name)
                    .put("path", model.path)
                    .put("sizeBytes", model.sizeBytes)
                    .put("projectorPath", model.projectorPath ?: "")
                    .put("projectorName", model.projectorName ?: "")
            )
        }
        getSharedPreferences("nexora_models", MODE_PRIVATE)
            .edit().putString("items", array.toString()).apply()
    }

    private fun readHardwareInfo(): String {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return buildString {
            append("Android ")
            append(Build.VERSION.RELEASE)
            append(" (API ")
            append(Build.VERSION.SDK_INT)
            append(")\nABI: ")
            append(Build.SUPPORTED_ABIS.firstOrNull() ?: "Tidak diketahui")
            append("\nCPU cores: ")
            append(Runtime.getRuntime().availableProcessors())
            append("\nRAM perangkat: ")
            append(formatBytes(memory.totalMem))
            append("\nRAM tersedia: ")
            append(formatBytes(memory.availMem))
            append("\nRuntime: llama.cpp / ")
            append(Build.SUPPORTED_ABIS.firstOrNull() ?: "ABI tidak diketahui")
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}
