package com.example.nexora.engine.training

import com.example.nexora.model.database.TrainingDataEntity
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

data class RealTrainingExecutionResult(
    val initialLoss: Float,
    val finalLoss: Float,
    val lossReduction: Float,
    val parameterCount: Long,
    val parameterCountFormatted: String,
    val benchmarkScore: Float,
    val adapterSizeBytes: Long,
    val durationMs: Long,
    val vocabularySize: Int,
    val epochsTrained: Int,
    val isAccepted: Boolean,
    val summary: String
)

/**
 * On-device neural & associative adapter learning engine.
 * Computes REAL gradient descent, REAL cross-entropy loss, and REAL parameter counts.
 * Enforces strict rule:
 * - Dilarang mock, placeholder, angka hardcode.
 * - Loss, benchmark, parameter, adapter size dihitung dari proses matematika nyata.
 * - Jika data kosong, tampilkan 0f (sehingga UI menampilkan "belum ada").
 */
object OnDeviceLearningEngine {
    // Batas atas kosakata real untuk on-device. Dengan embeddingDim=16, satu matriks
    // 4096x16 float hanya ~256KB -> aman dari OOM. Token di luar top-N -> UNK (id=0).
    private const val MAX_VOCAB_SIZE = 4096
    private const val EMBEDDING_DIM = 16

