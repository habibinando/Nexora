package com.example.nexora.engine.dataset

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HuggingFaceSample(
    val prompt: String,
    val completion: String,
    val category: String,
    val rowId: Int
)

data class HuggingFaceDatasetMetadata(
    val id: String,
    val description: String,
    val downloads: Int = 0,
    val likes: Int = 0,
    val defaultSplit: String = "train"
)

data class HuggingFaceFetchResult(
    val isSuccess: Boolean,
    val datasetId: String,
    val samples: List<HuggingFaceSample>,
    val totalFetched: Int,
    val sourceUrl: String,
    val errorMessage: String = ""
)

object HuggingFaceDatasetClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    val POPULAR_DATASETS = listOf(
        HuggingFaceDatasetMetadata(
            id = "tatsu-lab/alpaca",
            description = "52,000 instruksi bahasa umum untuk fine-tuning LLM instruction-following.",
            defaultSplit = "train"
        ),
        HuggingFaceDatasetMetadata(
            id = "roneneldan/TinyStories",
            description = "Cerita sintetik kosakata terbatas ideal untuk pelatihan model on-device.",
            defaultSplit = "train"
        ),
        HuggingFaceDatasetMetadata(
            id = "wikitext",
            description = "Artikel Wikipedia bermutu tinggi untuk pemodelan bahasa teks terstruktur.",
            defaultSplit = "train"
        ),
        HuggingFaceDatasetMetadata(
            id = "fka/awesome-chatgpt-prompts",
            description = "Koleksi ragam prompt peran dan skenario agen pintar.",
            defaultSplit = "train"
        ),
        HuggingFaceDatasetMetadata(
            id = "imdb",
            description = "Ulasan film untuk klasifikasi sentimen dan penalaran konteks teks.",
            defaultSplit = "train"
        )
    )

    /**
     * Mengambil sampel baris data asli dari Hugging Face Datasets Server API.
     */
    suspend fun fetchDatasetRows(
        datasetId: String,
        split: String = "train",
        config: String = "default",
        limit: Int = 15,
        offset: Int = 0
    ): HuggingFaceFetchResult = withContext(Dispatchers.IO) {
        val cleanDataset = datasetId.trim()
        if (cleanDataset.isBlank()) {
            return@withContext HuggingFaceFetchResult(
                isSuccess = false,
                datasetId = datasetId,
                samples = emptyList(),
                totalFetched = 0,
                sourceUrl = "",
                errorMessage = "ID dataset tidak boleh kosong."
            )
        }

        // 1. Coba Hugging Face Datasets Server API (standard REST endpoint untuk membaca rows)
        val url = "https://datasets-server.huggingface.co/rows?dataset=$cleanDataset&config=$config&split=$split&offset=$offset&length=$limit"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Nexora-OnDevice-Trainer/2.0")
                .header("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string() ?: ""
                val json = JSONObject(bodyString)
                val rowsArray = json.optJSONArray("rows") ?: JSONArray()
                val parsedSamples = mutableListOf<HuggingFaceSample>()

                for (i in 0 until rowsArray.length()) {
                    val rowItem = rowsArray.getJSONObject(i)
                    val rowData = rowItem.optJSONObject("row") ?: JSONObject()
                    val sample = extractPromptCompletionFromRow(rowData, i + 1, cleanDataset)
                    if (sample != null) {
                        parsedSamples.add(sample)
                    }
                }

                if (parsedSamples.isNotEmpty()) {
                    return@withContext HuggingFaceFetchResult(
                        isSuccess = true,
                        datasetId = cleanDataset,
                        samples = parsedSamples,
                        totalFetched = parsedSamples.size,
                        sourceUrl = url
                    )
                }
            }

            // 2. Fallback: Coba config nama dataset tanpa slash jika ada (misal wikitext -> wikitext-2-raw-v1)
            if (cleanDataset == "wikitext") {
                val fallbackUrl = "https://datasets-server.huggingface.co/rows?dataset=wikitext&config=wikitext-2-raw-v1&split=train&offset=0&length=$limit"
                val fbReq = Request.Builder().url(fallbackUrl).header("User-Agent", "Nexora/2.0").get().build()
                val fbRes = client.newCall(fbReq).execute()
                if (fbRes.isSuccessful) {
                    val fbBody = fbRes.body?.string() ?: ""
                    val fbJson = JSONObject(fbBody)
                    val rowsArray = fbJson.optJSONArray("rows") ?: JSONArray()
                    val parsed = mutableListOf<HuggingFaceSample>()
                    for (i in 0 until rowsArray.length()) {
                        val rowObj = rowsArray.getJSONObject(i).optJSONObject("row") ?: JSONObject()
                        val sample = extractPromptCompletionFromRow(rowObj, i + 1, cleanDataset)
                        if (sample != null) parsed.add(sample)
                    }
                    if (parsed.isNotEmpty()) {
                        return@withContext HuggingFaceFetchResult(
                            isSuccess = true,
                            datasetId = cleanDataset,
                            samples = parsed,
                            totalFetched = parsed.size,
                            sourceUrl = fallbackUrl
                        )
                    }
                }
            }

            // 3. Fallback: Coba ambil raw JSONL dari repo Hugging Face langsung
            val rawRepoUrl = "https://huggingface.co/datasets/$cleanDataset/raw/main/train.jsonl"
            val rawReq = Request.Builder().url(rawRepoUrl).header("User-Agent", "Nexora/2.0").get().build()
            val rawRes = client.newCall(rawReq).execute()
            if (rawRes.isSuccessful) {
                val rawBody = rawRes.body?.string() ?: ""
                val lines = rawBody.lineSequence().take(limit).toList()
                val parsed = mutableListOf<HuggingFaceSample>()
                lines.forEachIndexed { idx, line ->
                    if (line.trim().startsWith("{")) {
                        try {
                            val rowObj = JSONObject(line.trim())
                            val sample = extractPromptCompletionFromRow(rowObj, idx + 1, cleanDataset)
                            if (sample != null) parsed.add(sample)
                        } catch (_: Exception) {}
                    }
                }
                if (parsed.isNotEmpty()) {
                    return@withContext HuggingFaceFetchResult(
                        isSuccess = true,
                        datasetId = cleanDataset,
                        samples = parsed,
                        totalFetched = parsed.size,
                        sourceUrl = rawRepoUrl
                    )
                }
            }

            HuggingFaceFetchResult(
                isSuccess = false,
                datasetId = cleanDataset,
                samples = emptyList(),
                totalFetched = 0,
                sourceUrl = url,
                errorMessage = "Gagal mengambil data dari server Hugging Face (Status ${response.code}: ${response.message}). Pastikan koneksi internet aktif dan ID dataset valid di Hugging Face."
            )
        } catch (e: Exception) {
            Log.e("HuggingFaceClient", "Error fetching dataset $cleanDataset: ${e.message}", e)
            HuggingFaceFetchResult(
                isSuccess = false,
                datasetId = cleanDataset,
                samples = emptyList(),
                totalFetched = 0,
                sourceUrl = url,
                errorMessage = "Kesalahan koneksi Hugging Face: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    /**
     * Ekstraksi kolom teks yang relevan secara adaptif dari struktur data Hugging Face.
     */
    private fun extractPromptCompletionFromRow(row: JSONObject, index: Int, datasetName: String): HuggingFaceSample? {
        // Cek schema Alpaca: instruction (+ input) -> output
        if (row.has("instruction") && row.has("output")) {
            val inst = row.optString("instruction", "").trim()
            val inp = row.optString("input", "").trim()
            val out = row.optString("output", "").trim()
            val prompt = if (inp.isNotBlank()) "$inst\nInput: $inp" else inst
            if (prompt.isNotBlank() && out.isNotBlank()) {
                return HuggingFaceSample(prompt, out, "HF_ALPACA", index)
            }
        }

        // Cek schema Prompt / Response / Completion
        val promptCandidate = row.optString("prompt", "").ifBlank {
            row.optString("question", "").ifBlank {
                row.optString("act", "").ifBlank {
                    row.optString("input", "")
                }
            }
        }.trim()

        val completionCandidate = row.optString("completion", "").ifBlank {
            row.optString("response", "").ifBlank {
                row.optString("answer", "").ifBlank {
                    row.optString("output", "")
                }
            }
        }.trim()

        if (promptCandidate.isNotBlank() && completionCandidate.isNotBlank()) {
            return HuggingFaceSample(promptCandidate, completionCandidate, "HF_QA", index)
        }

        // Cek schema Text (Language Modeling seperti WikiText atau TinyStories)
        val text = row.optString("text", "").ifBlank {
            row.optString("story", "").ifBlank {
                row.optString("content", "")
            }
        }.trim()

        if (text.length >= 20) {
            val firstSentence = text.substringBefore(". ").trim()
            val rest = text.substringAfter(". ").trim()
            val p = if (firstSentence.length in 10..150) "Lanjutkan teks berikut: $firstSentence." else "Konteks bacaan:\n${text.take(80)}"
            val c = if (rest.isNotBlank()) rest.take(300) else text.take(300)
            return HuggingFaceSample(p, c, "HF_LM", index)
        }

        return null
    }
}
