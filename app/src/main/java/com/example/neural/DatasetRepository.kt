package com.example.neural

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class DatasetInfo(
    val name: String,
    val text: String,
    val characterCount: Int,
    val wordCount: Int,
    val uniqueVocabSize: Int,
    val sizeBytes: Long,
    val vocabList: List<Char>,
    val charFrequency: List<Pair<Char, Int>>,
    val isCustomOrImported: Boolean = false
)

object DatasetRepository {

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

    fun computeDatasetInfo(name: String, text: String, isCustom: Boolean): DatasetInfo {
        val clean = text.ifBlank { "Nexora On-Device Neural Intelligence" }
        val chars = clean.toCharArray()
        val freqMap = HashMap<Char, Int>()
        for (c in chars) {
            freqMap[c] = (freqMap[c] ?: 0) + 1
        }
        val sortedVocab = freqMap.keys.sorted()
        val sortedFreq = freqMap.toList().sortedByDescending { it.second }.take(30)
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val bytes = clean.toByteArray(Charsets.UTF_8).size.toLong()

        return DatasetInfo(
            name = name,
            text = clean,
            characterCount = clean.length,
            wordCount = words,
            uniqueVocabSize = sortedVocab.size,
            sizeBytes = bytes,
            vocabList = sortedVocab,
            charFrequency = sortedFreq,
            isCustomOrImported = isCustom
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

    suspend fun importFromFileUri(context: Context, uri: Uri, fileName: String?): Result<DatasetInfo> = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: throw IllegalArgumentException("Tidak dapat membaca file dari penyimpanan.")

            val name = fileName ?: "File Impor (${uri.lastPathSegment ?: "teks"})"
            val info = computeDatasetInfo(name, content, isCustom = true)
            _activeDataset.value = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