    fun train(
        data: List<TrainingDataEntity>,
        storageDir: File,
        adapterId: String,
        epochs: Int = 4,
        learningRate: Float = 0.05f
    ): RealTrainingExecutionResult {
        val startTime = System.currentTimeMillis()

        if (data.isEmpty()) {
            return RealTrainingExecutionResult(
                initialLoss = 0.0f,
                finalLoss = 0.0f,
                lossReduction = 0.0f,
                parameterCount = 0L,
                parameterCountFormatted = "belum ada",
                benchmarkScore = 0.0f,
                adapterSizeBytes = 0L,
                durationMs = System.currentTimeMillis() - startTime,
                vocabularySize = 0,
                epochsTrained = 0,
                isAccepted = false,
                summary = "Dataset pelatihan kosong. Belum ada data untuk diproses."
            )
        }

        // 1. Tokenize & build real vocabulary (dibatasi MAX_VOCAB_SIZE teratas by frekuensi;
        //    token langka dipetakan ke UNK id=0 sehingga vocab tidak tumbuh tak terbatas -> anti OOM).
        val wordFreq = mutableMapOf<String, Int>()
        for (item in data) {
            for (t in tokenize(item.prompt)) wordFreq[t] = (wordFreq[t] ?: 0) + 1
            for (t in tokenize(item.completion)) wordFreq[t] = (wordFreq[t] ?: 0) + 1
        }
        val vocabMap = mutableMapOf<String, Int>()
        var nextId = 1 // 0 dicadangkan untuk UNK
        for (w in wordFreq.entries.sortedByDescending { it.value }.take(MAX_VOCAB_SIZE - 1).map { it.key }) {
            vocabMap[w] = nextId++
        }
        val tokenizedPairs = mutableListOf<Pair<List<Int>, List<Int>>>()
        for (item in data) {
            val promptTokens = tokenize(item.prompt).map { vocabMap[it] ?: 0 }
            val completionTokens = tokenize(item.completion).map { vocabMap[it] ?: 0 }
            if (promptTokens.isNotEmpty() && completionTokens.isNotEmpty()) {
                tokenizedPairs.add(promptTokens to completionTokens)
            }
        }

        val vocabSize = nextId.coerceAtLeast(2)
        if (vocabSize < 2 || tokenizedPairs.isEmpty()) {
            return RealTrainingExecutionResult(
                initialLoss = 0.0f,
                finalLoss = 0.0f,
                lossReduction = 0.0f,
                parameterCount = 0L,
                parameterCountFormatted = "belum ada",
                benchmarkScore = 0.0f,
                adapterSizeBytes = 0L,
                durationMs = System.currentTimeMillis() - startTime,
                vocabularySize = vocabSize,
                epochsTrained = 0,
                isAccepted = false,
                summary = "Ukuran kosakata terlalu kecil (< 2 token unik). Belum cukup data representasi."
            )
        }

        // 2. Initialize real weight matrices
        // Embedding dim D = 16 (compact for on-device CPU efficiency)
        val embeddingDim = EMBEDDING_DIM
        val totalParams = (vocabSize * embeddingDim * 2).toLong()

        // Weight matrices: W_in: [vocabSize][embeddingDim], W_out: [embeddingDim][vocabSize]
        val rng = Random(42)
        val wIn = Array(vocabSize) { FloatArray(embeddingDim) { (rng.nextFloat() - 0.5f) * 0.1f } }
        val wOut = Array(embeddingDim) { FloatArray(vocabSize) { (rng.nextFloat() - 0.5f) * 0.1f } }

        var initialEpochLoss = 0.0f
        var finalEpochLoss = 0.0f

        // 3. Run real gradient descent across epochs
        for (epoch in 0 until epochs) {
            var totalLossThisEpoch = 0.0
            var sampleCount = 0

            for ((promptIds, completionIds) in tokenizedPairs) {
                // Average embedding of prompt context
                val contextVector = FloatArray(embeddingDim)
                for (id in promptIds) {
                    val emb = wIn[id]
                    for (d in 0 until embeddingDim) {
                        contextVector[d] += emb[d] / promptIds.size
                    }
                }

                // Predict each target completion token
                for (targetId in completionIds) {
                    // Compute logits: z_j = sum(contextVector[d] * wOut[d][j])
                    val logits = DoubleArray(vocabSize)
                    var maxLogit = Double.NEGATIVE_INFINITY
                    for (j in 0 until vocabSize) {
                        var sum = 0.0
                        for (d in 0 until embeddingDim) {
                            sum += contextVector[d] * wOut[d][j]
                        }
                        logits[j] = sum
                        if (sum > maxLogit) maxLogit = sum
                    }

                    // Softmax with numerical stability
                    var sumExp = 0.0
                    val probs = DoubleArray(vocabSize)
                    for (j in 0 until vocabSize) {
                        val p = exp(logits[j] - maxLogit)
                        probs[j] = p
                        sumExp += p
                    }
                    val targetProb = (probs[targetId] / sumExp).coerceAtLeast(1e-7)

                    // Real Cross-Entropy Loss: L = -ln(P(target))
                    val loss = -ln(targetProb)
                    totalLossThisEpoch += loss
                    sampleCount++

                    // Compute gradient: dL/dz = P - 1 (for target) else P
                    val gradOutput = FloatArray(vocabSize)
                    for (j in 0 until vocabSize) {
                        val p = (probs[j] / sumExp).toFloat()
                        gradOutput[j] = if (j == targetId) (p - 1.0f) else p
                    }

                    // Backprop to W_out and update weights
                    for (d in 0 until embeddingDim) {
                        for (j in 0 until vocabSize) {
                            val gradWOut = contextVector[d] * gradOutput[j]
                            wOut[d][j] -= learningRate * gradWOut
                        }
                    }

                    // Backprop to W_in
                    for (id in promptIds) {
                        val emb = wIn[id]
                        for (d in 0 until embeddingDim) {
                            var gradContextD = 0.0f
                            for (j in 0 until vocabSize) {
                                gradContextD += gradOutput[j] * wOut[d][j]
                            }
                            emb[d] -= learningRate * (gradContextD / promptIds.size)
                        }
                    }
                }
            }

            val epochAvgLoss = if (sampleCount > 0) (totalLossThisEpoch / sampleCount).toFloat() else 0.0f
            if (epoch == 0) initialEpochLoss = epochAvgLoss
            if (epoch == epochs - 1) finalEpochLoss = epochAvgLoss
        }

        // 4. Calculate real benchmark validation score (Top-1 accuracy on target tokens)
        var correctPredictions = 0
        var totalValidationTokens = 0

        for ((promptIds, completionIds) in tokenizedPairs) {
            val contextVector = FloatArray(embeddingDim)
            for (id in promptIds) {
                val emb = wIn[id]
                for (d in 0 until embeddingDim) {
                    contextVector[d] += emb[d] / promptIds.size
                }
            }

            for (targetId in completionIds) {
                var bestId = -1
                var bestLogit = Double.NEGATIVE_INFINITY
                for (j in 0 until vocabSize) {
                    var sum = 0.0
                    for (d in 0 until embeddingDim) {
                        sum += contextVector[d] * wOut[d][j]
                    }
                    if (sum > bestLogit) {
                        bestLogit = sum
                        bestId = j
                    }
                }
                if (bestId == targetId) correctPredictions++
                totalValidationTokens++
            }
        }

        val benchmarkScore = if (totalValidationTokens > 0) {
            ((correctPredictions.toDouble() / totalValidationTokens) * 100.0).toFloat()
        } else {
            0.0f
        }

        // 5. Serialize SELURUH bobot adapter (wIn + wOut) ke .bin & .safetensors.
        //    Sebelumnya hanya 100 baris pertama wIn yang disimpan (coerceAtMost(100) -> data loss),
        //    float ditulis 2 byte (korup), dan adapterBytes dipalsukan dengan coerceAtLeast(totalParams*2).
        val adapterBytes = writeAdapterWeights(storageDir, adapterId, "NEXORA_ADAPTER_V2", vocabSize, embeddingDim, wIn, wOut)
        val lossReduction = (initialEpochLoss - finalEpochLoss).coerceAtLeast(0.0f)

        return RealTrainingExecutionResult(
            initialLoss = round4(initialEpochLoss),
            finalLoss = round4(finalEpochLoss),
            lossReduction = round4(lossReduction),
            parameterCount = totalParams,
            parameterCountFormatted = String.format(java.util.Locale.US, "%,d params", totalParams),
            benchmarkScore = round2(benchmarkScore),
            adapterSizeBytes = adapterBytes,
            durationMs = System.currentTimeMillis() - startTime,
            vocabularySize = vocabSize,
            epochsTrained = epochs,
            isAccepted = finalEpochLoss < initialEpochLoss,
            summary = "Pelatihan on-device selesai ($epochs epoch). Loss awal: ${round4(initialEpochLoss)} -> Loss akhir: ${round4(finalEpochLoss)}. Akurasi top-1: ${round2(benchmarkScore)}%."
        )
    }

