package com.example.neural

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

data class DatasetInfo(
    val name: String,
    val text: String,
    val characterCount: Int,
    val wordCount: Int,
    val uniqueVocabSize: Int,
    val sizeBytes: Long,
    val vocabList: List<Char>,
    val charFrequency: List<Pair<Char, Int>>,
    val isCustomOrImported: Boolean = false,
    // Pratinjau singkat untuk ditampilkan di UI (mencegah crash TextField saat dataset besar)
    val previewText: String = "",
    // true jika teks terlalu besar untuk diedit di layar (editor harus read-only / pakai preview)
    val isTooLargeForEditor: Boolean = false
)

object DatasetRepository {

    /** Batas maksimum ukuran file impor (byte). Mencegah OutOfMemoryError. */
    const val MAX_IMPORT_BYTES: Long = 5L * 1024 * 1024 // 5 MB

    /** Jumlah karakter yang ditampilkan sebagai pratinjau ketika dataset terlalu besar. */
    const val PREVIEW_CHAR_LIMIT: Int = 4000

    /** Di atas jumlah karakter ini, editor teks di UI dinonaktifkan (read-only preview). */
    const val EDITOR_CHAR_LIMIT: Int = 16000

    val INDONESIAN_CONVERSATIONAL = """
User: Halo Nexora, siapa kamu?
Nexora: Halo! Saya Nexora, model AI saraf yang dilatih langsung di perangkat Android Anda.
User: Apa keunggulan AI on-device?
Nexora: Keunggulan AI on-device adalah privasi data terjaga 100%, bekerja offline tanpa internet, dan tidak memerlukan biaya server.
User: Bagaimana cara melatih model saraf di HP?
Nexora: Nexora menggunakan forward pass, backward pass (BPTT), dan optimisasi gradien menggunakan C++20 NDK native untuk efisiensi CPU maksimal.
User: Apa itu loss cross entropy?
Nexora: Loss cross entropy mengukur seberapa dekat probabilitas karakter prediksi model dengan karakter target aktual. Semakin kecil loss, semakin cerdas modelnya.
User: Selamat pagi Nexora!
Nexora: Selamat pagi! Siap melanjutkan eksperimen pelatihan saraf hari ini?
""".trimIndent()

    val TECH_AI_KNOWLEDGE = """
Nexora Neural Engine Architecture:
1. Input layer menerima representasi one-hot karakter dari vocabulary V.
2. Hidden layer recurrent (Whh) memelihara context memory state h(t) = tanh(Wxh * x(t) + Whh * h(t-1) + bh).
3. Output projection layer Why menghasilkan logit probabilitas karakter berikutnya z(t) = Why * h(t) + by.
4. Softmax probability: P(c_i) = exp(z_i / T) / sum(exp(z_j / T)).
5. Backpropagation Through Time (BPTT) menghitung gradien L2 norm dan melakukan clipping gradien agar pelatihan stabil di arsitektur ARM64.
""".trimIndent()

    val KOTLIN_CODE_CORPUS = """
fun trainNexoraModel(dataset: String, hiddenSize: Int): Float {
    val model = NexoraModel(dataset.toSet().toList(), hiddenSize)
    val metrics = model.step(dataset, learningRate = 0.01f)
    return metrics.loss
}
class NexoraState(val epoch: Int, val loss: Float) {
    fun isConverged(): Boolean = loss < 1.0f
}
""".trimIndent()

    private val _activeDataset = MutableStateFlow(computeDatasetInfo("Percakapan Nexora", INDONESIAN_CONVERSATIONAL, false))
    val activeDataset: StateFlow<DatasetInfo> = _activeDataset.asStateFlow()

