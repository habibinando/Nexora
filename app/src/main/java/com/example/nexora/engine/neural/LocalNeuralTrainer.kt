package com.example.nexora.engine.neural

import android.content.Context
import android.util.Log
import com.example.nexora.engine.training.TrainingBenchmarkResult
import com.example.nexora.model.database.TrainingDataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.roundToInt

data class TrainingRamProfile(
    val id: String,
    val name: String,
    val targetRamMb: Int,
    val description: String,
    val embedDim: Int,
    val hiddenDim: Int,
    val chunkSize: Int,
    val batchSize: Int
) {
    companion object {
        val ALL_PROFILES = listOf(
            TrainingRamProfile(
                id = "50mb",
                name = "Ultra-Lite (50 MB)",
                targetRamMb = 50,
                description = "Konsumsi memori minimal untuk HP spesifikasi hemat RAM",
                embedDim = 32,
                hiddenDim = 64,
                chunkSize = 32,
                batchSize = 1
            ),
            TrainingRamProfile(
                id = "150mb",
                name = "Ringan (150 MB)",
                targetRamMb = 150,
                description = "Keseimbangan seimbang, cepat dan stabil untuk ponsel harian",
                embedDim = 64,
                hiddenDim = 128,
                chunkSize = 64,
                batchSize = 2
            ),
            TrainingRamProfile(
                id = "350mb",
                name = "Menengah (350 MB)",
                targetRamMb = 350,
                description = "Representasi teks lebih kaya dengan hidden state lebih lebar",
                embedDim = 128,
                hiddenDim = 256,
                chunkSize = 96,
                batchSize = 4
            ),
            TrainingRamProfile(
                id = "600mb",
                name = "Tinggi (600 MB)",
                targetRamMb = 600,
                description = "Kapasitas modeling tinggi untuk dataset panjang dan kompleks",
                embedDim = 192,
                hiddenDim = 384,
                chunkSize = 128,
                batchSize = 4
            ),
            TrainingRamProfile(
                id = "1gb",
                name = "Maksimal (1 GB)",
                targetRamMb = 1024,
                description = "Skala terbesar on-device RAM dengan 512 hidden dimension",
                embedDim = 256,
                hiddenDim = 512,
                chunkSize = 160,
                batchSize = 8
            )
        )

        fun findProfileByDimensions(embedDim: Int, hiddenDim: Int): TrainingRamProfile? {
            return ALL_PROFILES.find { it.embedDim == embedDim && it.hiddenDim == hiddenDim }
        }
    }
}

data class LocalTrainingProgress(
    val isTraining: Boolean = false,
    val currentEpoch: Int = 0,
    val totalEpochs: Int = 1,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentLoss: Float = 0f,
    val initialLoss: Float = 0f,
    val bestLoss: Float? = null,
    val valLoss: Float? = null,
    val bestValLoss: Float? = null,
    val perplexity: Float? = null,
    val benchmarkScore: Float? = null,
    val batchSize: Int = 1,
    val chunkSize: Int = 40,
    val vocabSize: Int = CharRNNModel.DEFAULT_VOCAB_SIZE,
    val numHeads: Int = 0,
    val hiddenDim: Int = 128,
    val embedDim: Int = 64,
    val totalParameters: Long = 0L,
    val tokensPerSec: Float? = null,
    val totalDurationMs: Long = 0L,
    val trainingLogs: List<String> = emptyList(),
    val step1Loss: Float? = null,
    val step10Loss: Float? = null,
    val step50Loss: Float? = null,
    val step100Loss: Float? = null,
    val sampleGeneration: String = "",
    val wOutL2NormBefore: Float = 0f,
    val wOutL2NormAfter: Float = 0f,
    val isWeightsSaved: Boolean = false,
    val weightsFilePath: String = "",
    val verificationPassed: Boolean = false,
    val executorchTensorsExported: Boolean = false,
    val statusMessage: String = "Siap melatih",
    val optimizerType: OptimizerType = OptimizerType.ADAMW,
    val optimizerDisplayName: String = "AdamW (Decoupled Weight Decay - SOTA)",
    val ramProfileName: String = "Ringan (150 MB)",
    val memoryUsedMb: Float = 0f,
    val memoryMaxMb: Float = 0f,
    val earlyStoppingEnabled: Boolean = true,
    val earlyStoppingPatience: Int = 3,
    val earlyStoppingTriggered: Boolean = false,
    val bestCheckpointEpoch: Int? = null,
    val bestCheckpointValLoss: Float? = null,
    val isCheckpointRestored: Boolean = false
)

/**
 * Controller for running genuine on-device Char-RNN training in Dispatchers.Default coroutines.
 * Connects directly to Room database dataset and stores model weights to app's filesDir.
 */
