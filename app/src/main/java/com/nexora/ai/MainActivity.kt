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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
internal enum class AppPage(val label: String) { CHAT("Chat"), MODELS("Models"), HARDWARE("Hardware"), VISION("Vision") }

class MainActivity : ComponentActivity() {
    private val models = mutableStateListOf<LocalModel>()
    private val messages = mutableStateListOf<ChatMessage>()

    private var activeModelId by mutableStateOf<String?>(null)
    private var page by mutableStateOf(AppPage.CHAT)
    private var chatMode by mutableStateOf(ChatMode.CHAT)
    private var engineReady by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var busyLabel by mutableStateOf("")
    private var busyProgress by mutableStateOf(-1f)
    private var progressDetail by mutableStateOf("")
    private var maxOutputTokens by mutableStateOf(4096)
    private var generationPreview by mutableStateOf("")
    private var statusText by mutableStateOf("Menyiapkan runtime lokal…")
    private var systemInfo by mutableStateOf("Backend belum diinisialisasi.")
    private var hardwareInfo by mutableStateOf("Memeriksa perangkat…")
    private var errorText by mutableStateOf<String?>(null)
    private var visionSupported by mutableStateOf(false)
    private var visionImageName by mutableStateOf<String?>(null)
    private var visionImageBytes by mutableStateOf<ByteArray?>(null)
    private var visionResult by mutableStateOf("")
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

