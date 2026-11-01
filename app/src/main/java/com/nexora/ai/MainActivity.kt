package com.nexora.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

internal data class LocalModel(val id: String, val name: String, val path: String, val sizeBytes: Long)
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
    private var statusText by mutableStateOf("Menyiapkan runtime lokal…")
    private var systemInfo by mutableStateOf("Backend belum diinisialisasi.")
    private var hardwareInfo by mutableStateOf("Memeriksa perangkat…")
    private var errorText by mutableStateOf<String?>(null)

    private val importModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadModelIndex()
        hardwareInfo = readHardwareInfo()

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
                    onSendMessage = ::sendMessage,
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
            errorText = null
            try {
                val model = withContext(Dispatchers.IO) { copyAndValidateModel(uri) }
                models.add(model)
                saveModelIndex()
                page = AppPage.MODELS
                statusText = "Model ${model.name} berhasil diimpor. Model belum dimuat."
            } catch (error: Throwable) {
                errorText = error.message ?: "Gagal mengimpor model."
            } finally {
                isBusy = false
                busyLabel = ""
            }
        }
    }

    private fun copyAndValidateModel(uri: Uri): LocalModel {
        val displayName = contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        } ?: "model.gguf"

        require(ModelFileValidator.isSupportedSingleFile(displayName)) {
            "Pilih satu file GGUF biasa. Model GGUF yang dipecah menjadi beberapa shard belum didukung."
        }

        val directory = File(filesDir, "models").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val uniqueName = "${System.currentTimeMillis()}-$safeName"
        val temporary = File(directory, "$uniqueName.partial")
        val destination = File(directory, uniqueName)

        try {
            val source = contentResolver.openInputStream(uri) ?: error("File sumber tidak dapat dibaca.")
            source.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                    output.fd.sync()
                }
            }

            val header = ByteArray(4)
            FileInputStream(temporary).use { input ->
                require(input.read(header) == 4) { "File terlalu kecil untuk menjadi model GGUF." }
            }
            require(ModelFileValidator.isGgufFile(displayName, header)) {
                "Header file bukan GGUF yang valid. Impor dibatalkan."
            }
            require(temporary.length() > 4L) { "File GGUF kosong atau rusak." }
            check(temporary.renameTo(destination)) { "Tidak dapat menyimpan model ke penyimpanan aplikasi." }

            return LocalModel(UUID.randomUUID().toString(), displayName, destination.absolutePath, destination.length())
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
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
        val estimatedNeed = (file.length() * 1.20).toLong() + 512L * 1024L * 1024L
        if (memory.availMem < estimatedNeed) {
            errorText = "RAM tersedia belum cukup untuk memuat model ini dengan aman. " +
                "Estimasi kebutuhan: ${formatBytes(estimatedNeed)}; tersedia: ${formatBytes(memory.availMem)}."
            return
        }

        lifecycleScope.launch {
            isBusy = true
            busyLabel = "Memuat model ke RAM…"
            errorText = null
            try {
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
                val result = withContext(Dispatchers.IO) {
                    NativeLlama.loadModel(file.absolutePath, 2048, threads)
                }
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Gagal memuat model (kode $result). $detail")
                }
                activeModelId = model.id
                messages.clear()
                statusText = "Model aktif: ${model.name}"
            } catch (error: Throwable) {
                activeModelId = null
                errorText = error.message ?: "Gagal memuat model."
            } finally {
                isBusy = false
                busyLabel = ""
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
                messages.clear()
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
                    messages.clear()
                }
                withContext(Dispatchers.IO) { File(model.path).delete() }
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

    private fun changeMode(newMode: ChatMode) {
        if (isBusy || newMode == chatMode) return
        chatMode = newMode
        messages.clear()
        if (activeModelId != null) {
            lifecycleScope.launch(Dispatchers.IO) { NativeLlama.resetChat() }
        }
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
        messages.add(ChatMessage("assistant", ""))
        isBusy = true
        busyLabel = "Model sedang menghasilkan token…"
        errorText = null

        lifecycleScope.launch {
            try {
                val systemPrompt = when (chatMode) {
                    ChatMode.CHAT ->
                        "You are Nexora, a precise and helpful on-device AI assistant. " +
                            "Answer in the user's language. Never claim to use tools or data you do not have."
                    ChatMode.CODING ->
                        "You are Nexora Code, a senior software engineer. " +
                            "Produce correct, maintainable, modern code. Explain assumptions and never fabricate test results."
                }

                val result = withContext(Dispatchers.IO) {
                    NativeLlama.startPrompt(systemPrompt, trimmed, 512)
                }
                if (result != 0) {
                    val detail = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                    error("Prompt ditolak (kode $result). $detail")
                }

                val output = StringBuilder()
                while (true) {
                    val token = withContext(Dispatchers.IO) { NativeLlama.nextToken() } ?: break
                    output.append(token)
                    val index = messages.lastIndex
                    if (index >= 0) messages[index] = ChatMessage("assistant", output.toString())
                }

                val generationError = withContext(Dispatchers.IO) { NativeLlama.lastError() }
                if (generationError.isNotBlank()) errorText = generationError
                statusText = "Inference selesai secara lokal."
            } catch (error: Throwable) {
                val index = messages.lastIndex
                if (index >= 0 && messages[index].role == "assistant" && messages[index].content.isBlank()) {
                    messages[index] = ChatMessage("assistant", "Generasi gagal. Periksa pesan kesalahan.")
                }
                errorText = error.message ?: "Inference gagal."
            } finally {
                isBusy = false
                busyLabel = ""
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
                val model = LocalModel(
                    item.getString("id"),
                    item.getString("name"),
                    item.getString("path"),
                    item.getLong("sizeBytes")
                )
                if (File(model.path).isFile) models.add(model)
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
            append("\nRuntime: llama.cpp / CPU ARM64")
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