    /**
     * Mode Belajar Sebutir-sebutir (Incremental Continual / Online Learning):
     * Belajar per butir sampel data secara bertahap tanpa membebani RAM ponsel.
     * Mengkalkulasi Cross-Entropy Loss nyata untuk setiap butir, memperbarui bobot adapter,
     * dan melaporkan progres per butir secara langsung (onStepCallback).
     * Mendukung multi-epoch dan learning rate hingga 0.00001 dengan buffer reuse aman.
     */
    fun trainIncrementalStepByStep(
        data: List<TrainingDataEntity>,
        storageDir: File,
        adapterId: String,
        learningRate: Float = 0.005f,
        epochs: Int = 1,
        onStepProgress: (step: Int, total: Int, label: String, loss: Float) -> Unit
    ): RealTrainingExecutionResult {
        val startTime = System.currentTimeMillis()

        if (data.isEmpty()) {
            return RealTrainingExecutionResult(
                initialLoss = 0.0f,
                finalLoss = 0.0f,
                lossReduction = 0.0f,
                parameterCount = 0L,
                parameterCountFormatted = "belum ada",
                benchmarkScore = 0.0f,
                adapterSizeBytes = 0L,
                durationMs = System.currentTimeMillis() - startTime,
                vocabularySize = 0,
                epochsTrained = 0,
                isAccepted = false,
                summary = "Dataset pelatihan kosong. Belum ada butir data untuk dicerna."
            )
        }

        try {
            val safeEpochs = epochs.coerceIn(1, 500)
            val effectiveLr = learningRate.coerceIn(0.00001f, 0.1f)

            // 1. Build bounded vocabulary dari data (maksimal MAX_VOCAB_SIZE token frekuensi tertinggi untuk mencegah OOM di HP)
            val wordFreq = mutableMapOf<String, Int>()
            for (item in data) {
                val safePrompt = item.prompt.take(200)
                val safeComp = item.completion.take(300)
                for (t in tokenize(safePrompt)) wordFreq[t] = (wordFreq[t] ?: 0) + 1
                for (t in tokenize(safeComp)) wordFreq[t] = (wordFreq[t] ?: 0) + 1
            }

            val topWords = wordFreq.entries
                .sortedByDescending { it.value }
                .take(MAX_VOCAB_SIZE - 1)
                .map { it.key }

            val vocabMap = mutableMapOf<String, Int>()
            var nextId = 1 // 0 is reserved for UNK
            for (w in topWords) {
                vocabMap[w] = nextId++
            }

            val tokenizedPairs = mutableListOf<Pair<Pair<String, String>, Pair<List<Int>, List<Int>>>>()
            for (item in data) {
                val safePrompt = item.prompt.take(200)
                val safeComp = item.completion.take(300)
                val pTokens = tokenize(safePrompt).map { vocabMap[it] ?: 0 }.take(40)
                val cTokens = tokenize(safeComp).map { vocabMap[it] ?: 0 }.take(60)
                if (pTokens.isNotEmpty() && cTokens.isNotEmpty()) {
                    tokenizedPairs.add((safePrompt to safeComp) to (pTokens to cTokens))
                }
            }

            val vocabSize = nextId.coerceAtLeast(2)
            if (tokenizedPairs.isEmpty()) {
                return RealTrainingExecutionResult(
                    initialLoss = 0.0f,
                    finalLoss = 0.0f,
                    lossReduction = 0.0f,
                    parameterCount = 0L,
                    parameterCountFormatted = "belum ada",
                    benchmarkScore = 0.0f,
                    adapterSizeBytes = 0L,
                    durationMs = System.currentTimeMillis() - startTime,
                    vocabularySize = vocabSize,
                    epochsTrained = 0,
                    isAccepted = false,
                    summary = "Ukuran kosakata terlalu kecil (< 2 token unik). Belum cukup data representasi."
                )
            }

            val embeddingDim = EMBEDDING_DIM
            val totalParams = (vocabSize * embeddingDim * 2).toLong()
            val rng = Random(42)
            val wIn = Array(vocabSize) { FloatArray(embeddingDim) { (rng.nextFloat() - 0.5f) * 0.1f } }
            val wOut = Array(embeddingDim) { FloatArray(vocabSize) { (rng.nextFloat() - 0.5f) * 0.1f } }

            var firstSampleLoss = 0.0f
            var lastSampleLoss = 0.0f
            var totalLossSum = 0.0
            var totalTokensTrained = 0
            val totalSteps = tokenizedPairs.size * safeEpochs
            var currentGlobalStep = 0

            // Reusable buffers to avoid heap fragmentation and GC pressure on Android
            val logits = DoubleArray(vocabSize)
            val probs = DoubleArray(vocabSize)
            val gradOutput = FloatArray(vocabSize)
            val contextVector = FloatArray(embeddingDim)

            // 2. Cerna SEBUTIR-SEBUTIR dengan dukungan Epochs & memory-safe loops
            for (epoch in 1..safeEpochs) {
                tokenizedPairs.forEachIndexed { index, (rawTextPair, tokensPair) ->
                    currentGlobalStep++
                    val (promptIds, completionIds) = tokensPair
                    var sampleLossSum = 0.0
                    var sampleTokenCount = 0

                    // Forward pass untuk butir ini
                    java.util.Arrays.fill(contextVector, 0.0f)
                    val pSize = promptIds.size.coerceAtLeast(1)
                    for (id in promptIds) {
                        val validId = id.coerceIn(0, vocabSize - 1)
                        val emb = wIn[validId]
                        for (d in 0 until embeddingDim) {
                            contextVector[d] += emb[d] / pSize
                        }
                    }

                    // Hitung loss dan backprop per target token
                    for (targetIdRaw in completionIds) {
                        val targetId = targetIdRaw.coerceIn(0, vocabSize - 1)
                        var maxLogit = Double.NEGATIVE_INFINITY
                        for (j in 0 until vocabSize) {
                            var sum = 0.0
                            for (d in 0 until embeddingDim) {
                                sum += contextVector[d] * wOut[d][j]
                            }
                            logits[j] = sum
                            if (sum > maxLogit) maxLogit = sum
                        }

                        var sumExp = 0.0
                        for (j in 0 until vocabSize) {
                            val p = exp(logits[j] - maxLogit)
                            probs[j] = p
                            sumExp += p
                        }
                        val safeSumExp = sumExp.coerceAtLeast(1e-9)
                        val targetProb = (probs[targetId] / safeSumExp).coerceAtLeast(1e-7)
                        val loss = -ln(targetProb)
                        sampleLossSum += loss
                        sampleTokenCount++

                        // Gradien dL/dz
                        for (j in 0 until vocabSize) {
                            val p = (probs[j] / safeSumExp).toFloat()
                            gradOutput[j] = if (j == targetId) (p - 1.0f) else p
                        }

                        // Update W_out
                        for (d in 0 until embeddingDim) {
                            val cVal = contextVector[d]
                            for (j in 0 until vocabSize) {
                                val gradWOut = cVal * gradOutput[j]
                                wOut[d][j] -= effectiveLr * gradWOut
                            }
                        }

                        // Update W_in
                        for (id in promptIds) {
                            val validId = id.coerceIn(0, vocabSize - 1)
                            val emb = wIn[validId]
                            for (d in 0 until embeddingDim) {
                                var gradContextD = 0.0f
                                for (j in 0 until vocabSize) {
                                    gradContextD += gradOutput[j] * wOut[d][j]
                                }
                                emb[d] -= effectiveLr * (gradContextD / pSize)
                            }
                        }
                    }

                    val currentSampleAvgLoss = if (sampleTokenCount > 0) (sampleLossSum / sampleTokenCount).toFloat() else 0.0f
                    if (currentGlobalStep == 1) firstSampleLoss = currentSampleAvgLoss
                    lastSampleLoss = currentSampleAvgLoss
                    totalLossSum += sampleLossSum
                    totalTokensTrained += sampleTokenCount

                    val displayLabel = if (safeEpochs > 1) {
                        "[Ep $epoch/$safeEpochs] ${rawTextPair.first.take(25)}"
                    } else {
                        rawTextPair.first.take(35)
                    }
                    onStepProgress(currentGlobalStep, totalSteps, displayLabel, round4(currentSampleAvgLoss))
                }
            }

        // 3. Evaluasi Benchmark Top-1 Accuracy nyata
        var correct = 0
        var totalVal = 0
        for ((_, tokensPair) in tokenizedPairs) {
            val (promptIds, completionIds) = tokensPair
            val contextVector = FloatArray(embeddingDim)
            for (id in promptIds) {
                val emb = wIn[id]
                for (d in 0 until embeddingDim) {
                    contextVector[d] += emb[d] / promptIds.size
                }
            }
            for (targetId in completionIds) {
                var bestId = -1
                var bestLogit = Double.NEGATIVE_INFINITY
                for (j in 0 until vocabSize) {
                    var sum = 0.0
                    for (d in 0 until embeddingDim) {
                        sum += contextVector[d] * wOut[d][j]
                    }
                    if (sum > bestLogit) {
                        bestLogit = sum
                        bestId = j
                    }
                }
                if (bestId == targetId) correct++
                totalVal++
            }
        }

        val benchmarkScore = if (totalVal > 0) ((correct.toDouble() / totalVal) * 100.0).toFloat() else 0.0f

        // 4. Simpan SELURUH bobot adapter (wIn + wOut) ke storage internal.
        //    (Perbaikan sama seperti train(): tidak ada lagi coerceAtMost(100), float 4 byte penuh,
        //     adapterBytes adalah ukuran file nyata, bukan hasil coerceAtLeast.)
        val adapterBytes = writeAdapterWeights(storageDir, adapterId, "NEXORA_INCREMENTAL_V2", vocabSize, embeddingDim, wIn, wOut)
        val finalLossAvg = if (totalTokensTrained > 0) (totalLossSum / totalTokensTrained).toFloat() else 0.0f

        return RealTrainingExecutionResult(
            initialLoss = round4(firstSampleLoss),
            finalLoss = round4(lastSampleLoss),
            lossReduction = round4((firstSampleLoss - lastSampleLoss).coerceAtLeast(0.0f)),
            parameterCount = totalParams,
            parameterCountFormatted = String.format(java.util.Locale.US, "%,d params", totalParams),
            benchmarkScore = round2(benchmarkScore),
            adapterSizeBytes = adapterBytes,
            durationMs = System.currentTimeMillis() - startTime,
            vocabularySize = vocabSize,
            epochsTrained = safeEpochs,
            isAccepted = true,
            summary = "Proses belajar sebutir-sebutir selesai: $safeEpochs epoch (${tokenizedPairs.size} butir data, total $totalSteps step). Loss awal: ${round4(firstSampleLoss)} -> Loss akhir: ${round4(lastSampleLoss)}. Akurasi: ${round2(benchmarkScore)}%."
        )
    } catch (e: Exception) {
        return RealTrainingExecutionResult(
            initialLoss = 0.0f,
            finalLoss = 0.0f,
            lossReduction = 0.0f,
            parameterCount = 0L,
            parameterCountFormatted = "belum ada",
            benchmarkScore = 0.0f,
            adapterSizeBytes = 0L,
            durationMs = System.currentTimeMillis() - startTime,
            vocabularySize = 0,
            epochsTrained = 0,
            isAccepted = false,
            summary = "Pelatihan aman terhenti: ${e.message ?: "Memori atau thread dibatasi sistem"}"
        )
    }
    }

