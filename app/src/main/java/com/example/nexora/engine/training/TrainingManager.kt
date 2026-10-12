package com.example.nexora.engine.training

import android.content.Context
import com.example.nexora.model.database.AdapterModuleEntity
import com.example.nexora.model.database.ModelVersionEntity
import com.example.nexora.model.database.NexoraDao
import com.example.nexora.model.database.TrainingDataEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TrainingBenchmarkResult(
    val adapterId: String,
    val initialLoss: Float,
    val finalLoss: Float,
    val benchmarkScore: Float,
    val durationMs: Long,
    val isAccepted: Boolean,
    val summary: String
)

class TrainingManager(
    private val dao: NexoraDao,
    private val context: Context? = null
) {

    val allTrainingData: Flow<List<TrainingDataEntity>> = dao.getAllTrainingData()
    val allAdapters: Flow<List<AdapterModuleEntity>> = dao.getAllAdapterModules()
    val allModelVersions: Flow<List<ModelVersionEntity>> = dao.getAllModelVersions()

    private fun getAdaptersDir(): File {
        val baseDir = context?.filesDir ?: File("/data/data/com.example/files")
        return File(baseDir, "adapters").apply { mkdirs() }
    }

    /**
     * Menambah pasangan data pelatihan secara langsung (manual).
     */
    suspend fun addManualTrainingData(
        prompt: String,
        completion: String,
        category: String = "MANUAL_INPUT",
        score: Int = 100
    ): Long {
        return dao.insertTrainingData(
            TrainingDataEntity(
                sourceExperimentId = "MANUAL-${System.currentTimeMillis()}",
                prompt = prompt.trim(),
                completion = completion.trim(),
                category = category,
                score = score,
                datasetName = "Nexora-Curated-User"
            )
        )
    }

    /**
     * Menghapus butir data pelatihan berdasarkan ID.
     */
    suspend fun deleteTrainingData(id: Long) {
        dao.deleteTrainingData(id)
    }

    /**
     * Menghapus beberapa butir data pelatihan sekaligus (batch delete).
     */
    suspend fun deleteTrainingDataByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) {
            dao.deleteTrainingDataByIds(ids)
        }
    }

    /**
     * Menghapus seluruh data pelatihan di dataset terkurasi.
     */
    suspend fun clearAllTrainingData() {
        dao.clearAllTrainingData()
    }

    /**
     * Mengimpor dataset dari baris JSONL standar {"prompt": "...", "completion": "..."}.
     */
    suspend fun importDatasetFromJsonl(jsonlContent: String): Int {
        var count = 0
        jsonlContent.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && trimmed.startsWith("{")) {
                try {
                    val obj = JSONObject(trimmed)
                    val p = obj.optString("prompt", "").trim()
                    val c = obj.optString("completion", "").ifBlank { obj.optString("response", "") }.trim()
                    val cat = obj.optString("category", "JSONL_IMPORT")
                    if (p.isNotBlank() && c.isNotBlank()) {
                        dao.insertTrainingData(
                            TrainingDataEntity(
                                sourceExperimentId = "JSONL-IMPORT",
                                prompt = p,
                                completion = c,
                                category = cat,
                                score = obj.optInt("score", 95),
                                datasetName = "Imported-JSONL"
                            )
                        )
                        count++
                    }
                } catch (_: Exception) {}
            }
        }
        return count
    }

    /**
     * Curates raw experiences from experiments and lessons learned into fine-tuning datasets.
     */
    suspend fun curateExperiencesIntoDataset(datasetName: String = "Nexora-Experience-V1"): Int {
        val memories = dao.getAllMemoriesList()
        var added = 0

        for (mem in memories) {
            if (mem.type == "SUCCESSFUL_STRATEGY" || mem.type == "LESSON_LEARNED" || mem.type == "FAILED_STRATEGY") {
                val prompt = "Task Context: ${mem.title}\nPrior Observation: ${mem.tags}"
                val completion = "Optimal Reasoning Heuristic: ${mem.content}"

                dao.insertTrainingData(
                    TrainingDataEntity(
                        sourceExperimentId = mem.tags,
                        prompt = prompt,
                        completion = completion,
                        category = if (mem.type == "SUCCESSFUL_STRATEGY") "STRATEGY_SUCCESS" else "FAILURE_CORRECTION",
                        score = if (mem.type == "SUCCESSFUL_STRATEGY") 100 else 85,
                        datasetName = datasetName
                    )
                )
                added++
            }
        }
        return added
    }

    /**
     * Exports curated training data to JSONL format standard for fine-tuning / adapter training.
     */
    suspend fun exportDatasetToJsonl(): String {
        val data = dao.getIncludedTrainingDataList()
        val sb = StringBuilder()
        for (item in data) {
            val json = JSONObject().apply {
                put("prompt", item.prompt)
                put("completion", item.completion)
                put("category", item.category)
                put("score", item.score)
            }
            sb.append(json.toString()).append("\n")
        }
        return sb.toString()
    }

    /**
     * Mode Belajar Sebutir-sebutir (Incremental Continual Learning):
     * Mencerna setiap pasangan data pelatihan secara bertahap satu per satu,
     * memperbarui bobot adapter nyata, dan memancarkan progres callback.
     */
    suspend fun trainIncrementalStepByStep(
        adapterName: String = "Incremental Continual LoRA",
        learningRate: Float = 0.005f,
        epochs: Int = 1,
        saveAsAdapter: Boolean = true,
        onProgress: (step: Int, total: Int, label: String, loss: Float) -> Unit
    ): TrainingBenchmarkResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val newId = "INC-${UUID.randomUUID().toString().take(6).uppercase()}"
        val includedData = dao.getIncludedTrainingDataList()

        if (includedData.isEmpty()) {
            return@withContext TrainingBenchmarkResult(
                adapterId = newId,
                initialLoss = 0.0f,
                finalLoss = 0.0f,
                benchmarkScore = 0.0f,
                durationMs = 10,
                isAccepted = false,
                summary = "Dataset pelatihan belum ada. Tambahkan data secara manual atau simpan dari riwayat chat terlebih dahulu."
            )
        }

        val storageDir = getAdaptersDir()
        val result = OnDeviceLearningEngine.trainIncrementalStepByStep(
            data = includedData,
            storageDir = storageDir,
            adapterId = newId,
            learningRate = learningRate,
            epochs = epochs,
            onStepProgress = onProgress
        )

        if (saveAsAdapter) {
            val entity = AdapterModuleEntity(
                moduleId = newId,
                name = adapterName,
                version = "inc-v1.0",
                description = "Adapter inkremental $epochs epoch hasil mencerna ${includedData.size} butir data on-device",
                parameterCount = result.parameterCountFormatted,
                baseModel = "NEXORA-CORE-Q4",
                trainingLoss = result.finalLoss,
                benchmarkScore = result.benchmarkScore,
                isEnabled = true
            )
            dao.insertAdapterModule(entity)
        }

        TrainingBenchmarkResult(
            adapterId = newId,
            initialLoss = result.initialLoss,
            finalLoss = result.finalLoss,
            benchmarkScore = result.benchmarkScore,
            durationMs = result.durationMs,
            isAccepted = true,
            summary = result.summary
        )
    }

    /**
     * Curates and trains an adapter on-device using actual data.
     */
    suspend fun trainNewAdapter(
        name: String,
        description: String,
        baseModelId: String = "NEXORA-CORE-Q4",
        paramSize: String = "belum ada"
    ): TrainingBenchmarkResult {
        val newId = "ADAPT-${UUID.randomUUID().toString().take(6).uppercase()}"
        val includedData = dao.getIncludedTrainingDataList()

        if (includedData.isEmpty()) {
            val entity = AdapterModuleEntity(
                moduleId = newId,
                name = name,
                version = "v1.0",
                description = description,
                parameterCount = "belum ada",
                baseModel = baseModelId,
                trainingLoss = 0.0f,
                benchmarkScore = 0.0f,
                isEnabled = false
            )
            dao.insertAdapterModule(entity)

            return TrainingBenchmarkResult(
                adapterId = newId,
                initialLoss = 0.0f,
                finalLoss = 0.0f,
                benchmarkScore = 0.0f,
                durationMs = 20,
                isAccepted = false,
                summary = "Dataset pelatihan belum ada (0 item). Modul '$name' dicatat dalam database (loss, parameter, dan benchmark: belum ada). Silakan kurasi pengalaman terlebih dahulu."
            )
        }

        // Run real on-device training on the actual dataset
        val storageDir = getAdaptersDir()
        val trainResult = OnDeviceLearningEngine.train(
            data = includedData,
            storageDir = storageDir,
            adapterId = newId
        )

        val entity = AdapterModuleEntity(
            moduleId = newId,
            name = name,
            version = "v1.0",
            description = "$description (Dilatih on-device dari ${includedData.size} contoh nyata)",
            parameterCount = trainResult.parameterCountFormatted,
            baseModel = baseModelId,
            trainingLoss = trainResult.finalLoss,
            benchmarkScore = trainResult.benchmarkScore,
            isEnabled = trainResult.isAccepted
        )
        dao.insertAdapterModule(entity)

        return TrainingBenchmarkResult(
            adapterId = newId,
            initialLoss = trainResult.initialLoss,
            finalLoss = trainResult.finalLoss,
            benchmarkScore = trainResult.benchmarkScore,
            durationMs = trainResult.durationMs,
            isAccepted = trainResult.isAccepted,
            summary = "${trainResult.summary}\nCatatan: Untuk fine-tuning LLM multi-miliar parameter (7B/13B), gunakan tombol Export JSONL ke server GPU."
        )
    }

    suspend fun toggleAdapter(moduleId: String, isEnabled: Boolean) {
        dao.setAdapterEnabled(moduleId, isEnabled)
    }

    suspend fun rollbackModelVersion(versionId: String) {
        dao.setActiveModelVersion(versionId)
    }
}
