package com.example.nexora.engine.neural

import android.content.Context
import android.util.Log
import com.example.nexora.engine.training.TrainingBenchmarkResult
import com.example.nexora.model.database.TrainingDataEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    /**
     * Estimasi RAM NYATA (model + gradien + momen optimizer + tape) untuk profil ini.
     * `targetRamMb` hanyalah label target; angka ini yang dihitung dari jumlah parameter.
     */
    fun estimatedRamMb(vocabSize: Int): Float =
        CharRNNModel.estimateTrainingMemoryMb(vocabSize, embedDim, hiddenDim, chunkSize)

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
    val isCheckpointRestored: Boolean = false,
    // --- Tambahan (semua punya default, kompatibel dengan pemanggil lama) ---
    /** Learning rate yang SEDANG dipakai (setelah warmup/cosine decay). */
    val learningRate: Float = 0f,
    /** Estimasi RAM nyata model+optimizer (dihitung dari jumlah parameter), bukan label profil. */
    val estimatedModelRamMb: Float = 0f,
    /** false jika dataset < 4 sampel: tidak ada validation set, early stopping memantau train loss. */
    val hasValidationSet: Boolean = true,
    /** Update yang dilewati karena gradien NaN/Inf. */
    val skippedUpdates: Int = 0,
    /** true jika loss/gradien meledak (NaN/Inf) dan pelatihan dihentikan. */
    val divergenceDetected: Boolean = false,
    /** Norma gradien global pada update terakhir. */
    val gradNorm: Float = 0f
)

/**
 * Controller untuk pelatihan Char-RNN on-device di Dispatchers.Default.
 * Terhubung ke dataset Room dan menyimpan bobot ke filesDir.
 */
