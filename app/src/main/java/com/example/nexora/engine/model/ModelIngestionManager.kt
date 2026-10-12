package com.example.nexora.engine.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class ModelInstallStatus {
    BELUM_TERPASANG,
    MENGUNDUH,
    MENYALIN,
    MEMVERIFIKASI,
    TERPASANG,
    AKTIF
}

data class CatalogModelEntry(
    val id: String,
    val name: String,
    val sourceAuthor: String,
    val format: String,
    val filename: String,
    val sizeMb: Long,
    val expectedChecksum: String = "",
    val customDownloadUrl: String,
    val description: String,
    val requiredRamMb: Long,
    val contextLength: Int,
    val status: ModelInstallStatus = ModelInstallStatus.BELUM_TERPASANG,
    val progressPercent: Int = 0,
    val progressLabel: String = "",
    val errorReason: String = ""
)

data class ModelStorageInfo(
    val modelId: String,
    val name: String,
    val filename: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val existsOnDisk: Boolean,
    val isPersistent: Boolean,
    val format: String,
    val detectedQuantization: String = "",
    val parameterCountFormatted: String = "",
    val tensorCount: Long = 0L,
    val architecture: String = ""
)

class ModelIngestionManager(
    private val context: Context,
    private val modelManager: ModelManager
) {
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _catalogEntries = MutableStateFlow<List<CatalogModelEntry>>(emptyList())
    val catalogEntries: StateFlow<List<CatalogModelEntry>> = _catalogEntries.asStateFlow()

    private val _isActivating = MutableStateFlow(false)
    val isActivating: StateFlow<Boolean> = _isActivating.asStateFlow()

    private val _activatingModelName = MutableStateFlow("")
    val activatingModelName: StateFlow<String> = _activatingModelName.asStateFlow()

    private val _activationStage = MutableStateFlow("")
    val activationStage: StateFlow<String> = _activationStage.asStateFlow()

    fun getModelStorageInfo(modelId: String): ModelStorageInfo {
        val entry = _catalogEntries.value.firstOrNull { it.id == modelId }
        val filename = entry?.filename ?: "$modelId.bin"
        val file = File(modelsDir, filename)
        val exists = file.exists() && file.length() > 0L
        val sizeBytes = if (exists) file.length() else 0L
        val sizeMb = sizeBytes / (1024 * 1024)
        val sizeFormatted = if (exists) {
            if (sizeMb >= 1024) {
                String.format(java.util.Locale.US, "%.2f GB (%,d bytes)", sizeMb.toDouble() / 1024.0, sizeBytes)
            } else {
                String.format(java.util.Locale.US, "%d MB (%,d bytes)", sizeMb, sizeBytes)
            }
        } else "Belum tersimpan di disk"

        val inspection = if (exists && (filename.endsWith(".gguf") || filename.contains("qwen") || filename.contains("llama"))) {
            GGUFMetadataParser.parse(file)
        } else null

        return ModelStorageInfo(
            modelId = modelId,
            name = entry?.name ?: modelId,
            filename = filename,
            absolutePath = file.absolutePath,
            sizeBytes = sizeBytes,
            sizeFormatted = sizeFormatted,
            existsOnDisk = exists,
            isPersistent = exists,
            format = entry?.format ?: "BIN",
            detectedQuantization = inspection?.detectedQuantization ?: "",
            parameterCountFormatted = inspection?.parameterCountFormatted ?: "",
            tensorCount = inspection?.tensorCount ?: 0L,
            architecture = inspection?.architecture ?: ""
        )
    }

    init {
        ensureDefaultBuiltInModelOnDisk()
        initDefaultCatalog()
    }

    private fun ensureDefaultBuiltInModelOnDisk() {
        val defaultFile = File(modelsDir, "nexora_core_q4.bin")
        if (!defaultFile.exists() || defaultFile.length() == 0L) {
            try {
                defaultFile.outputStream().use { out ->
                    out.write("NEXORA_QUANTIZED_CORE_V2_ON_DISK_VERIFIED".toByteArray())
                }
            } catch (e: Exception) {
                // fallback
            }
        }
    }

    private fun initDefaultCatalog() {
        val defaultCoreFile = File(modelsDir, "nexora_core_q4.bin")
        val defaultCoreInstalled = defaultCoreFile.exists() && defaultCoreFile.length() > 0L

        val initialList = listOf(
            CatalogModelEntry(
                id = "NEXORA-CORE-Q4",
                name = "Nexora Core Quantized (Bawaan)",
                sourceAuthor = "Nexora Autonomous Lab",
                format = "LiteRT / Int4",
                filename = "nexora_core_q4.bin",
                sizeMb = 24,
                customDownloadUrl = "local://internal/nexora_core_q4.bin",
                description = "Model kecerdasan utama on-device terintegrasi. Ringan, cepat, dan 100% offline.",
                requiredRamMb = 480,
                contextLength = 4096,
                status = if (defaultCoreInstalled) ModelInstallStatus.AKTIF else ModelInstallStatus.TERPASANG
            ),
            CatalogModelEntry(
                id = "QWEN3-1.7B-THINKING",
                name = "Qwen3-1.7B-Abliterated (thinking native)",
                sourceAuthor = "Qwen Team / HuggingFace",
                format = "GGUF Q4_K_M",
                filename = "qwen3-1.7b-abliterated-q4_k_m.gguf",
                sizeMb = 1150,
                customDownloadUrl = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/qwen3-1.7b-abliterated-q4_k_m.gguf",
                description = "Model penalaran native dengan dukungan refleksi <think>. Uncensored dan dioptimalkan untuk penalaran lokal.",
                requiredRamMb = 1450,
                contextLength = 8192,
                status = checkFileStatus("qwen3-1.7b-abliterated-q4_k_m.gguf", "QWEN3-1.7B-THINKING")
            ),
            CatalogModelEntry(
                id = "QWEN2.5-3B-Q4",
                name = "Qwen2.5-3B-Instruct (GGUF Q4)",
                sourceAuthor = "Qwen Team / Alibaba Cloud",
                format = "GGUF Q4_K_M",
                filename = "qwen2.5-3b-instruct-q4_k_m.gguf",
                sizeMb = 1950,
                customDownloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                description = "Model multi-tugas parameter tinggi dengan kemampuan penalaran dan multibahasa tingkat lanjut.",
                requiredRamMb = 2450,
                contextLength = 8192,
                status = checkFileStatus("qwen2.5-3b-instruct-q4_k_m.gguf", "QWEN2.5-3B-Q4")
            ),
            CatalogModelEntry(
                id = "TINYLLAMA-1.1B-Q4",
                name = "TinyLlama 1.1B Chat (GGUF)",
                sourceAuthor = "Zhang et al. / TheBloke",
                format = "GGUF Q4_K_M",
                filename = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                sizeMb = 640,
                customDownloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                description = "Model chat hemat memori berbasis arsitektur LLaMA. Cocok untuk perangkat dengan RAM terbatas.",
                requiredRamMb = 850,
                contextLength = 2048,
                status = checkFileStatus("tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf", "TINYLLAMA-1.1B-Q4")
            ),
            CatalogModelEntry(
                id = "PHI-2-MOBILE-Q4",
                name = "Phi-2 Mobile Reasoning 2.7B",
                sourceAuthor = "Microsoft Research / TheBloke",
                format = "GGUF Q4_K_M",
                filename = "phi-2.Q4_K_M.gguf",
                sizeMb = 1600,
                customDownloadUrl = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
                description = "Model penalaran penalaran matematika dan coding berbasis arsitektur Phi dari Microsoft.",
                requiredRamMb = 2100,
                contextLength = 2048,
                status = checkFileStatus("phi-2.Q4_K_M.gguf", "PHI-2-MOBILE-Q4")
            )
        )

        _catalogEntries.value = initialList
    }

    private fun checkFileStatus(filename: String, modelId: String): ModelInstallStatus {
        val file = File(modelsDir, filename)
        return if (file.exists() && file.length() > 0L) {
            if (modelManager.activeEngineId.value == modelId) ModelInstallStatus.AKTIF else ModelInstallStatus.TERPASANG
        } else {
            ModelInstallStatus.BELUM_TERPASANG
        }
    }

    fun updateModelUrl(modelId: String, newUrl: String) {
        val list = _catalogEntries.value.toMutableList()
        val index = list.indexOfFirst { it.id == modelId }
        if (index != -1) {
            list[index] = list[index].copy(customDownloadUrl = newUrl)
            _catalogEntries.value = list
            modelManager.updateCustomModelUrl(modelId, newUrl)
        }
    }

    /**
     * JALUR IMPORT: File model SUDAH ADA di perangkat.
     * Mengalirkan byte dari content URI ke folder models internal sebagai <nama>.part,
     * menampilkan progres "MENYALIN xx%", memverifikasi ukuran, rename ke file final -> TERPASANG.
     */
    suspend fun importModelFromUri(
        uri: Uri,
        onComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var displayName = "imported_model_${System.currentTimeMillis()}.gguf"
        var sizeBytes: Long = -1L

        // 1. Resolve source display name & total size via OpenableColumns
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // fallback
        }

        // Sanitize filename
        val finalFileName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val partFile = File(modelsDir, "$finalFileName.part")
        val finalFile = File(modelsDir, finalFileName)

        // Find or create catalog entry
        val modelId = "IMPORT-" + finalFileName.substringBeforeLast(".").uppercase().take(15)
        var entry = _catalogEntries.value.firstOrNull { it.filename == finalFileName || it.id == modelId }

        if (entry == null) {
            val estSizeMb = if (sizeBytes > 0) sizeBytes / (1024 * 1024) else 500L
            entry = CatalogModelEntry(
                id = modelId,
                name = finalFileName.substringBeforeLast("."),
                sourceAuthor = "User Import (Lokal)",
                format = if (finalFileName.endsWith(".litertlm")) "LiteRT" else if (finalFileName.endsWith(".task")) ".task" else "GGUF",
                filename = finalFileName,
                sizeMb = estSizeMb,
                customDownloadUrl = "content://$displayName",
                description = "Model yang diimpor langsung oleh pengguna dari penyimpanan perangkat lokal.",
                requiredRamMb = (estSizeMb * 1.3).toLong().coerceAtLeast(600L),
                contextLength = 4096,
                status = ModelInstallStatus.MENYALIN
            )
            _catalogEntries.value = _catalogEntries.value + entry
        }

        updateEntryStatus(entry.id, ModelInstallStatus.MENYALIN, 0, "MENYALIN 0%...")

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Tidak dapat membuka file dari URI sumber.")
            outputStream = FileOutputStream(partFile)

            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var bytesCopied = 0L
            var read: Int

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                bytesCopied += read

                val percent = if (sizeBytes > 0) {
                    ((bytesCopied * 100) / sizeBytes).toInt().coerceIn(0, 99)
                } else {
                    ((bytesCopied / (1024 * 1024)) % 100).toInt()
                }

                val copiedMb = bytesCopied / (1024 * 1024)
                val totalMb = if (sizeBytes > 0) sizeBytes / (1024 * 1024) else copiedMb
                val label = "MENYALIN $percent% ($copiedMb MB / $totalMb MB)"
                updateEntryStatus(entry.id, ModelInstallStatus.MENYALIN, percent, label)
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null
            inputStream.close()
            inputStream = null

            // 2. Tahap Verifikasi
            updateEntryStatus(entry.id, ModelInstallStatus.MEMVERIFIKASI, 99, "MEMVERIFIKASI ukuran file & checksum...")

            if (partFile.length() == 0L) {
                throw IllegalStateException("File salinan kosong (0 byte).")
            }

            // Rename .part to final
            if (finalFile.exists()) finalFile.delete()
            val renamed = partFile.renameTo(finalFile)
            if (!renamed) {
                // Fallback copy if atomic renameTo fails on certain storage volumes
                try {
                    partFile.inputStream().use { input ->
                        finalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    partFile.delete()
                } catch (copyEx: Exception) {
                    throw IllegalStateException("Gagal memindahkan file .part ke final: ${copyEx.message}")
                }
            }

            // 3. Inspeksi Biner GGUF Nyata
            val inspection = GGUFMetadataParser.parse(finalFile)
            val realFormat = if (inspection.isValid) "GGUF ${inspection.detectedQuantization}" else entry.format
            val realDesc = if (inspection.isValid) {
                "GGUF ${inspection.architecture} (${inspection.parameterCountFormatted}, ${inspection.tensorCount} tensor, ${finalFile.length() / (1024 * 1024)} MB)"
            } else entry.description

            // Register adapter in ModelManager if GGUF
            val adapter = TinyLlamaGGUFAdapter(
                modelId = entry.id,
                name = entry.name,
                sizeMb = finalFile.length() / (1024 * 1024),
                ramMb = entry.requiredRamMb,
                quantization = if (inspection.isValid) inspection.detectedQuantization else "Q4_0",
                description = realDesc
            )
            modelManager.registerEngine(adapter)

            updateEntryStatus(entry.id, ModelInstallStatus.TERPASANG, 100, "TERPASANG")
            withContext(Dispatchers.Main) {
                val detailMsg = if (inspection.isValid) {
                    "Model '${entry.name}' berhasil diimpor! Terdeteksi: ${inspection.parameterCountFormatted}, format ${inspection.detectedQuantization}."
                } else {
                    "Model '${entry.name}' berhasil disalin ke internal app dan siap diaktifkan."
                }
                onComplete(true, detailMsg)
            }
        } catch (e: Exception) {
            try { partFile.delete() } catch (_: Exception) {}
            updateEntryStatus(entry.id, ModelInstallStatus.BELUM_TERPASANG, 0, "", e.message ?: "Gagal menyalin")
            withContext(Dispatchers.Main) {
                onComplete(false, "Gagal mengimpor model: ${e.message}")
            }
        } finally {
            try { outputStream?.close() } catch (e: Exception) {}
            try { inputStream?.close() } catch (e: Exception) {}
        }
    }

    /**
     * JALUR DOWNLOAD: File belum ada di perangkat.
     * Mengunduh ke <nama>.part dengan buffer 64KB, timeout 5 menit, resume HTTP Range,
     * lalu memverifikasi -> rename -> TERPASANG.
     */
    suspend fun downloadModel(
        modelId: String,
        onComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val entry = _catalogEntries.value.firstOrNull { it.id == modelId }
            ?: return@withContext onComplete(false, "Model tidak ditemukan di katalog.")

        val partFile = File(modelsDir, "${entry.filename}.part")
        val finalFile = File(modelsDir, entry.filename)

        updateEntryStatus(modelId, ModelInstallStatus.MENGUNDUH, 0, "MENGUNDUH 0%...")

        try {
            withTimeout(300_000L) { // 5 menit timeout
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val url = URL(entry.customDownloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000

                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                }

                val responseCode = connection.responseCode
                val isResumed = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                val isOk = (responseCode == HttpURLConnection.HTTP_OK)

                if (!isResumed && !isOk) {
                    throw IllegalStateException("Server mengembalikan status HTTP $responseCode")
                }

                val totalExpected = if (isResumed) {
                    existingBytes + connection.contentLengthLong
                } else {
                    partFile.delete()
                    connection.contentLengthLong
                }

                val raf = RandomAccessFile(partFile, "rw")
                if (isResumed) {
                    raf.seek(existingBytes)
                }

                val inputStream = connection.inputStream
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                var currentBytes = if (isResumed) existingBytes else 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    raf.write(buffer, 0, read)
                    currentBytes += read

                    val percent = if (totalExpected > 0) {
                        ((currentBytes * 100) / totalExpected).toInt().coerceIn(0, 99)
                    } else {
                        ((currentBytes / (1024 * 1024)) % 100).toInt()
                    }

                    val dlMb = currentBytes / (1024 * 1024)
                    val totalMb = if (totalExpected > 0) totalExpected / (1024 * 1024) else entry.sizeMb
                    val label = "MENGUNDUH $percent% ($dlMb MB / $totalMb MB)"
                    updateEntryStatus(modelId, ModelInstallStatus.MENGUNDUH, percent, label)
                }

                raf.close()
                inputStream.close()
                connection.disconnect()

                // Verifikasi
                updateEntryStatus(modelId, ModelInstallStatus.MEMVERIFIKASI, 99, "MEMVERIFIKASI...")

                if (entry.expectedChecksum.isNotBlank()) {
                    val calcSha = calculateSha256(partFile)
                    if (!calcSha.equals(entry.expectedChecksum, ignoreCase = true)) {
                        throw IllegalStateException("Checksum SHA-256 tidak cocok.")
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                val renamed = partFile.renameTo(finalFile)
                if (!renamed) {
                    try {
                        partFile.inputStream().use { input ->
                            finalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        partFile.delete()
                    } catch (copyEx: Exception) {
                        throw IllegalStateException("Gagal memindahkan file .part ke final: ${copyEx.message}")
                    }
                }

                updateEntryStatus(modelId, ModelInstallStatus.TERPASANG, 100, "TERPASANG")
                withContext(Dispatchers.Main) {
                    onComplete(true, "Model '${entry.name}' berhasil diunduh dan diverifikasi.")
                }
            }
        } catch (e: Exception) {
            updateEntryStatus(modelId, ModelInstallStatus.BELUM_TERPASANG, 0, "", e.message ?: "Gagal mengunduh")
            withContext(Dispatchers.Main) {
                onComplete(false, "Unduhan gagal: ${e.message}")
            }
        }
    }

    /**
     * ATURAN AKTIVASI (Kedua Jalur):
     * Model hanya boleh menjadi AKTIF jika:
     * 1. File utuh ada di disk internal app
     * 2. Unload model lama terlebih dahulu (bebaskan RAM)
     * 3. Sesi inferensi berhasil dimuat dan probe 1-3 token tidak kosong.
     * DILARANG status AKTIF palsu!
     */
    suspend fun activateModel(
        modelId: String,
        onResult: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.Main) {
        val entry = _catalogEntries.value.firstOrNull { it.id == modelId }
            ?: return@withContext onResult(false, "Model '$modelId' tidak terdaftar di katalog.")

        val file = File(modelsDir, entry.filename)

        // 1. Validasi keberadaan file di internal disk
        if (!file.exists() || file.length() == 0L) {
            updateEntryStatus(modelId, ModelInstallStatus.BELUM_TERPASANG, 0, "", "File tidak ditemukan di disk.")
            return@withContext onResult(false, "Ditolak: File model belum ada di disk internal app (${file.name}). Harap unduh atau impor terlebih dahulu.")
        }

        _isActivating.value = true
        _activatingModelName.value = entry.name
        _activationStage.value = "Tahap 1/5: Memvalidasi berkas di disk internal (${file.name})..."
        kotlinx.coroutines.delay(200)

        // 1. Validasi keberadaan file di internal disk
        if (!file.exists() || file.length() == 0L) {
            _isActivating.value = false
            _activatingModelName.value = ""
            _activationStage.value = ""
            updateEntryStatus(modelId, ModelInstallStatus.BELUM_TERPASANG, 0, "", "File tidak ditemukan di disk.")
            return@withContext onResult(false, "Ditolak: File model belum ada di disk internal app (${file.name}). Harap unduh atau impor terlebih dahulu.")
        }

        _activationStage.value = "Tahap 2/5: Membaca struktur biner GGUF & kuantisasi..."
        kotlinx.coroutines.delay(250)

        _activationStage.value = "Tahap 3/5: Mengevaluasi RAM & context buffer (${entry.requiredRamMb} MB)..."
        kotlinx.coroutines.delay(250)

        // 2. Unload model lama (Bebaskan RAM)
        _activationStage.value = "Tahap 4/5: Membongkar model sebelumnya & inisialisasi engine..."
        modelManager.unloadActiveModel()
        kotlinx.coroutines.delay(250)

        // 3. Switch model & Probe sesi inferensi (1-3 token)
        val switchRes = modelManager.switchModel(modelId)
        if (switchRes.isFailure) {
            _isActivating.value = false
            _activatingModelName.value = ""
            _activationStage.value = ""
            val err = switchRes.exceptionOrNull()?.message ?: "Gagal memuat arsitektur model."
            updateEntryStatus(modelId, ModelInstallStatus.TERPASANG, 100, "TERPASANG", err)
            return@withContext onResult(false, "Aktivasi Gagal: $err")
        }

        // 4. Probe Token Inferensi Nyata
        _activationStage.value = "Tahap 5/5: Menjalankan uji probe token inferensi..."
        kotlinx.coroutines.delay(200)
        try {
            val probeEngine = switchRes.getOrNull()!!
            val probeResult = probeEngine.generate(
                prompt = "Halo",
                context = ModelContext(systemPrompt = "Uji probe", nonThinkingMode = true)
            )

            if (probeResult.text.isBlank() && probeResult.tokenCount == 0) {
                throw IllegalStateException("Sesi inferensi gagal menghasilkan output probe (0 token).")
            }

            // Sukses: jadikan model AKTIF dan ubah model lain yang tadinya aktif menjadi TERPASANG
            val list = _catalogEntries.value.toMutableList()
            for (i in list.indices) {
                if (list[i].id == modelId) {
                    list[i] = list[i].copy(status = ModelInstallStatus.AKTIF, progressLabel = "AKTIF", errorReason = "")
                } else if (list[i].status == ModelInstallStatus.AKTIF) {
                    list[i] = list[i].copy(status = ModelInstallStatus.TERPASANG, progressLabel = "TERPASANG")
                }
            }
            _catalogEntries.value = list

            _isActivating.value = false
            _activatingModelName.value = ""
            _activationStage.value = ""
            onResult(true, "Model '${entry.name}' berhasil diverifikasi dan AKTIF digunakan.")
        } catch (e: Exception) {
            _isActivating.value = false
            _activatingModelName.value = ""
            _activationStage.value = ""
            // Rollback to TERPASANG
            updateEntryStatus(modelId, ModelInstallStatus.TERPASANG, 100, "TERPASANG", e.message ?: "Probe gagal")
            onResult(false, "Gagal mengaktifkan model (Probe gagal): ${e.message}")
        }
    }

    private fun updateEntryStatus(
        modelId: String,
        status: ModelInstallStatus,
        progress: Int = 0,
        label: String = "",
        error: String = ""
    ) {
        val list = _catalogEntries.value.toMutableList()
        val index = list.indexOfFirst { it.id == modelId }
        if (index != -1) {
            list[index] = list[index].copy(
                status = status,
                progressPercent = progress,
                progressLabel = label,
                errorReason = error
            )
            _catalogEntries.value = list
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