    /**
     * Menghitung statistik dataset dalam SATU lintasan (single-pass) tanpa membuat
     * salinan array besar atau list kata. Versi lama menggunakan split(Regex) yang
     * membuat jutaan objek String dan memicu OutOfMemoryError pada file besar.
     */
    fun computeDatasetInfo(name: String, text: String, isCustom: Boolean): DatasetInfo {
        val clean = text.ifBlank { "Nexora On-Device Neural Intelligence" }

        val freqMap = HashMap<Char, Int>()
        var wordCount = 0
        var inWord = false
        var utf8Bytes = 0L

        for (i in 0 until clean.length) {
            val c = clean[i]
            freqMap[c] = (freqMap[c] ?: 0) + 1

            // Hitung kata: transisi whitespace -> non-whitespace (setara split(\s+))
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                wordCount++
            }

            // Hitung ukuran byte UTF-8 tanpa membuat salinan toByteArray()
            val code = c.code
            utf8Bytes += when {
                code <= 0x7F -> 1
                code <= 0x7FF -> 2
                code in 0xD800..0xDBFF -> 4 // high surrogate: pasangannya dihitung 4 byte sekali
                code in 0xDC00..0xDFFF -> 0 // low surrogate: sudah dihitung di high surrogate
                else -> 3
            }
        }

        val sortedVocab = freqMap.keys.sorted()
        val sortedFreq = freqMap.toList().sortedByDescending { it.second }.take(30)
        val tooLarge = clean.length > EDITOR_CHAR_LIMIT
        val preview = if (tooLarge) clean.take(PREVIEW_CHAR_LIMIT) else clean

        return DatasetInfo(
            name = name,
            text = clean,
            characterCount = clean.length,
            wordCount = wordCount,
            uniqueVocabSize = sortedVocab.size,
            sizeBytes = utf8Bytes,
            vocabList = sortedVocab,
            charFrequency = sortedFreq,
            isCustomOrImported = isCustom,
            previewText = preview,
            isTooLargeForEditor = tooLarge
        )
    }

    fun selectPresetDataset(presetIndex: Int) {
        val (name, text) = when (presetIndex) {
            1 -> "Arsitektur & Teori AI" to TECH_AI_KNOWLEDGE
            2 -> "Kode Kotlin Nexora" to KOTLIN_CODE_CORPUS
            else -> "Percakapan Nexora" to INDONESIAN_CONVERSATIONAL
        }
        _activeDataset.value = computeDatasetInfo(name, text, isCustom = false)
    }

    fun setCustomTextDataset(name: String, text: String) {
        _activeDataset.value = computeDatasetInfo(name.ifBlank { "Dataset Kustom" }, text, isCustom = true)
    }

    /**
     * Mengimpor file teks dengan proteksi:
     * 1. Cek ukuran via AssetFileDescriptor (jika provider mengembalikannya).
     * 2. Baca byte secara streaming dengan hard cap MAX_IMPORT_BYTES (mencegah OOM).
     * 3. Validasi UTF-8 ketat + deteksi file biner (NUL / replacement char).
     * Semua berjalan di Dispatchers.IO.
     */
    suspend fun importFromFileUri(context: Context, uri: Uri, fileName: String?): Result<DatasetInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. Cek ukuran deklaratif (cepat, tanpa membaca isi)
            val declaredLength: Long = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (_: Exception) {
                -1L
            }
            if (declaredLength > MAX_IMPORT_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "File terlalu besar (${formatBytes(declaredLength)}). " +
                            "Batas maksimum impor adalah ${formatBytes(MAX_IMPORT_BYTES)}."
                    )
                )
            }

            // 2. Baca byte secara streaming dengan hard cap (menangani provider yang ukurannya tidak diketahui)
            val bytes: ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException(
                            "File terlalu besar (lebih dari ${formatBytes(MAX_IMPORT_BYTES)}). " +
                                "Batas maksimum impor adalah ${formatBytes(MAX_IMPORT_BYTES)}."
                        )
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: throw IllegalArgumentException("Tidak dapat membaca file dari penyimpanan.")

            if (bytes.isEmpty()) {
                throw IllegalArgumentException("File kosong atau tidak dapat dibaca.")
            }

            // 3. Validasi UTF-8 ketat + deteksi file biner
            val content = String(bytes, Charsets.UTF_8)
            if (content.contains('\u0000') || content.contains('\uFFFD')) {
                throw IllegalArgumentException("File berisi data biner atau bukan teks UTF-8 yang valid.")
            }

            val name = fileName ?: "File Impor (${uri.lastPathSegment ?: "teks"})"
            val info = computeDatasetInfo(name, content, isCustom = true)
            _activeDataset.value = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