        setContent {
            NexoraTheme {
                NexoraScreen(
                    models = models,
                    activeModelId = activeModelId,
                    currentPage = page,
                    chatMode = chatMode,
                    messages = messages,
                    engineReady = engineReady,
                    isBusy = isBusy,
                    busyLabel = busyLabel,
                    busyProgress = busyProgress,
                    progressDetail = progressDetail,
                    generationPreview = generationPreview,
                    maxOutputTokens = maxOutputTokens,
                    onMaxOutputTokensChange = ::updateMaxOutputTokens,
                    statusText = statusText,
                    errorText = errorText,
                    systemInfo = systemInfo,
                    hardwareInfo = hardwareInfo,
                    onPageSelected = { page = it },
                    onModeSelected = ::changeMode,
                    onImportModel = ::launchModelPicker,
                    onLoadModel = ::loadModel,
                    onUnloadModel = ::unloadModel,
                    onRemoveModel = ::removeModel,
                    onImportProjector = ::launchProjectorPicker,
                    visionReady = visionSupported,
                    visionImageName = visionImageName,
                    visionResult = visionResult,
                    isVisionImageSelected = visionImageBytes != null,
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
                statusText = if (models.isEmpty()) {
                    "Runtime siap. Impor model GGUF untuk memulai."
                } else {
                    "Runtime siap. Pilih model yang ingin dimuat."
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
                models.add(model)
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
                visionSupported = withContext(Dispatchers.IO) { NativeLlama.hasVision() }
                visionImageBytes = null
                visionImageName = null
                messages.clear()
                visionResult = ""
                statusText = buildString {
                    append("Model aktif: ${model.name} · Context ${activeContextSize.coerceAtLeast(0)} token")
                    if (visionSupported) append(" · Vision siap")
                }
            } catch (error: Throwable) {
                activeModelId = null
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
                visionSupported = false
                messages.clear()
                visionResult = ""
                statusText = "Model dilepas dari RAM."
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
                    messages.clear()
                }
                withContext(Dispatchers.IO) {
                    File(model.path).delete()
                    model.projectorPath?.let { File(it).delete() }
                }
                models.removeAll { it.id == model.id }
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
                models[targetIndex] = updated
                saveModelIndex()
                if (activeModelId == targetId) {
                    withContext(Dispatchers.IO) { NativeLlama.unload() }
                    activeModelId = null
                    visionSupported = false
                    messages.clear()
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

    private fun analyzeVisionImage(question: String) {
        val trimmed = question.trim()
        val image = visionImageBytes
        if (isBusy || activeModelId == null) return
        if (!visionSupported) {
            errorText = "Model aktif tidak menyediakan vision. Impor mmproj yang cocok lalu muat ulang model."
            return
        }
        if (image == null) {
            errorText = "Pilih gambar terlebih dahulu."
            return
        }
        if (trimmed.isEmpty()) {
            errorText = "Tulis pertanyaan tentang gambar."
            return
        }

        isBusy = true
        busyLabel = "Menyiapkan analisis gambar…"
        busyProgress = 0f
        progressDetail = "Memproses masukan multimodal di perangkat"
        errorText = null
        visionResult = ""
        lifecycleScope.launch {
            try {
                val systemPrompt =
                    "You are Nexora Vision, an accurate on-device visual assistant. " +
                    "Describe only visual evidence present in the image. If something is unclear, say so. " +
                    "Answer in the user's language."

                val startedAt = SystemClock.elapsedRealtime()
                val visionTask = async(Dispatchers.IO) {
                    NativeLlama.startVisionPrompt(systemPrompt, trimmed, image, 512)
                }
                while (!visionTask.isCompleted) {
                    updateInferenceProgress(NativeLlama.inferenceStats(), startedAt, "Memproses input vision")
                    delay(80)
                }
                val result = visionTask.await()
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Vision gagal dimulai (kode $result). $detail")
                }

                val output = StringBuilder()
                while (true) {
                    val token = withContext(Dispatchers.IO) { NativeLlama.nextToken() } ?: break
                    output.append(token)
                    visionResult = output.toString()
                    updateInferenceProgress(NativeLlama.inferenceStats(), startedAt, "Menghasilkan jawaban vision")
                }

                val generationError = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                if (generationError.isNotBlank()) errorText = generationError
                if (output.isEmpty()) {
                    visionResult = if (generationError.isBlank()) {
                        "Model tidak menghasilkan jawaban untuk gambar ini."
                    } else {
                        "Analisis gagal: $generationError"
                    }
                }
                busyProgress = 1f
                busyLabel = "Analisis gambar selesai"
                progressDetail = "Token output aktual: ${NativeLlama.inferenceStats().getOrNull(3) ?: 0}"
                statusText = "Analisis gambar selesai secara lokal."
            } catch (error: Throwable) {
                errorText = error.message ?: "Analisis vision gagal."
                if (visionResult.isBlank()) visionResult = "Analisis gagal. Periksa pesan kesalahan."
            } finally {
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
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
                progressDetail = "Token input yang sudah diproses backend native"
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
        messages.clear()
        resetNativeConversation("Menyiapkan mode ${newMode.label}…")
    }

    private fun newChat() {
        if (isBusy) return
        messages.clear()
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

        messages.add(ChatMessage("user", trimmed))
        generationPreview = ""
        isBusy = true
        busyLabel = "Menyiapkan prompt…"
        busyProgress = 0f
        progressDetail = "Token input dan output dihitung dari backend native"
        errorText = null

        lifecycleScope.launch {
            try {
                val systemPrompt = when (chatMode) {
                    ChatMode.CHAT ->
                        "You are Nexora, a precise and helpful on-device AI assistant. " +
                            "Answer in the user's language. Never claim to use tools or data you do not have."
                    ChatMode.CODING ->
                        "You are Nexora Code, a senior software engineer. " +
                            "Produce complete, correct, maintainable, modern code. Preserve all required imports and supporting functions. " +
                            "Do not stop mid-file; if the model context prevents completion, state exactly what remains. " +
                            "Explain assumptions and never fabricate test results."
                }

                val startedAt = SystemClock.elapsedRealtime()
                val promptTask = async(Dispatchers.IO) {
                    NativeLlama.startPrompt(systemPrompt, trimmed, maxOutputTokens)
                }
                while (!promptTask.isCompleted) {
                    updateInferenceProgress(NativeLlama.inferenceStats(), startedAt, "Memproses prompt")
                    delay(80)
                }

                val result = promptTask.await()
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Prompt ditolak (kode $result). $detail")
                }

                val outputText = withContext(Dispatchers.IO) {
                    val output = StringBuilder()
                    var lastUiUpdate = SystemClock.elapsedRealtime()
                    var processedTokens = 0

                    while (true) {
                        val token = NativeLlama.nextToken() ?: break
                        output.append(token)
                        processedTokens++

                        val now = SystemClock.elapsedRealtime()
                        if (processedTokens % 16 == 0 || now - lastUiUpdate >= 150L) {
                            val preview = output.takeLast(2000).toString()
                            val stats = NativeLlama.inferenceStats()
                            withContext(Dispatchers.Main) {
                                generationPreview = preview
                                updateInferenceProgress(stats, startedAt, "Menghasilkan jawaban")
                            }
                            lastUiUpdate = now
                        }
                    }

                    val complete = output.toString()
                    val finalPreview = output.takeLast(2000).toString()
                    val finalTokenStats = NativeLlama.inferenceStats()
                    withContext(Dispatchers.Main) {
                        generationPreview = finalPreview
                        updateInferenceProgress(finalTokenStats, startedAt, "Menyelesaikan jawaban")
                    }
                    complete
                }

                val generationError = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                val finalStats = NativeLlama.inferenceStats()
                val producedTokens = finalStats.getOrNull(3) ?: 0
                val effectiveLimit = finalStats.getOrNull(4) ?: maxOutputTokens
                val finalContent = when {
                    outputText.isNotEmpty() -> outputText
                    generationError.isNotBlank() -> "Generasi berhenti: $generationError"
                    else -> "Model tidak menghasilkan teks untuk prompt ini."
                }
                messages.add(ChatMessage("assistant", finalContent))
                generationPreview = ""
                if (generationError.isNotBlank()) errorText = generationError
                busyProgress = 1f
                busyLabel = "Inference selesai"
                progressDetail = "Token output aktual: $producedTokens"
                statusText = if (effectiveLimit < maxOutputTokens) {
                    "Inference selesai secara lokal. Output dibatasi oleh sisa context: $effectiveLimit dari $maxOutputTokens token yang diminta."
                } else {
                    "Inference selesai secara lokal."
                }
            } catch (error: Throwable) {
                if (messages.lastOrNull()?.role == "user") {
                    messages.add(ChatMessage("assistant", "Generasi gagal. Periksa pesan kesalahan."))
                }
                generationPreview = ""
                errorText = error.message ?: "Inference gagal."
            } finally {
                isBusy = false
                busyLabel = ""
                busyProgress = -1f
                progressDetail = ""
            }
        }
    }

    private fun loadModelIndex() {
        val stored = getSharedPreferences("nexora_models", MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(stored)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val projectorPath = item.optString("projectorPath").takeIf { it.isNotBlank() }
                val model = LocalModel(
                    item.getString("id"),
                    item.getString("name"),
                    item.getString("path"),
                    item.getLong("sizeBytes"),
                    projectorPath,
                    item.optString("projectorName").takeIf { it.isNotBlank() }
                )
                if (File(model.path).isFile) {
                    if (model.projectorPath != null && !File(model.projectorPath).isFile) {
                        models.add(model.copy(projectorPath = null, projectorName = null))
                    } else {
                        models.add(model)
                    }
                }
            }
        }.onFailure {
            models.clear()
            errorText = "Daftar model lokal rusak dan perlu diimpor ulang."
        }
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