class LocalNeuralTrainer(
    private val context: Context
) {
    private val weightsFile = File(context.filesDir, "models/local_char_rnn_weights.json")
    private val bestCheckpointFile = File(context.filesDir, "models/local_char_rnn_best_checkpoint.json")

    // var agar bisa diganti instance baru via CharRNNModel.fromFile jika vocab/dimensi di file berbeda.
    private var _model = CharRNNModel(embedDim = 32, hiddenDim = 64)
    val model: CharRNNModel get() = _model

    private val _progress = MutableStateFlow(
        LocalTrainingProgress(
            vocabSize = _model.vocabSize,
            hiddenDim = _model.hiddenDim,
            embedDim = _model.embedDim,
            weightsFilePath = weightsFile.absolutePath,
            isWeightsSaved = weightsFile.exists() && weightsFile.length() > 0L
        )
    )
    val progress: StateFlow<LocalTrainingProgress> = _progress.asStateFlow()

    private val _trainedInferenceOutput = MutableStateFlow<String>("")
    val trainedInferenceOutput: StateFlow<String> = _trainedInferenceOutput.asStateFlow()

    fun getRealJvmMemory(): Pair<Float, Float> {
        val runtime = Runtime.getRuntime()
        val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f)).coerceAtLeast(0f)
        val maxMb = (runtime.maxMemory() / (1024f * 1024f)).coerceAtLeast(1f)
        return Pair(usedMb, maxMb)
    }

    init {
        val (memUsed, memMax) = getRealJvmMemory()
        // Fast synchronous check if file exists, marks state.
        // Full file load can be called via ensureLoaded() asynchronously.
        if (weightsFile.exists()) {
            // Prioritas: bangun ulang instance dari file (vocab/dimensi otomatis cocok).
            val loadedModel = CharRNNModel.fromFile(weightsFile)
            val hasWeights = if (loadedModel != null) {
                _model = loadedModel
                true
            } else {
                _model.loadFromFile(weightsFile) // fallback kompatibilitas
            }
            if (hasWeights) {
                val prof = TrainingRamProfile.findProfileByDimensions(_model.embedDim, _model.hiddenDim)
                _progress.update {
                    it.copy(
                        vocabSize = _model.vocabSize,
                        hiddenDim = _model.hiddenDim,
                        embedDim = _model.embedDim,
                        totalParameters = _model.countParameters(),
                        ramProfileName = prof?.name ?: "Kustom (${_model.hiddenDim}h)",
                        memoryUsedMb = memUsed,
                        memoryMaxMb = memMax,
                        isWeightsSaved = true,
                        statusMessage = "Bobot terlatih siap (${weightsFile.length() / 1024} KB)"
                    )
                }
            }
        } else {
            val prof = TrainingRamProfile.findProfileByDimensions(_model.embedDim, _model.hiddenDim)
            _progress.update {
                it.copy(
                    totalParameters = _model.countParameters(),
                    ramProfileName = prof?.name ?: "Ringan (150 MB)",
                    memoryUsedMb = memUsed,
                    memoryMaxMb = memMax
                )
            }
        }
    }

    /**
     * Konfigurasi arsitektur neural & profil RAM secara dinamis (50MB - 1GB).
     * Memperbarui hidden dimension, embed dimension, optimizer, dan batch size.
     */
    fun configureModelArchitecture(
        embedDim: Int,
        hiddenDim: Int,
        optimizer: OptimizerType = _progress.value.optimizerType,
        ramProfileName: String = "Kustom",
        chunkSize: Int = 40,
        batchSize: Int = 1
    ) {
        if (_progress.value.isTraining) return
        if (_model.embedDim != embedDim || _model.hiddenDim != hiddenDim) {
            _model = CharRNNModel(
                vocab = _model.vocab,
                embedDim = embedDim,
                hiddenDim = hiddenDim
            )
            // Simpan model berdimensi baru ke disk
            _model.saveToFile(weightsFile)
        }
        val (memUsed, memMax) = getRealJvmMemory()
        _progress.update {
            it.copy(
                embedDim = _model.embedDim,
                hiddenDim = _model.hiddenDim,
                vocabSize = _model.vocabSize,
                totalParameters = _model.countParameters(),
                optimizerType = optimizer,
                optimizerDisplayName = optimizer.displayName,
                ramProfileName = ramProfileName,
                chunkSize = chunkSize,
                batchSize = batchSize,
                memoryUsedMb = memUsed,
                memoryMaxMb = memMax,
                isWeightsSaved = weightsFile.exists() && weightsFile.length() > 0L,
                statusMessage = "Arsitektur diatur ke hidden=$hiddenDim, embed=$embedDim ($ramProfileName)"
            )
        }
    }

    /**
     * Fix #9: Asynchronously loads weights from disk without blocking the caller thread.
     */
    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (!weightsFile.exists() || weightsFile.length() == 0L) return@withContext false
        try {
            val loadedModel = CharRNNModel.fromFile(weightsFile)
            val loaded = if (loadedModel != null) {
                _model = loadedModel
                true
            } else {
                _model.loadFromFile(weightsFile)
            }
            if (loaded) {
                val (memUsed, memMax) = getRealJvmMemory()
                val prof = TrainingRamProfile.findProfileByDimensions(_model.embedDim, _model.hiddenDim)
                _progress.update {
                    it.copy(
                        vocabSize = _model.vocabSize,
                        hiddenDim = _model.hiddenDim,
                        embedDim = _model.embedDim,
                        totalParameters = _model.countParameters(),
                        ramProfileName = prof?.name ?: "Kustom (${_model.hiddenDim}h)",
                        memoryUsedMb = memUsed,
                        memoryMaxMb = memMax,
                        isWeightsSaved = true,
                        statusMessage = "Bobot terlatih berhasil dimuat dari storage"
                    )
                }
            }
            loaded
        } catch (e: Exception) {
            Log.e("LocalNeuralTrainer", "Gagal memuat bobot model: ${e.message}")
            false
        }
    }

    /**
     * Run training loop in background without blocking UI.
     * Uses practical defaults (epochs = 25, learningRate = 0.05f) to ensure real neural loss reduction.
     * Evaluates true Validation Loss with pure forward pass (evaluateStep) and tracks real JVM RAM.
     */
    suspend fun trainOnDataset(
        dataset: List<TrainingDataEntity>,
        epochs: Int = 25,
        learningRate: Float = 0.05f,
        optimizerType: OptimizerType = _progress.value.optimizerType,
        batchSize: Int = _progress.value.batchSize,
        chunkSize: Int = _progress.value.chunkSize,
        earlyStoppingEnabled: Boolean = _progress.value.earlyStoppingEnabled,
        earlyStoppingPatience: Int = _progress.value.earlyStoppingPatience,
        sampleEverySteps: Int = 10
    ): Boolean = withContext(Dispatchers.Default) {
        if (dataset.isEmpty()) {
            _progress.update {
                it.copy(
                    statusMessage = "Dataset kosong! Tempel teks atau kurasi data terlebih dahulu."
                )
            }
            return@withContext false
        }

        // Dynamically extract unique characters from dataset and expand vocab if needed
        val allDatasetTexts = dataset.flatMap { listOf(it.prompt, it.completion) }
        _model = CharRNNModel.expandVocabIfNeeded(_model, allDatasetTexts)

        // Split dataset into Training (80-85%) and Validation (15-20%) sets
        val valCount = if (dataset.size >= 4) maxOf(1, (dataset.size * 0.2).roundToInt()) else 0
        val trainSet = if (valCount > 0) dataset.dropLast(valCount) else dataset
        val valSet = if (valCount > 0) dataset.takeLast(valCount) else dataset

        // 1. Anti-Gimmick Verification check first
        val preVerificationPassed = CharRNNModel.verifyRealTraining()

        val startTime = System.currentTimeMillis()
        var totalTokensProcessed = 0
        val logsList = mutableListOf<String>()
        var bestLossVal: Float? = null
        var bestValLossVal: Float? = null
        var bestValEpoch: Int? = null
        var patienceCounter = 0
        var earlyStoppingTriggered = false
        val minDelta = 0.0005f

        val normBefore = _model.computeWOutL2Norm()
        val (initMemUsed, initMemMax) = getRealJvmMemory()

        _progress.update {
            it.copy(
                isTraining = true,
                totalEpochs = epochs,
                currentEpoch = 1,
                currentStep = 0,
                totalSteps = trainSet.size * epochs,
                vocabSize = _model.vocabSize,
                hiddenDim = _model.hiddenDim,
                embedDim = _model.embedDim,
                totalParameters = _model.countParameters(),
                optimizerType = optimizerType,
                optimizerDisplayName = optimizerType.displayName,
                batchSize = batchSize,
                chunkSize = chunkSize,
                earlyStoppingEnabled = earlyStoppingEnabled,
                earlyStoppingPatience = earlyStoppingPatience,
                earlyStoppingTriggered = false,
                isCheckpointRestored = false,
                memoryUsedMb = initMemUsed,
                memoryMaxMb = initMemMax,
                wOutL2NormBefore = normBefore,
                verificationPassed = preVerificationPassed,
                trainingLogs = emptyList(),
                statusMessage = "Memulai optimasi dengan ${optimizerType.shortName} & autograd..."
            )
        }

        var stepCounter = 0
        val totalSteps = trainSet.size * epochs
        var firstLoss: Float? = null
        var s1Loss: Float? = null
        var s10Loss: Float? = null
        var s50Loss: Float? = null
        var s100Loss: Float? = null

        var latestLoss = 0f
        var latestValLoss: Float? = null
        var latestPerplexity: Float? = null
        var latestGeneration = ""

        try {
            for (epoch in 1..epochs) {
                // Dynamic DataLoader: Acak urutan sampel tiap epoch agar model tidak menghafal urutan data
                val epochTrainSet = if (trainSet.size > 1) trainSet.shuffled(kotlin.random.Random(1337 + epoch)) else trainSet

                for (sample in epochTrainSet) {
                    // Fix #4: Cooperative cancellation check per sample
                    ensureActive()

                    stepCounter++
                    val fullText = "${sample.prompt}\n${sample.completion}\n"
                    val tokens = fullText.map { _model.charToToken(it) }.toIntArray()
                    totalTokensProcessed += tokens.size

                    if (tokens.size >= 2) {
                        // Chunk long sequences into manageable context lengths for mobile CPU
                        var chunkStart = 0
                        while (chunkStart + 1 < tokens.size) {
                            // Fix #4: Cancellation in inner chunk loop
                            ensureActive()

                            val chunkEnd = minOf(chunkStart + chunkSize, tokens.size)
                            // Fix #5: Use copyOfRange instead of sliceArray for safety
                            val inputSlice = tokens.copyOfRange(chunkStart, chunkEnd - 1)
                            val targetSlice = tokens.copyOfRange(chunkStart + 1, chunkEnd)

                            if (inputSlice.isNotEmpty() && inputSlice.size == targetSlice.size) {
                                val metrics = _model.trainStep(inputSlice, targetSlice, learningRate, optimizerType)
                                latestLoss = metrics.loss

                                if (firstLoss == null) firstLoss = latestLoss
                                if (bestLossVal == null || latestLoss < bestLossVal!!) {
                                    bestLossVal = latestLoss
                                }
                                if (stepCounter == 1 && s1Loss == null) s1Loss = latestLoss
                                if (stepCounter == 10 && s10Loss == null) s10Loss = latestLoss
                                if (stepCounter == 50 && s50Loss == null) s50Loss = latestLoss
                                if (stepCounter == 100 && s100Loss == null) s100Loss = latestLoss
                            }
                            chunkStart += chunkSize
                        }
                    }

                    // Read real JVM memory usage
                    val (curMemUsed, curMemMax) = getRealJvmMemory()

                    // Append real chronological step log
                    if (stepCounter % 5 == 0 || stepCounter == 1 || stepCounter == totalSteps) {
                        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                        val tps = (totalTokensProcessed / elapsedSec)
                        val valLog = latestValLoss?.let { " | Val: ${"%.4f".format(it)}" } ?: ""
                        val logLine = "[Step $stepCounter/$totalSteps] Ep $epoch - Loss: ${"%.4f".format(latestLoss)}$valLog | RAM: ${"%.1f".format(curMemUsed)}MB | ${"%.1f".format(tps)} tok/s"
                        logsList.add(logLine)
                        if (logsList.size > 80) logsList.removeAt(0)
                    }

                    // Periodically generate text live during training to show real progress
                    if (stepCounter % sampleEverySteps == 0 || stepCounter == 1) {
                        val samplePrompt = sample.prompt.take(15).ifBlank { "Halo " }
                        latestGeneration = _model.generate(samplePrompt, maxChars = 45, temperature = 0.6f)
                        Log.i("ANTI_GIMMICK", "[Step $stepCounter/$totalSteps] Real Loss: $latestLoss | Gen: \"$latestGeneration\"")
                    }

                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                    val currentTps = totalTokensProcessed / elapsedSec

                    // Fix #3: Atomic update using StateFlow.update
                    _progress.update {
                        it.copy(
                            currentEpoch = epoch,
                            totalEpochs = epochs,
                            currentStep = stepCounter,
                            totalSteps = totalSteps,
                            currentLoss = latestLoss,
                            initialLoss = firstLoss ?: 0f,
                            bestLoss = bestLossVal,
                            valLoss = latestValLoss,
                            bestValLoss = bestValLossVal,
                            perplexity = latestPerplexity,
                            tokensPerSec = currentTps,
                            totalDurationMs = System.currentTimeMillis() - startTime,
                            memoryUsedMb = curMemUsed,
                            memoryMaxMb = curMemMax,
                            trainingLogs = logsList.toList(),
                            step1Loss = s1Loss,
                            step10Loss = s10Loss,
                            step50Loss = s50Loss,
                            step100Loss = s100Loss,
                            sampleGeneration = latestGeneration,
                            statusMessage = "Epoch $epoch/$epochs - Step $stepCounter: Loss = ${"%.4f".format(latestLoss)}${latestValLoss?.let { v -> " (Val: ${"%.4f".format(v)})" } ?: ""}"
                        )
                    }
                }

                // Evaluasi Validation Loss Lengkap pada akhir setiap Epoch
                var epochValLossSum = 0f
                var epochValCount = 0
                for (valSample in valSet) {
                    ensureActive()
                    val vText = "${valSample.prompt}\n${valSample.completion}\n"
                    val vTokens = vText.map { _model.charToToken(it) }.toIntArray()
                    if (vTokens.size >= 2) {
                        val vIn = vTokens.copyOfRange(0, minOf(vTokens.size - 1, chunkSize))
                        val vTgt = vTokens.copyOfRange(1, minOf(vTokens.size, chunkSize + 1))
                        if (vIn.isNotEmpty() && vIn.size == vTgt.size) {
                            val evalM = _model.evaluateStep(vIn, vTgt)
                            epochValLossSum += evalM.loss
                            epochValCount++
                        }
                    }
                }

                val currentEpochValLoss = if (epochValCount > 0) epochValLossSum / epochValCount else latestLoss
                val currentEpochPerplexity = kotlin.math.exp(currentEpochValLoss.toDouble()).toFloat()
                latestValLoss = currentEpochValLoss
                latestPerplexity = currentEpochPerplexity

                // Evaluasi Checkpoint & Early Stopping
                if (bestValLossVal == null || currentEpochValLoss < (bestValLossVal!! - minDelta)) {
                    val deltaImprovement = if (bestValLossVal != null) bestValLossVal!! - currentEpochValLoss else 0f
                    bestValLossVal = currentEpochValLoss
                    bestValEpoch = epoch
                    patienceCounter = 0
                    // Auto-save best checkpoint to disk
                    _model.saveToFile(bestCheckpointFile)
                    val impStr = if (deltaImprovement > 0f) " (turun ${"%.4f".format(deltaImprovement)})" else ""
                    logsList.add("★ [Checkpoint Ep $epoch] Rekor Val Loss baru: ${"%.4f".format(currentEpochValLoss)}$impStr -> Disimpan ke checkpoint!")
                } else {
                    patienceCounter++
                    logsList.add("! [Patience Ep $epoch] Val Loss (${"%.4f".format(currentEpochValLoss)}) tidak membaik (Best: ${"%.4f".format(bestValLossVal!!)} di Ep $bestValEpoch). Patience: $patienceCounter/$earlyStoppingPatience")
                    if (earlyStoppingEnabled && patienceCounter >= earlyStoppingPatience) {
                        earlyStoppingTriggered = true
                        logsList.add("🛑 [Early Stopping] Pelatihan dihentikan di Epoch $epoch karena Val Loss tidak membaik selama $earlyStoppingPatience epoch berturut-turut!")
                    }
                }

                val (epochMemUsed, epochMemMax) = getRealJvmMemory()
                _progress.update {
                    it.copy(
                        currentEpoch = epoch,
                        valLoss = latestValLoss,
                        bestValLoss = bestValLossVal,
                        perplexity = latestPerplexity,
                        bestCheckpointEpoch = bestValEpoch,
                        bestCheckpointValLoss = bestValLossVal,
                        earlyStoppingTriggered = earlyStoppingTriggered,
                        memoryUsedMb = epochMemUsed,
                        memoryMaxMb = epochMemMax,
                        trainingLogs = logsList.toList(),
                        statusMessage = if (earlyStoppingTriggered) {
                            "🛑 Early Stopping dipicu di Ep $epoch (Patience $earlyStoppingPatience habis. Best: Ep $bestValEpoch, Val: ${"%.4f".format(bestValLossVal!!)})"
                        } else {
                            "Epoch $epoch/$epochs selesai - Loss: ${"%.4f".format(latestLoss)} | Val Loss: ${"%.4f".format(currentEpochValLoss)}"
                        }
                    )
                }

                if (earlyStoppingTriggered) {
                    break
                }
            }

            // 4. Restore Bobot Terbaik dari Checkpoint jika ada
            var isRestored = false
            if (bestCheckpointFile.exists()) {
                val restored = _model.loadFromFile(bestCheckpointFile)
                if (restored) {
                    isRestored = true
                    _model.saveToFile(weightsFile) // Simpan bobot optimal permanen ke berkas utama
                    logsList.add("✓ [Restore Checkpoint] Model dipulihkan ke bobot terbaik dari Epoch $bestValEpoch (Val Loss: ${"%.4f".format(bestValLossVal!!)})")
                }
            }
            if (!isRestored) {
                _model.saveToFile(weightsFile)
            }

            val normAfter = _model.computeWOutL2Norm()
            val l2Delta = kotlin.math.abs(normAfter - normBefore)

            // Export to PyTorch ExecuTorch Tensor structures
            val execuTorchTensors = _model.toExecuTorchTensors()
            Log.i("ANTI_GIMMICK", "ExecuTorch Tensors generated: ${execuTorchTensors.keys.joinToString()}")

            // Fix #6: Evaluate real Benchmark Accuracy with pure forward pass (evaluateStep)
            var evalCorrect = 0
            var evalTotal = 0
            for (sample in valSet.take(20)) {
                ensureActive()
                val full = "${sample.prompt}\n${sample.completion}"
                val testTokens = full.take(30).map { _model.charToToken(it) }.toIntArray()
                if (testTokens.size >= 2) {
                    val inputSeq = testTokens.copyOfRange(0, testTokens.size - 1)
                    val targetSeq = testTokens.copyOfRange(1, testTokens.size)
                    val m = _model.evaluateStep(inputSeq, targetSeq) // Pure forward evaluation pass
                    if (m.loss < 2.5f) evalCorrect++
                    evalTotal++
                }
            }

            // Fix #2: Pure calculation without fake 75% fallback. If evalTotal == 0, benchmarkAcc = 0f
            val benchmarkAcc = if (evalTotal > 0) ((evalCorrect.toFloat() / evalTotal) * 100f) else 0f

            val finalElapsedMs = System.currentTimeMillis() - startTime
            val (finMemUsed, finMemMax) = getRealJvmMemory()
            val finishStatus = if (earlyStoppingTriggered) "Early Stopping dihentikan dini" else "Pelatihan tuntas"
            logsList.add("✓ [$finishStatus] dalam ${finalElapsedMs}ms. Best Val Loss: ${"%.4f".format(bestValLossVal ?: latestLoss)} (Ep $bestValEpoch), Akurasi: ${"%.1f".format(benchmarkAcc)}%")

            // Fix #3: Atomic update
            _progress.update {
                it.copy(
                    isTraining = false,
                    bestLoss = bestLossVal,
                    valLoss = latestValLoss,
                    bestValLoss = bestValLossVal,
                    perplexity = latestPerplexity,
                    benchmarkScore = benchmarkAcc,
                    totalDurationMs = finalElapsedMs,
                    memoryUsedMb = finMemUsed,
                    memoryMaxMb = finMemMax,
                    earlyStoppingTriggered = earlyStoppingTriggered,
                    bestCheckpointEpoch = bestValEpoch,
                    bestCheckpointValLoss = bestValLossVal,
                    isCheckpointRestored = isRestored,
                    trainingLogs = logsList.toList(),
                    wOutL2NormAfter = normAfter,
                    isWeightsSaved = true,
                    executorchTensorsExported = true,
                    statusMessage = if (earlyStoppingTriggered) {
                        "✓ Early Stopping Selesai! Dipulihkan ke Epoch $bestValEpoch (Val: ${"%.4f".format(bestValLossVal ?: latestLoss)}) • Bobot optimal tersimpan (${weightsFile.length() / 1024} KB)."
                    } else {
                        "✓ Pelatihan Selesai! Best Val Loss: ${"%.4f".format(bestValLossVal ?: latestLoss)} (Ep $bestValEpoch) • Bobot optimal tersimpan (${weightsFile.length() / 1024} KB)."
                    }
                )
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e("LocalNeuralTrainer", "Error during training: ${e.message}", e)
            _progress.update {
                it.copy(
                    isTraining = false,
                    statusMessage = "Pelatihan terhenti: ${e.message}"
                )
            }
            return@withContext false
        }
    }

    /**
     * Pelatihan Inkremental Sebutir-sebutir langsung ke Bobot Model Utama.
     * Memperbarui bobot neural CharRNN secara langsung di memori dan storage (weightsFile),
     * TANPA membuat entitas modul adapter baru.
     * Mendukung multi-epoch (1 - 500) dan learning rate hingga 0.00001 dengan proteksi memori & yield.
     */
    suspend fun trainIncrementalDirectToBaseModel(
        dataset: List<TrainingDataEntity>,
        learningRate: Float = 0.005f,
        epochs: Int = 1,
        optimizerType: OptimizerType = _progress.value.optimizerType,
        chunkSize: Int = _progress.value.chunkSize,
        onProgress: (step: Int, total: Int, label: String, loss: Float) -> Unit
    ): TrainingBenchmarkResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        if (dataset.isEmpty()) {
            return@withContext TrainingBenchmarkResult(
                adapterId = "BASE-INCREMENTAL",
                initialLoss = 0f,
                finalLoss = 0f,
                benchmarkScore = 0f,
                durationMs = 0L,
                isAccepted = false,
                summary = "Dataset pelatihan kosong. Tambahkan data terlebih dahulu."
            )
        }

        // Dynamically extract unique characters from dataset and expand vocab if needed
        val allDatasetTexts = dataset.flatMap { listOf(it.prompt, it.completion) }
        _model = CharRNNModel.expandVocabIfNeeded(_model, allDatasetTexts)

        try {
            val safeEpochs = epochs.coerceIn(1, 500)
            val effectiveLr = learningRate.coerceIn(0.00001f, 0.1f)
            val normBefore = _model.computeWOutL2Norm()
            var firstLoss: Float? = null
            var lastLoss = 0f
            var globalStep = 0
            val totalSteps = dataset.size * safeEpochs
            var correct = 0

            for (epoch in 1..safeEpochs) {
                for (sample in dataset) {
                    // Fix #4: Cooperative cancellation
                    ensureActive()

                    globalStep++
                    // Fix #7: Bounded context length up to 500 chars (200 prompt + 300 completion)
                    val safePrompt = sample.prompt.take(200)
                    val safeCompletion = sample.completion.take(300)
                    val fullText = "$safePrompt\n$safeCompletion\n"
                    val tokens = fullText.map { _model.charToToken(it) }.toIntArray()

                    val label = if (safeEpochs > 1) {
                        "[Ep $epoch/$safeEpochs] ${sample.prompt.take(25).ifBlank { "Sampel" }}"
                    } else {
                        sample.prompt.take(30).ifBlank { "Sampel #$globalStep" }
                    }

                    // Fix #1: If token size is insufficient, skip without counting toward accuracy
                    if (tokens.size < 2) {
                        onProgress(globalStep, totalSteps, label, lastLoss)
                        ensureActive()
                        continue
                    }

                    val chunkSize = 40
                    var chunkStart = 0
                    var chunkCount = 0
                    var chunkLossSum = 0f
                    // Fix #7: maxChunksPerSample = 12 covers the entire prompt+completion safely with yields
                    val maxChunksPerSample = 12
                    while (chunkStart + 1 < tokens.size && chunkCount < maxChunksPerSample) {
                        ensureActive()
                        val chunkEnd = minOf(chunkStart + chunkSize, tokens.size)
                        // Fix #5: copyOfRange instead of sliceArray
                        val inputSlice = tokens.copyOfRange(chunkStart, chunkEnd - 1)
                        val targetSlice = tokens.copyOfRange(chunkStart + 1, chunkEnd)
                        if (inputSlice.isNotEmpty() && inputSlice.size == targetSlice.size) {
                            val m = _model.trainStep(inputSlice, targetSlice, effectiveLr, optimizerType)
                            chunkLossSum += m.loss
                            chunkCount++
                        }
                        chunkStart += chunkSize
                    }

                    // Fix #1: Guard chunkCount == 0 to prevent sampleLoss = 0f polluting accuracy
                    if (chunkCount == 0) {
                        onProgress(globalStep, totalSteps, label, lastLoss)
                        ensureActive()
                        continue
                    }

                    val sampleLoss = chunkLossSum / chunkCount
                    if (firstLoss == null) firstLoss = sampleLoss
                    lastLoss = sampleLoss
                    if (sampleLoss < 2.5f) correct++

                    onProgress(globalStep, totalSteps, label, sampleLoss)

                    // Beri nafas coroutine agar GC bisa jalan & sistem tidak membunuh proses
                    kotlinx.coroutines.yield()
                }
            }

            // Simpan bobot neural yang telah diperbarui ke berkas model utama
            _model.saveToFile(weightsFile)
            val normAfter = _model.computeWOutL2Norm()
            val durationMs = System.currentTimeMillis() - startTime

            // Fix #2: Real calculation without fake 75% fallback
            val benchAcc = if (totalSteps > 0) ((correct.toFloat() / totalSteps) * 100f).coerceIn(0f, 100f) else 0f

            val (incMemUsed, incMemMax) = getRealJvmMemory()

            // Fix #3: Atomic update
            _progress.update { current ->
                current.copy(
                    isWeightsSaved = true,
                    vocabSize = _model.vocabSize,
                    hiddenDim = _model.hiddenDim,
                    embedDim = _model.embedDim,
                    totalParameters = _model.countParameters(),
                    memoryUsedMb = incMemUsed,
                    memoryMaxMb = incMemMax,
                    currentLoss = lastLoss,
                    bestLoss = minOf(current.bestLoss ?: lastLoss, lastLoss),
                    benchmarkScore = benchAcc,
                    wOutL2NormBefore = normBefore,
                    wOutL2NormAfter = normAfter,
                    statusMessage = "✓ Bobot model utama berhasil diperbarui ($totalSteps langkah, $safeEpochs epoch)!"
                )
            }

            TrainingBenchmarkResult(
                adapterId = "BASE-MODEL-DIRECT",
                initialLoss = firstLoss ?: 0f,
                finalLoss = lastLoss,
                benchmarkScore = benchAcc,
                durationMs = durationMs,
                isAccepted = true,
                summary = "Bobot model utama berhasil diperbarui secara inkremental ($safeEpochs epoch, ${dataset.size} sampel, total $totalSteps step). Loss: ${"%.4f".format(firstLoss ?: 0f)} -> ${"%.4f".format(lastLoss)}. Akurasi: ${"%.1f".format(benchAcc)}%."
            )
        } catch (e: Exception) {
            Log.e("LocalNeuralTrainer", "Error during incremental base training: ${e.message}", e)
            TrainingBenchmarkResult(
                adapterId = "BASE-MODEL-DIRECT",
                initialLoss = 0f,
                finalLoss = 0f,
                benchmarkScore = 0f,
                durationMs = System.currentTimeMillis() - startTime,
                isAccepted = false,
                summary = "Pelatihan aman terhenti: ${e.message ?: "Memori atau thread dibatasi sistem"}"
            )
        }
    }

    /**
     * Direct Inference Test with user prompt using trained weights.
     */
    fun runTrainedInference(prompt: String, maxTokens: Int = 100): String {
        val clean = prompt.trim()
        val text = _model.generate(if (clean.isEmpty()) "Halo" else clean, maxChars = maxTokens, temperature = 0.7f)
        _trainedInferenceOutput.value = text
        return text
    }

    /**
     * Direct Inference Streaming with user prompt using trained weights.
     */
    fun runTrainedInferenceStream(prompt: String, maxTokens: Int = 120): Flow<String> {
        val clean = prompt.trim()
        return _model.generateStream(if (clean.isEmpty()) "Halo" else clean, maxChars = maxTokens, temperature = 0.7f)
    }

    /**
     * Export raw weights JSON string so user can copy/export directly.
     */
    fun exportWeightsJson(): String {
        return if (weightsFile.exists()) weightsFile.readText() else ""
    }

    /**
     * Import raw weights JSON string and load into memory.
     */
    fun importWeightsJson(jsonString: String): Boolean {
        return try {
            weightsFile.parentFile?.mkdirs()
            weightsFile.writeText(jsonString)
            val success = _model.loadFromFile(weightsFile)
            if (success) {
                _progress.update {
                    it.copy(
                        isWeightsSaved = true,
                        statusMessage = "Bobot berhasil diimpor & dimuat ke model lokal!"
                    )
                }
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    fun hasTrainedWeights(): Boolean {
        return weightsFile.exists() && weightsFile.length() > 0L
    }

    /**
     * Fix #10: Inspect single inference step via PyTorch ExecuTorch EValue container.
     * Clarified description and function naming.
     */
    fun inspectExecuTorchEValueShape(prompt: String): String {
        val token = if (prompt.isNotEmpty()) _model.charToToken(prompt.first()) else 0
        val initialZeroHidden = FloatArray(_model.hiddenDim)
        val (eValueOutput, _) = _model.forwardEValue(token, initialZeroHidden)
        val tensor = eValueOutput.toTensor()
        return "ExecuTorch EValue output shape: ${tensor.shape().joinToString("x")}"
    }

    @Deprecated("Gunakan inspectExecuTorchEValueShape", ReplaceWith("inspectExecuTorchEValueShape(prompt)"))
    fun runExecuTorchEValueInference(prompt: String): String = inspectExecuTorchEValueShape(prompt)

    /**
     * Fix #8: Inspect or load a pre-compiled ExecuTorch .pte Module file if present on device.
     * Uses withContext(Dispatchers.IO) to prevent UI thread jank.
     */
    suspend fun loadExecuTorchModule(pteFile: File): Module? = withContext(Dispatchers.IO) {
        if (!pteFile.exists() || pteFile.length() == 0L) return@withContext null
        try {
            Module.load(pteFile.absolutePath)
        } catch (e: Exception) {
            Log.e("LocalNeuralTrainer", "Failed to load ExecuTorch Module from ${pteFile.name}: ${e.message}")
            null
        }
    }
}