class LocalNeuralTrainer(
    private val context: Context
) {
    private companion object {
        /** Batas norma gradien global. */
        const val MAX_GRAD_NORM = 1.0f
    }

    private val weightsFile = File(context.filesDir, "models/local_char_rnn_weights.json")
    private val bestCheckpointFile = File(context.filesDir, "models/local_char_rnn_best_checkpoint.json")

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
        if (weightsFile.exists()) {
            val loadedModel = CharRNNModel.fromFile(weightsFile)
            val hasWeights = if (loadedModel != null) {
                _model = loadedModel
                true
            } else {
                _model.loadFromFile(weightsFile)
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
     * Konfigurasi arsitektur neural & profil RAM.
     * PERHATIAN: mengganti dimensi membuat model BARU (bobot acak). Bobot lama dicadangkan ke
     * local_char_rnn_weights.prev.json sebelum ditimpa.
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
            if (weightsFile.exists() && weightsFile.length() > 0L) {
                try {
                    weightsFile.copyTo(File(weightsFile.parentFile, "local_char_rnn_weights.prev.json"), overwrite = true)
                } catch (e: Exception) {
                    Log.w("LocalNeuralTrainer", "Backup bobot lama gagal: ${e.message}")
                }
            }
            _model = CharRNNModel(
                vocab = _model.vocab,
                embedDim = embedDim,
                hiddenDim = hiddenDim
            )
            _model.saveToFile(weightsFile)
        }
        val (memUsed, memMax) = getRealJvmMemory()
        val estMb = CharRNNModel.estimateTrainingMemoryMb(_model.vocabSize, _model.embedDim, _model.hiddenDim, chunkSize)
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
                estimatedModelRamMb = estMb,
                isWeightsSaved = weightsFile.exists() && weightsFile.length() > 0L,
                statusMessage = "Arsitektur diatur ke hidden=$hiddenDim, embed=$embedDim ($ramProfileName) • estimasi RAM model+optimizer ${"%.1f".format(estMb)} MB"
            )
        }
    }

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

    /** Encode satu sampel menjadi sekuens token karakter: "prompt\ncompletion\n". */
    private fun encode(prompt: String, completion: String): IntArray =
        "$prompt\n$completion\n".map { _model.charToToken(it) }.toIntArray()

    /**
     * Pulihkan model dari berkas bobot utama; jika tidak ada/tidak valid, inisialisasi ulang model kosong
     * dengan vocab & dimensi yang sama. Dipakai setelah divergence tanpa checkpoint.
     * Return true jika bobot lama berhasil dimuat.
     */
    private fun restoreModelFromDiskOrReinit(): Boolean {
        val reloaded = CharRNNModel.fromFile(weightsFile)
        return if (reloaded != null) {
            _model = reloaded
            true
        } else {
            _model = CharRNNModel(vocab = _model.vocab, embedDim = _model.embedDim, hiddenDim = _model.hiddenDim)
            false
        }
    }

    /**
     * Loop latih utama.
     *
     * - Split train/val deterministik berbasis hash isi sampel (stabil saat data bertambah).
     * - Teks dipecah per chunk; hidden state DIBAWA antar chunk dalam satu sampel (truncated BPTT)
     *   dan semua transisi karakter terlatih (tidak ada yang terlewat di batas chunk).
     * - batchSize = jumlah chunk per satu update optimizer (gradient accumulation nyata).
     * - LR: warmup 5% lalu cosine decay ke 10%; dibatasi maxRecommendedLearningRate optimizer.
     * - Val loss = total NLL / total token pada SELURUH teks val; perplexity = exp(val loss).
     * - Akurasi = top-1 prediksi karakter berikutnya yang benar (bukan ambang loss).
     * - Early stopping + checkpoint terbaik; divergence (NaN/Inf) memulihkan checkpoint terbaik.
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

        val safeEpochs = epochs.coerceAtLeast(1)
        val safeChunk = chunkSize.coerceIn(8, 512)
        val safeBatch = batchSize.coerceIn(1, 64)
        val safePatience = earlyStoppingPatience.coerceAtLeast(1)
        val baseLr = learningRate.coerceIn(1e-5f, optimizerType.maxRecommendedLearningRate)

        // Perluas vocab jika dataset punya karakter baru, lalu mulai dengan state optimizer bersih.
        val allDatasetTexts = dataset.flatMap { listOf(it.prompt, it.completion) }
        _model = CharRNNModel.expandVocabIfNeeded(_model, allDatasetTexts)
        _model.resetOptimizerState()

        // Split deterministik berbasis hash isi (bukan 20% data terakhir di DB)
        val valCount = if (dataset.size >= 4) maxOf(1, (dataset.size * 0.2).roundToInt()) else 0
        val hasRealVal = valCount > 0
        val byHash = dataset.sortedBy { (it.prompt + "\u0001" + it.completion).hashCode() }
        val valSet = if (hasRealVal) byHash.take(valCount) else emptyList()
        val trainSet = if (hasRealVal) byHash.drop(valCount) else dataset

        val trainTok = trainSet.map { it to encode(it.prompt, it.completion) }
        val valTok = valSet.map { encode(it.prompt, it.completion) }

        // Jadwal LR
        var chunksPerEpoch = 0
        for ((_, t) in trainTok) {
            if (t.size >= 2) chunksPerEpoch += (t.size - 1 + safeChunk - 1) / safeChunk
        }
        val updatesPerEpoch = ((chunksPerEpoch + safeBatch - 1) / safeBatch).coerceAtLeast(1)
        val totalUpdates = updatesPerEpoch * safeEpochs
        val warmupUpdates = (totalUpdates * 0.05f).toInt().coerceIn(1, 200)
        fun lrAt(u: Int): Float {
            if (u < warmupUpdates) return baseLr * (u + 1) / warmupUpdates
            val denom = (totalUpdates - warmupUpdates).coerceAtLeast(1)
            val prog = ((u - warmupUpdates).toFloat() / denom).coerceIn(0f, 1f)
            val minLr = baseLr * 0.1f
            return minLr + 0.5f * (baseLr - minLr) * (1f + kotlin.math.cos(Math.PI * prog).toFloat())
        }

        // Anti-Gimmick check
        val preVerificationPassed = CharRNNModel.verifyRealTraining()

        val startTime = System.currentTimeMillis()
        var totalTokensProcessed = 0
        val logsList = mutableListOf<String>()
        fun addLog(line: String) {
            logsList.add(line)
            while (logsList.size > 120) logsList.removeAt(0)
        }
        val minDelta = 0.0005f
        val monitorName = if (hasRealVal) "Val Loss" else "Train Loss"

        val normBefore = _model.computeWOutL2Norm()
        val (initMemUsed, initMemMax) = getRealJvmMemory()
        val estMb = CharRNNModel.estimateTrainingMemoryMb(_model.vocabSize, _model.embedDim, _model.hiddenDim, safeChunk)

        addLog("Optimizer ${optimizerType.shortName} | LR ${"%.5f".format(baseLr)} (warmup $warmupUpdates update → cosine ke 10%) | batch $safeBatch chunk/update | chunk $safeChunk karakter")
        if (baseLr < learningRate - 1e-9f) {
            addLog("! LR diminta ${"%.5f".format(learningRate)} melebihi batas stabil ${optimizerType.shortName} (${"%.3f".format(optimizerType.maxRecommendedLearningRate)}); dipakai ${"%.5f".format(baseLr)}")
        }
        addLog("Params ${_model.countParameters()} | estimasi RAM model+optimizer ≈ ${"%.1f".format(estMb)} MB")
        if (hasRealVal) {
            addLog("Data: ${trainSet.size} latih / ${valSet.size} validasi (split deterministik)")
        } else {
            addLog("! Dataset < 4 sampel: TIDAK ada validation set. Early stopping & checkpoint memantau Train Loss.")
        }

        _progress.update {
            it.copy(
                isTraining = true,
                totalEpochs = safeEpochs,
                currentEpoch = 1,
                currentStep = 0,
                totalSteps = trainSet.size * safeEpochs,
                vocabSize = _model.vocabSize,
                hiddenDim = _model.hiddenDim,
                embedDim = _model.embedDim,
                totalParameters = _model.countParameters(),
                optimizerType = optimizerType,
                optimizerDisplayName = optimizerType.displayName,
                batchSize = safeBatch,
                chunkSize = safeChunk,
                earlyStoppingEnabled = earlyStoppingEnabled,
                earlyStoppingPatience = safePatience,
                earlyStoppingTriggered = false,
                isCheckpointRestored = false,
                memoryUsedMb = initMemUsed,
                memoryMaxMb = initMemMax,
                estimatedModelRamMb = estMb,
                hasValidationSet = hasRealVal,
                learningRate = lrAt(0),
                skippedUpdates = 0,
                divergenceDetected = false,
                valLoss = null,
                bestValLoss = null,
                perplexity = null,
                bestLoss = null,
                wOutL2NormBefore = normBefore,
                verificationPassed = preVerificationPassed,
                trainingLogs = logsList.toList(),
                statusMessage = "Memulai optimasi dengan ${optimizerType.shortName} & autograd..."
            )
        }

        var stepCounter = 0
        val totalSteps = trainSet.size * safeEpochs
        var firstLoss: Float? = null
        var s1Loss: Float? = null
        var s10Loss: Float? = null
        var s50Loss: Float? = null
        var s100Loss: Float? = null

        var latestLoss = 0f
        var emaLoss = 0f
        var emaReady = false
        var latestValLoss: Float? = null
        var latestPerplexity: Float? = null
        var latestGeneration = ""

        var bestTrainEpochLoss: Float? = null
        var bestMonitor: Float? = null
        var bestEpoch: Int? = null
        var patienceCounter = 0
        var earlyStoppingTriggered = false

        var diverged = false
        var pending = 0
        var updateCount = 0
        var currentLr = lrAt(0)
        var skippedConsecutive = 0

        fun flushUpdate() {
            if (pending <= 0) return
            currentLr = lrAt(updateCount)
            val ok = _model.applyAccumulated(currentLr, optimizerType, MAX_GRAD_NORM, pending)
            if (ok) {
                skippedConsecutive = 0
            } else {
                skippedConsecutive++
                if (skippedConsecutive >= 10) diverged = true
            }
            updateCount++
            pending = 0
        }

        try {
            epochLoop@ for (epoch in 1..safeEpochs) {
                // DataLoader: acak urutan sampel tiap epoch
                val epochSamples = if (trainTok.size > 1) trainTok.shuffled(kotlin.random.Random(1337 + epoch)) else trainTok
                var epochLossSum = 0.0
                var epochTokens = 0

                for ((sample, tokens) in epochSamples) {
                    ensureActive()
                    stepCounter++
                    totalTokensProcessed += tokens.size

                    // Hidden state mulai dari nol di awal tiap sampel, lalu dibawa antar chunk.
                    var h = FloatArray(_model.hiddenDim)
                    var s = 0
                    val n = tokens.size
                    while (s < n - 1) {
                        ensureActive()
                        val e = minOf(s + safeChunk, n - 1)
                        // Input [s, e), target [s+1, e+1): semua transisi tercakup.
                        val r = _model.accumulateGradients(
                            tokens.copyOfRange(s, e),
                            tokens.copyOfRange(s + 1, e + 1),
                            h
                        )
                        h = r.lastHidden
                        val chunkLoss = (r.lossSum / r.tokens.coerceAtLeast(1)).toFloat()
                        if (!chunkLoss.isFinite()) {
                            diverged = true
                            break
                        }
                        epochLossSum += r.lossSum
                        epochTokens += r.tokens
                        latestLoss = chunkLoss
                        emaLoss = if (emaReady) 0.98f * emaLoss + 0.02f * chunkLoss else chunkLoss
                        emaReady = true

                        if (firstLoss == null) firstLoss = chunkLoss
                        if (stepCounter == 1 && s1Loss == null) s1Loss = chunkLoss
                        if (stepCounter == 10 && s10Loss == null) s10Loss = chunkLoss
                        if (stepCounter == 50 && s50Loss == null) s50Loss = chunkLoss
                        if (stepCounter == 100 && s100Loss == null) s100Loss = chunkLoss

                        pending++
                        if (pending >= safeBatch) flushUpdate()
                        if (diverged) break
                        s = e
                    }
                    if (diverged) break@epochLoop

                    val (curMemUsed, curMemMax) = getRealJvmMemory()
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                    val currentTps = totalTokensProcessed / elapsedSec

                    if (stepCounter % 5 == 0 || stepCounter == 1 || stepCounter == totalSteps) {
                        val valLog = latestValLoss?.let { v -> " | Val: ${"%.4f".format(v)}" } ?: ""
                        addLog("[Step $stepCounter/$totalSteps] Ep $epoch - Loss(ema): ${"%.4f".format(emaLoss)}$valLog | LR ${"%.5f".format(currentLr)} | |g| ${"%.2f".format(_model.lastGradNorm)} | RAM ${"%.1f".format(curMemUsed)}MB | ${"%.1f".format(currentTps)} tok/s")
                    }

                    if (stepCounter % sampleEverySteps == 0 || stepCounter == 1) {
                        val samplePrompt = sample.prompt.take(15).ifBlank { "Halo " }
                        latestGeneration = _model.generate(samplePrompt, maxChars = 45, temperature = 0.6f)
                        Log.i("ANTI_GIMMICK", "[Step $stepCounter/$totalSteps] Loss(ema): $emaLoss | Gen: \"$latestGeneration\"")
                    }

                    _progress.update {
                        it.copy(
                            currentEpoch = epoch,
                            totalEpochs = safeEpochs,
                            currentStep = stepCounter,
                            totalSteps = totalSteps,
                            currentLoss = emaLoss,
                            initialLoss = firstLoss ?: 0f,
                            bestLoss = bestTrainEpochLoss,
                            valLoss = latestValLoss,
                            bestValLoss = if (hasRealVal) bestMonitor else null,
                            perplexity = latestPerplexity,
                            tokensPerSec = currentTps,
                            totalDurationMs = System.currentTimeMillis() - startTime,
                            memoryUsedMb = curMemUsed,
                            memoryMaxMb = curMemMax,
                            learningRate = currentLr,
                            gradNorm = _model.lastGradNorm,
                            skippedUpdates = _model.skippedUpdates,
                            trainingLogs = logsList.toList(),
                            step1Loss = s1Loss,
                            step10Loss = s10Loss,
                            step50Loss = s50Loss,
                            step100Loss = s100Loss,
                            sampleGeneration = latestGeneration,
                            statusMessage = "Epoch $epoch/$safeEpochs - Step $stepCounter: Loss = ${"%.4f".format(emaLoss)}${latestValLoss?.let { v -> " (Val: ${"%.4f".format(v)})" } ?: ""}"
                        )
                    }
                }

                // Sisa chunk yang belum mencapai ukuran batch ikut di-update di akhir epoch.
                flushUpdate()
                if (diverged) break@epochLoop

                val epochTrainLoss = if (epochTokens > 0) (epochLossSum / epochTokens).toFloat() else latestLoss
                val prevTrainBest = bestTrainEpochLoss
                if (prevTrainBest == null || epochTrainLoss < prevTrainBest) bestTrainEpochLoss = epochTrainLoss

                // Evaluasi validasi PENUH: seluruh teks, token-weighted
                var monitorLoss = epochTrainLoss
                if (hasRealVal) {
                    var vLoss = 0.0
                    var vTok = 0
                    for (vt in valTok) {
                        ensureActive()
                        val er = _model.evaluateSequence(vt, safeChunk)
                        vLoss += er.lossSum
                        vTok += er.tokens
                    }
                    if (vTok > 0) {
                        val vl = (vLoss / vTok).toFloat()
                        latestValLoss = vl
                        latestPerplexity = kotlin.math.exp(vl.toDouble()).toFloat()
                        monitorLoss = vl
                    }
                }
                if (!monitorLoss.isFinite()) {
                    diverged = true
                    break@epochLoop
                }

                // Checkpoint & early stopping
                val prevBest = bestMonitor
                if (prevBest == null || monitorLoss < prevBest - minDelta) {
                    val delta = if (prevBest != null) prevBest - monitorLoss else 0f
                    bestMonitor = monitorLoss
                    bestEpoch = epoch
                    patienceCounter = 0
                    _model.saveToFile(bestCheckpointFile)
                    val impStr = if (delta > 0f) " (turun ${"%.4f".format(delta)})" else ""
                    addLog("★ [Checkpoint Ep $epoch] Rekor $monitorName baru: ${"%.4f".format(monitorLoss)}$impStr -> Disimpan!")
                } else {
                    patienceCounter++
                    addLog("! [Patience Ep $epoch] $monitorName (${"%.4f".format(monitorLoss)}) tidak membaik (Best: ${"%.4f".format(prevBest)} di Ep $bestEpoch). Patience: $patienceCounter/$safePatience")
                    if (earlyStoppingEnabled && patienceCounter >= safePatience) {
                        earlyStoppingTriggered = true
                        addLog("🛑 [Early Stopping] Dihentikan di Epoch $epoch: $monitorName tidak membaik $safePatience epoch berturut-turut.")
                    }
                }

                val (epochMemUsed, epochMemMax) = getRealJvmMemory()
                val shownBestEpoch = bestEpoch
                val shownBest = bestMonitor
                _progress.update {
                    it.copy(
                        currentEpoch = epoch,
                        bestLoss = bestTrainEpochLoss,
                        valLoss = latestValLoss,
                        bestValLoss = if (hasRealVal) shownBest else null,
                        perplexity = latestPerplexity,
                        bestCheckpointEpoch = shownBestEpoch,
                        bestCheckpointValLoss = if (hasRealVal) shownBest else null,
                        earlyStoppingTriggered = earlyStoppingTriggered,
                        memoryUsedMb = epochMemUsed,
                        memoryMaxMb = epochMemMax,
                        trainingLogs = logsList.toList(),
                        statusMessage = if (earlyStoppingTriggered) {
                            "🛑 Early Stopping di Ep $epoch (patience $safePatience habis. Best: Ep $shownBestEpoch, $monitorName: ${"%.4f".format(shownBest ?: monitorLoss)})"
                        } else {
                            "Epoch $epoch/$safeEpochs selesai - Train: ${"%.4f".format(epochTrainLoss)}${latestValLoss?.let { v -> " | Val: ${"%.4f".format(v)}" } ?: ""}"
                        }
                    )
                }

                if (earlyStoppingTriggered) break@epochLoop
            }

            // ---- Divergence: pulihkan checkpoint terbaik (atau bobot lama) ----
            if (diverged) {
                _model.discardAccumulatedGradients()
                val be = bestEpoch
                val restored = be != null && _model.loadFromFile(bestCheckpointFile)
                if (restored) {
                    _model.saveToFile(weightsFile)
                } else {
                    restoreModelFromDiskOrReinit()
                }
                _model.resetOptimizerState()
                addLog("✗ [Divergence] Loss/gradien NaN atau Inf. ${if (restored) "Dipulihkan ke checkpoint terbaik Ep $be." else "Bobot dikembalikan ke kondisi sebelum latihan."} Turunkan learning rate.")
                val (dMem, dMax) = getRealJvmMemory()
                _progress.update {
                    it.copy(
                        isTraining = false,
                        divergenceDetected = true,
                        isCheckpointRestored = restored,
                        vocabSize = _model.vocabSize,
                        totalParameters = _model.countParameters(),
                        skippedUpdates = _model.skippedUpdates,
                        memoryUsedMb = dMem,
                        memoryMaxMb = dMax,
                        trainingLogs = logsList.toList(),
                        totalDurationMs = System.currentTimeMillis() - startTime,
                        statusMessage = if (restored) {
                            "⚠ Pelatihan meledak (NaN/Inf) di step $stepCounter. Dipulihkan ke Ep $be. Coba LR lebih kecil."
                        } else {
                            "✗ Pelatihan meledak (NaN/Inf) di step $stepCounter. Bobot tidak diubah. Turunkan learning rate."
                        }
                    )
                }
                return@withContext restored
            }

            // ---- Restore bobot terbaik (hanya jika checkpoint dibuat oleh run INI) ----
            var isRestored = false
            val finalBestEpoch = bestEpoch
            if (finalBestEpoch != null) {
                if (_model.loadFromFile(bestCheckpointFile)) {
                    isRestored = true
                    _model.saveToFile(weightsFile)
                    addLog("✓ [Restore Checkpoint] Model dipulihkan ke bobot terbaik Ep $finalBestEpoch ($monitorName: ${"%.4f".format(bestMonitor ?: 0f)})")
                }
            }
            if (!isRestored) {
                _model.saveToFile(weightsFile)
            }
            _model.resetOptimizerState()

            val normAfter = _model.computeWOutL2Norm()

            // Konversi tensor ExecuTorch (in-memory); aman jika library native tidak ada.
            val tensorsOk = try {
                val tensors = _model.toExecuTorchTensors()
                Log.i("ANTI_GIMMICK", "ExecuTorch Tensors generated: ${tensors.keys.joinToString()}")
                tensors.isNotEmpty()
            } catch (t: Throwable) {
                Log.w("LocalNeuralTrainer", "Konversi tensor ExecuTorch gagal: ${t.message}")
                false
            }

            // Akurasi top-1 NYATA (prediksi karakter berikutnya) pada val set; tanpa val set: pada sampel latih.
            val accSets: List<IntArray> = if (hasRealVal) valTok else trainTok.take(20).map { it.second }
            var accCorrect = 0
            var accTotal = 0
            for (t in accSets) {
                ensureActive()
                val er = _model.evaluateSequence(t, safeChunk)
                accCorrect += er.correct
                accTotal += er.tokens
            }
            val benchmarkAcc = if (accTotal > 0) (accCorrect * 100f / accTotal) else 0f
            val accLabel = if (hasRealVal) "val" else "latih (tanpa val set)"

            val finalElapsedMs = System.currentTimeMillis() - startTime
            val (finMemUsed, finMemMax) = getRealJvmMemory()
            val finishStatus = if (earlyStoppingTriggered) "Early Stopping dihentikan dini" else "Pelatihan tuntas"
            addLog("✓ [$finishStatus] ${finalElapsedMs}ms. Best $monitorName: ${"%.4f".format(bestMonitor ?: latestLoss)} (Ep $bestEpoch) | Akurasi top-1 $accLabel: ${"%.1f".format(benchmarkAcc)}%")

            _progress.update {
                it.copy(
                    isTraining = false,
                    bestLoss = bestTrainEpochLoss,
                    valLoss = latestValLoss,
                    bestValLoss = if (hasRealVal) bestMonitor else null,
                    perplexity = latestPerplexity,
                    benchmarkScore = benchmarkAcc,
                    totalDurationMs = finalElapsedMs,
                    memoryUsedMb = finMemUsed,
                    memoryMaxMb = finMemMax,
                    earlyStoppingTriggered = earlyStoppingTriggered,
                    bestCheckpointEpoch = bestEpoch,
                    bestCheckpointValLoss = if (hasRealVal) bestMonitor else null,
                    isCheckpointRestored = isRestored,
                    skippedUpdates = _model.skippedUpdates,
                    trainingLogs = logsList.toList(),
                    wOutL2NormAfter = normAfter,
                    isWeightsSaved = true,
                    executorchTensorsExported = tensorsOk,
                    statusMessage = if (earlyStoppingTriggered) {
                        "✓ Early Stopping selesai! Dipulihkan ke Ep $bestEpoch ($monitorName: ${"%.4f".format(bestMonitor ?: latestLoss)}) • Bobot tersimpan (${weightsFile.length() / 1024} KB)."
                    } else {
                        "✓ Pelatihan selesai! Best $monitorName: ${"%.4f".format(bestMonitor ?: latestLoss)} (Ep $bestEpoch) • Bobot tersimpan (${weightsFile.length() / 1024} KB)."
                    }
                )
            }
            return@withContext true
        } catch (e: CancellationException) {
            // Jangan ditelan: simpan bobot terbaik sejauh ini, lalu teruskan pembatalan.
            _model.discardAccumulatedGradients()
            val be = bestEpoch
            var saved = false
            try {
                if (be != null && _model.loadFromFile(bestCheckpointFile)) {
                    _model.saveToFile(weightsFile)
                    saved = true
                }
            } catch (_: Exception) {
            }
            _model.resetOptimizerState()
            _progress.update {
                it.copy(
                    isTraining = false,
                    isWeightsSaved = weightsFile.exists() && weightsFile.length() > 0L,
                    isCheckpointRestored = saved,
                    statusMessage = if (saved) "Pelatihan dihentikan. Bobot terbaik (Ep $be) dipulihkan & disimpan." else "Pelatihan dihentikan sebelum ada checkpoint."
                )
            }
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e("LocalNeuralTrainer", "OOM saat latih: ${e.message}")
            _model.discardAccumulatedGradients()
            _progress.update {
                it.copy(
                    isTraining = false,
                    statusMessage = "RAM tidak cukup. Pilih profil lebih kecil (hidden/batch/chunk lebih rendah)."
                )
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e("LocalNeuralTrainer", "Error during training: ${e.message}", e)
            _model.discardAccumulatedGradients()
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
     * Pelatihan inkremental langsung ke bobot model utama (tanpa modul adapter).
     * Satu chunk = satu update; hidden state dibawa antar chunk; akurasi = top-1 nyata
     * pada epoch terakhir (diukur saat latih, bukan val set).
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

        val allDatasetTexts = dataset.flatMap { listOf(it.prompt, it.completion) }
        _model = CharRNNModel.expandVocabIfNeeded(_model, allDatasetTexts)

        try {
            val safeEpochs = epochs.coerceIn(1, 500)
            val effectiveLr = learningRate.coerceIn(0.00001f, 0.1f)
            val safeChunk = chunkSize.coerceIn(8, 512)
            _model.resetOptimizerState()
            val normBefore = _model.computeWOutL2Norm()

            // Panjang konteks dibatasi 200 (prompt) + 300 (completion) karakter
            val encoded = dataset.map { sample ->
                Pair(sample.prompt, encode(sample.prompt.take(200), sample.completion.take(300)))
            }

            var firstLoss: Float? = null
            var lastLoss = 0f
            var globalStep = 0
            val totalSteps = dataset.size * safeEpochs
            var lastEpochCorrect = 0
            var lastEpochTokens = 0
            var diverged = false
            var skippedConsecutive = 0

            epochLoop@ for (epoch in 1..safeEpochs) {
                lastEpochCorrect = 0
                lastEpochTokens = 0
                for ((promptText, tokens) in encoded) {
                    ensureActive()
                    globalStep++
                    val label = if (safeEpochs > 1) {
                        "[Ep $epoch/$safeEpochs] ${promptText.take(25).ifBlank { "Sampel" }}"
                    } else {
                        promptText.take(30).ifBlank { "Sampel #$globalStep" }
                    }

                    if (tokens.size < 2) {
                        onProgress(globalStep, totalSteps, label, lastLoss)
                        continue
                    }

                    var h = FloatArray(_model.hiddenDim)
                    var s = 0
                    var sampleLossSum = 0.0
                    var sampleTokens = 0
                    while (s < tokens.size - 1) {
                        ensureActive()
                        val e = minOf(s + safeChunk, tokens.size - 1)
                        val r = _model.accumulateGradients(
                            tokens.copyOfRange(s, e),
                            tokens.copyOfRange(s + 1, e + 1),
                            h
                        )
                        h = r.lastHidden
                        val cl = (r.lossSum / r.tokens.coerceAtLeast(1)).toFloat()
                        if (!cl.isFinite()) {
                            diverged = true
                            break
                        }
                        val ok = _model.applyAccumulated(effectiveLr, optimizerType, MAX_GRAD_NORM, 1)
                        if (ok) {
                            skippedConsecutive = 0
                        } else {
                            skippedConsecutive++
                            if (skippedConsecutive >= 10) {
                                diverged = true
                                break
                            }
                        }
                        sampleLossSum += r.lossSum
                        sampleTokens += r.tokens
                        lastEpochCorrect += r.correct
                        lastEpochTokens += r.tokens
                        s = e
                    }
                    if (diverged) break@epochLoop

                    if (sampleTokens == 0) {
                        onProgress(globalStep, totalSteps, label, lastLoss)
                        continue
                    }
                    val sampleLoss = (sampleLossSum / sampleTokens).toFloat()
                    if (firstLoss == null) firstLoss = sampleLoss
                    lastLoss = sampleLoss
                    onProgress(globalStep, totalSteps, label, sampleLoss)

                    // Beri nafas coroutine agar GC bisa jalan
                    yield()
                }
            }

            if (diverged) {
                _model.discardAccumulatedGradients()
                val restored = restoreModelFromDiskOrReinit()
                _model.resetOptimizerState()
                _progress.update {
                    it.copy(
                        divergenceDetected = true,
                        vocabSize = _model.vocabSize,
                        totalParameters = _model.countParameters(),
                        statusMessage = "✗ Pelatihan inkremental meledak (NaN/Inf). ${if (restored) "Bobot lama dipulihkan." else "Model diinisialisasi ulang."} Turunkan learning rate."
                    )
                }
                return@withContext TrainingBenchmarkResult(
                    adapterId = "BASE-MODEL-DIRECT",
                    initialLoss = firstLoss ?: 0f,
                    finalLoss = 0f,
                    benchmarkScore = 0f,
                    durationMs = System.currentTimeMillis() - startTime,
                    isAccepted = false,
                    summary = "Pelatihan dihentikan: loss/gradien NaN atau Inf. ${if (restored) "Bobot lama dipulihkan." else "Model diinisialisasi ulang."} Turunkan learning rate (sekarang ${"%.5f".format(effectiveLr)})."
                )
            }

            _model.saveToFile(weightsFile)
            _model.resetOptimizerState()
            val normAfter = _model.computeWOutL2Norm()
            val durationMs = System.currentTimeMillis() - startTime

            val benchAcc = if (lastEpochTokens > 0) (lastEpochCorrect * 100f / lastEpochTokens) else 0f
            val (incMemUsed, incMemMax) = getRealJvmMemory()

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
                    divergenceDetected = false,
                    statusMessage = "✓ Bobot model utama berhasil diperbarui ($totalSteps langkah, $safeEpochs epoch)!"
                )
            }

            TrainingBenchmarkResult(
                adapterId = "BASE-MODEL-DIRECT",
                initialLoss = firstLoss ?: 0f,
                finalLoss = lastLoss,
                benchmarkScore = benchAcc,
                durationMs = durationMs,
                isAccepted = lastLoss.isFinite(),
                summary = "Bobot model utama diperbarui inkremental ($safeEpochs epoch, ${dataset.size} sampel, total $totalSteps step). Loss: ${"%.4f".format(firstLoss ?: 0f)} -> ${"%.4f".format(lastLoss)}. Akurasi top-1 (epoch terakhir, diukur saat latih): ${"%.1f".format(benchAcc)}%."
            )
        } catch (e: CancellationException) {
            _model.discardAccumulatedGradients()
            _model.resetOptimizerState()
            throw e
        } catch (e: Exception) {
            Log.e("LocalNeuralTrainer", "Error during incremental base training: ${e.message}", e)
            _model.discardAccumulatedGradients()
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

    fun runTrainedInference(prompt: String, maxTokens: Int = 100): String {
        val clean = prompt.trim()
        val text = _model.generate(if (clean.isEmpty()) "Halo" else clean, maxChars = maxTokens, temperature = 0.7f)
        _trainedInferenceOutput.value = text
        return text
    }

    fun exportWeightsJson(): String {
        return if (weightsFile.exists()) weightsFile.readText() else ""
    }

    /**
     * Impor bobot dari JSON. Divalidasi dulu di berkas sementara: bobot yang sedang dipakai
     * TIDAK ditimpa jika JSON tidak valid. Dimensi/vocab dari file impor otomatis dipakai.
     */
    fun importWeightsJson(jsonString: String): Boolean {
        return try {
            weightsFile.parentFile?.mkdirs()
            val tmp = File(weightsFile.parentFile, "local_char_rnn_import.tmp")
            tmp.writeText(jsonString)
            val imported = CharRNNModel.fromFile(tmp)
            if (imported == null) {
                tmp.delete()
                _progress.update { it.copy(statusMessage = "Impor ditolak: JSON bobot tidak valid. Bobot lama tidak diubah.") }
                false
            } else {
                _model = imported
                tmp.copyTo(weightsFile, overwrite = true)
                tmp.delete()
                val prof = TrainingRamProfile.findProfileByDimensions(_model.embedDim, _model.hiddenDim)
                _progress.update {
                    it.copy(
                        isWeightsSaved = true,
                        vocabSize = _model.vocabSize,
                        hiddenDim = _model.hiddenDim,
                        embedDim = _model.embedDim,
                        totalParameters = _model.countParameters(),
                        ramProfileName = prof?.name ?: "Kustom (${_model.hiddenDim}h)",
                        statusMessage = "Bobot berhasil diimpor & dimuat ke model lokal!"
                    )
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun hasTrainedWeights(): Boolean {
        return weightsFile.exists() && weightsFile.length() > 0L
    }

    fun inspectExecuTorchEValueShape(prompt: String): String {
        val token = if (prompt.isNotEmpty()) _model.charToToken(prompt.first()) else 0
        val dummyHidden = FloatArray(_model.hiddenDim)
        val (eValueOutput, _) = _model.forwardEValue(token, dummyHidden)
        val tensor = eValueOutput.toTensor()
        return "ExecuTorch EValue output shape: ${tensor.shape().joinToString("x")}"
    }

    @Deprecated("Gunakan inspectExecuTorchEValueShape", ReplaceWith("inspectExecuTorchEValueShape(prompt)"))
    fun runExecuTorchEValueInference(prompt: String): String = inspectExecuTorchEValueShape(prompt)

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