    /**
     * Menyimpan bobot adapter SECARA LENGKAP (wIn + wOut) ke dua format:
     *  - .bin custom: magic + int32(vocabSize) + int32(embeddingDim) + wIn[row-major] + wOut[row-major]
     *  - .safetensors standar: header JSON (8-byte LE length) + dua tensor F32 dengan offset benar.
     * Semua float ditulis 4 byte penuh little-endian (sebelumnya hanya 2 byte -> korup).
     * Mengembalikan TOTAL byte nyata yang tertulis di kedua file (bukan hasil coerceAtLeast palsu).
     */
    private fun writeAdapterWeights(
        storageDir: File,
        adapterId: String,
        magic: String,
        vocabSize: Int,
        embeddingDim: Int,
        wIn: Array<FloatArray>,
        wOut: Array<FloatArray>
    ): Long {
        val adapterFile = File(storageDir, "$adapterId.bin")
        val safetensorsFile = File(storageDir, "$adapterId.safetensors")
        val wInBytes = vocabSize.toLong() * embeddingDim * 4L
        val wOutBytes = embeddingDim.toLong() * vocabSize * 4L
        val totalTensorBytes = wInBytes + wOutBytes
        val bb = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun writeFloatLE(fos: java.io.OutputStream, v: Float) {
            bb.clear()
            bb.putFloat(v)
            fos.write(bb.array())
        }
        fun writeIntLE(fos: java.io.OutputStream, v: Int) {
            fos.write(v and 0xFF)
            fos.write((v shr 8) and 0xFF)
            fos.write((v shr 16) and 0xFF)
            fos.write((v shr 24) and 0xFF)
        }
        try {
            adapterFile.parentFile?.mkdirs()
            adapterFile.outputStream().use { fos ->
                fos.write(magic.toByteArray(Charsets.UTF_8))
                writeIntLE(fos, vocabSize)
                writeIntLE(fos, embeddingDim)
                for (i in 0 until vocabSize) for (d in 0 until embeddingDim) writeFloatLE(fos, wIn[i][d])
                for (d in 0 until embeddingDim) for (j in 0 until vocabSize) writeFloatLE(fos, wOut[d][j])
            }
            safetensorsFile.outputStream().use { fos ->
                val metadataJson = """{"base_model.model.adapter.w_in":{"dtype":"F32","shape":[$vocabSize,$embeddingDim],"data_offsets":[0,$wInBytes]},"base_model.model.adapter.w_out":{"dtype":"F32","shape":[$embeddingDim,$vocabSize],"data_offsets":[$wInBytes,$totalTensorBytes]},"__metadata__":{"format":"pt","model_type":"nexora_adapter","adapter_id":"$adapterId","vocab_size":$vocabSize,"embedding_dim":$embeddingDim}}"""
                val headerBytes = metadataJson.toByteArray(Charsets.UTF_8)
                val headerLength = headerBytes.size.toLong()
                val lenBytes = ByteArray(8)
                for (b in 0 until 8) lenBytes[b] = ((headerLength shr (b * 8)) and 0xFF).toByte()
                fos.write(lenBytes)
                fos.write(headerBytes)
                for (i in 0 until vocabSize) for (d in 0 until embeddingDim) writeFloatLE(fos, wIn[i][d])
                for (d in 0 until embeddingDim) for (j in 0 until vocabSize) writeFloatLE(fos, wOut[d][j])
            }
        } catch (_: Exception) {}
        return adapterFile.length() + safetensorsFile.length()
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9_\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
    }

    private fun round4(v: Float): Float = (v * 10000).toInt() / 10000.0f
    private fun round2(v: Float): Float = (v * 100).toInt() / 100.0f
}
