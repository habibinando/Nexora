package com.example.nexora.engine.neural

import android.util.Log
import org.jetbrains.kotlinx.multik.api.*
import org.jetbrains.kotlinx.multik.ndarray.data.*
import org.jetbrains.kotlinx.multik.ndarray.operations.*
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

enum class OptimizerType(
    val displayName: String,
    val shortName: String,
    val description: String,
    /** Batas atas LR yang masih masuk akal untuk optimizer ini pada Char-RNN (dipakai untuk clamp + log). */
    val maxRecommendedLearningRate: Float
) {
    ADAMW("AdamW (Decoupled Weight Decay - SOTA)", "AdamW", "Standar model modern, mencegah bobot meledak dengan susut bobot decoupled", 0.01f),
    ADAM("Adam (Adaptive Moments)", "Adam", "Momentum adaptif orde 1 & orde 2 standar", 0.01f),
    RMSPROP("RMSprop", "RMSprop", "Root Mean Square propagation adaptif untuk recurrent networks", 0.01f),
    SGD("SGD + Momentum", "SGD-M", "Stochastic Gradient Descent dengan momentum 0.9 & norm clipping", 0.1f)
}

/** Hasil satu chunk (forward + backward, gradien sudah diakumulasi). */
class ChunkResult(
    val lossSum: Double,
    val tokens: Int,
    val correct: Int,
    /** Hidden state terakhir chunk ini; dipakai sebagai h0 chunk berikutnya (truncated BPTT). */
    val lastHidden: FloatArray
)

/** Hasil evaluasi forward-only atas satu sekuens penuh. */
class EvalResult(
    val lossSum: Double,
    val tokens: Int,
    val correct: Int
)

/**
 * On-Device Character-Level Recurrent Neural Network (Char-RNN, vanilla tanh RNN).
 * Arsitektur:
 *   Char ID -> Embedding -> h_t = tanh(W_xh e + W_hh h_{t-1} + b_h) -> Linear -> Softmax
 *
 * Training:
 *   1. forwardSequence (hidden state boleh dibawa dari chunk sebelumnya = truncated BPTT)
 *   2. accumulateGradients: BPTT analitik, gradien dijumlah ke buffer persisten
 *   3. applyAccumulated: rata-rata per jumlah chunk, global-norm clipping, lalu update optimizer
 *      (update dilewati jika gradien NaN/Inf)
 *
 * Tidak ada angka palsu: semua loss/akurasi dihitung dari forward pass nyata.
 */
class CharRNNModel(
    val vocab: List<Char> = DEFAULT_VOCAB,
    val embedDim: Int = 64,
    val hiddenDim: Int = 128
) {
    val vocabSize: Int = vocab.size
    private val charToIdx: Map<Char, Int> = vocab.mapIndexed { idx, c -> c to idx }.toMap()
    private val idxToChar: Map<Int, Char> = vocab.mapIndexed { idx, c -> idx to c }.toMap()

    // W_emb: [vocabSize, embedDim]
    var wEmb: D2Array<Float>
    // W_xh: [hiddenDim, embedDim]
    var wXh: D2Array<Float>
    // W_hh: [hiddenDim, hiddenDim]
    var wHh: D2Array<Float>
    // b_h: [hiddenDim]
    var bH: D1Array<Float>
    // W_out: [vocabSize, hiddenDim]
    var wOut: D2Array<Float>
    // b_out: [vocabSize]
    var bOut: D1Array<Float>

    /** Norma gradien global (setelah dirata-rata, sebelum clipping) pada update terakhir. */
    var lastGradNorm: Float = 0f
        private set

    /** Jumlah update yang dilewati karena gradien tidak finit (NaN/Inf). */
    var skippedUpdates: Int = 0
        private set

    private var adamStep = 0

    /** Buffer gradien + momen optimizer untuk satu tensor. Dipakai ulang, tidak dialokasi per langkah. */
    private class OptSlot(size: Int) {
        val g = FloatArray(size)
        val m = FloatArray(size)
        val v = FloatArray(size)
    }

    // Urutan slot: 0 wEmb, 1 wXh, 2 wHh, 3 bH, 4 wOut, 5 bOut
    private var slots: Array<OptSlot>? = null

    init {
        val rng = Random(42)
        fun uniform(n: Int, limit: Float): FloatArray =
            FloatArray(n) { (rng.nextFloat() * 2f - 1f) * limit }

        // Embedding: skala 1/sqrt(embedDim).
        wEmb = mk.ndarray(uniform(vocabSize * embedDim, 1.0f / sqrt(embedDim.toFloat())), vocabSize, embedDim)
        // Xavier/Glorot uniform: limit = sqrt(6 / (fanIn + fanOut)).
        wXh = mk.ndarray(uniform(hiddenDim * embedDim, sqrt(6.0f / (embedDim + hiddenDim))), hiddenDim, embedDim)
        // W_hh: Xavier dikali 0.8 agar radius spektral sedikit < 1 (lebih stabil di awal untuk RNN tanh).
        wHh = mk.ndarray(uniform(hiddenDim * hiddenDim, 0.8f * sqrt(6.0f / (hiddenDim + hiddenDim))), hiddenDim, hiddenDim)
        bH = mk.zeros<Float>(hiddenDim)
        // W_out: fanIn = hiddenDim, fanOut = vocabSize.
        wOut = mk.ndarray(uniform(vocabSize * hiddenDim, sqrt(6.0f / (hiddenDim + vocabSize))), vocabSize, hiddenDim)
        bOut = mk.zeros<Float>(vocabSize)
    }

    fun countParameters(): Long {
        val embP = (vocabSize * embedDim).toLong()
        val xhP = (hiddenDim * embedDim).toLong()
        val hhP = (hiddenDim * hiddenDim).toLong()
        val bhP = hiddenDim.toLong()
        val outP = (vocabSize * hiddenDim).toLong()
        val boutP = vocabSize.toLong()
        return embP + xhP + hhP + bhP + outP + boutP
    }

    fun charToToken(c: Char): Int = charToIdx[c] ?: charToIdx['?'] ?: 0
    fun tokenToChar(idx: Int): Char = idxToChar[idx] ?: ' '

    fun computeWOutL2Norm(): Float {
        var sumSq = 0.0
        val data = wOut.data.getFloatArray()
        for (v in data) {
            sumSq += v.toDouble() * v.toDouble()
        }
        return sqrt(sumSq).toFloat()
    }

    /** Buang gradien terakumulasi + state optimizer (dipanggil sebelum run baru / setelah restore bobot). */
    fun resetOptimizerState() {
        slots = null
        adamStep = 0
        lastGradNorm = 0f
        skippedUpdates = 0
    }

    /** Buang gradien yang sudah diakumulasi tanpa meng-update bobot. */
    fun discardAccumulatedGradients() {
        slots?.let { clearGradients(it) }
    }

    private fun clearGradients(s: Array<OptSlot>) {
        for (slot in s) java.util.Arrays.fill(slot.g, 0f)
    }

    private fun ensureSlots(): Array<OptSlot> {
        val sizes = intArrayOf(
            vocabSize * embedDim,
            hiddenDim * embedDim,
            hiddenDim * hiddenDim,
            hiddenDim,
            vocabSize * hiddenDim,
            vocabSize
        )
        val cur = slots
        if (cur != null && cur.size == sizes.size) {
            var ok = true
            for (i in sizes.indices) if (cur[i].g.size != sizes[i]) ok = false
            if (ok) return cur
        }
        val created = Array(sizes.size) { OptSlot(sizes[it]) }
        slots = created
        return created
    }

    private fun argMax(a: FloatArray): Int {
        var best = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in a.indices) {
            if (a[i] > bestVal) {
                bestVal = a[i]
                best = i
            }
        }
        return best
    }

    /**
     * Forward pass pada [x_0 .. x_{T-1}]. h0 (opsional) = hidden state awal (dibawa dari chunk sebelumnya);
     * jika null dipakai vektor nol.
     */
    fun forwardSequence(tokens: IntArray, h0: FloatArray? = null): ForwardTape {
        val T = tokens.size
        val hStates = Array(T + 1) { FloatArray(hiddenDim) }
        if (h0 != null) System.arraycopy(h0, 0, hStates[0], 0, minOf(h0.size, hiddenDim))
        val logits = Array(T) { FloatArray(vocabSize) }
        val probs = Array(T) { FloatArray(vocabSize) }

        val wEmbData = wEmb.data.getFloatArray()
        val wXhData = wXh.data.getFloatArray()
        val wHhData = wHh.data.getFloatArray()
        val bHData = bH.data.getFloatArray()
        val wOutData = wOut.data.getFloatArray()
        val bOutData = bOut.data.getFloatArray()

        for (t in 0 until T) {
            val token = tokens[t].coerceIn(0, vocabSize - 1)
            val hPrev = hStates[t]
            val hNext = hStates[t + 1]

            // h_next = tanh(W_xh * emb(token) + W_hh * h_prev + b_h)
            val embOffset = token * embedDim
            for (i in 0 until hiddenDim) {
                var sum = bHData[i]
                val xhOffset = i * embedDim
                for (d in 0 until embedDim) {
                    sum += wXhData[xhOffset + d] * wEmbData[embOffset + d]
                }
                val hhOffset = i * hiddenDim
                for (j in 0 until hiddenDim) {
                    sum += wHhData[hhOffset + j] * hPrev[j]
                }
                hNext[i] = kotlin.math.tanh(sum.toDouble()).toFloat()
            }

            // Logits: z = W_out * h_next + b_out
            val z = logits[t]
            var maxZ = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                var sum = bOutData[v]
                val outOffset = v * hiddenDim
                for (h in 0 until hiddenDim) {
                    sum += wOutData[outOffset + h] * hNext[h]
                }
                z[v] = sum
                if (sum > maxZ) maxZ = sum
            }

            // Softmax stabil secara numerik
            var sumExp = 0.0
            val p = probs[t]
            for (v in 0 until vocabSize) {
                val expVal = exp((z[v] - maxZ).toDouble())
                p[v] = expVal.toFloat()
                sumExp += expVal
            }
            val invSum = (1.0 / (sumExp.coerceAtLeast(1e-9))).toFloat()
            for (v in 0 until vocabSize) {
                p[v] = (p[v] * invSum).coerceIn(1e-7f, 1.0f)
            }
        }

        return ForwardTape(tokens, hStates, logits, probs)
    }

    /**
     * Forward + BPTT satu chunk; gradien DIJUMLAHKAN ke buffer persisten (belum ada update bobot).
     * Loss chunk = rata-rata cross-entropy per token; gradien chunk sudah dibagi T.
     * Panggil applyAccumulated(chunksInBatch = jumlah chunk yang diakumulasi) untuk meng-update bobot.
     * Gradien tidak dialirkan ke h0 (truncated BPTT).
     */
    fun accumulateGradients(
        inputTokens: IntArray,
        targetTokens: IntArray,
        h0: FloatArray? = null
    ): ChunkResult {
        require(inputTokens.size == targetTokens.size) { "Input and target length mismatch" }
        val T = inputTokens.size
        if (T == 0) {
            return ChunkResult(0.0, 0, 0, h0?.copyOf() ?: FloatArray(hiddenDim))
        }

        val tape = forwardSequence(inputTokens, h0)
        val s = ensureSlots()
        val dWemb = s[0].g
        val dWxh = s[1].g
        val dWhh = s[2].g
        val dbh = s[3].g
        val dWout = s[4].g
        val dbout = s[5].g

        val wOutData = wOut.data.getFloatArray()
        val wHhData = wHh.data.getFloatArray()
        val wXhData = wXh.data.getFloatArray()
        val wEmbData = wEmb.data.getFloatArray()

        // Loss + akurasi top-1 dari forward pass (sebelum update)
        var lossSum = 0.0
        var correct = 0
        for (t in 0 until T) {
            val target = targetTokens[t].coerceIn(0, vocabSize - 1)
            lossSum += -ln(tape.probs[t][target].coerceAtLeast(1e-7f).toDouble())
            if (argMax(tape.logits[t]) == target) correct++
        }

        val invT = 1.0f / T
        val dh = FloatArray(hiddenDim)
        val dRaw = FloatArray(hiddenDim)
        var dhNext = FloatArray(hiddenDim)
        var dhPrev = FloatArray(hiddenDim)

        for (t in T - 1 downTo 0) {
            val target = targetTokens[t].coerceIn(0, vocabSize - 1)
            val p = tape.probs[t]
            val hCur = tape.hStates[t + 1]
            val hPrev = tape.hStates[t]
            val token = inputTokens[t].coerceIn(0, vocabSize - 1)

            // dL/dz_v = (p_v - 1{v==target}) / T ; sekaligus dW_out, db_out, dh = W_out^T dz
            java.util.Arrays.fill(dh, 0f)
            for (v in 0 until vocabSize) {
                val d = (p[v] - (if (v == target) 1.0f else 0.0f)) * invT
                dbout[v] += d
                val off = v * hiddenDim
                for (h in 0 until hiddenDim) {
                    dWout[off + h] += d * hCur[h]
                    dh[h] += wOutData[off + h] * d
                }
            }
            for (h in 0 until hiddenDim) dh[h] += dhNext[h]

            // dRaw = dh * (1 - tanh^2)
            for (h in 0 until hiddenDim) {
                val th = hCur[h]
                val r = dh[h] * (1.0f - th * th)
                dRaw[h] = r
                dbh[h] += r
            }

            // dW_hh += dRaw * hPrev^T ; dhPrev = W_hh^T * dRaw
            java.util.Arrays.fill(dhPrev, 0f)
            for (i in 0 until hiddenDim) {
                val r = dRaw[i]
                val off = i * hiddenDim
                for (j in 0 until hiddenDim) {
                    dWhh[off + j] += r * hPrev[j]
                    dhPrev[j] += wHhData[off + j] * r
                }
            }

            // dW_xh += dRaw * emb^T ; dW_emb += W_xh^T * dRaw
            val embOff = token * embedDim
            for (i in 0 until hiddenDim) {
                val r = dRaw[i]
                val off = i * embedDim
                for (d in 0 until embedDim) {
                    dWxh[off + d] += r * wEmbData[embOff + d]
                    dWemb[embOff + d] += r * wXhData[off + d]
                }
            }

            val tmp = dhNext
            dhNext = dhPrev
            dhPrev = tmp
        }

        return ChunkResult(lossSum, T, correct, tape.hStates[T])
    }

    /**
     * Update bobot dari gradien terakumulasi.
     * - Gradien dirata-rata: dibagi chunksInBatch.
     * - Global-norm clipping (satu norma untuk SEMUA tensor) ke maxGradNorm.
     * - Jika norma NaN/Inf: update dilewati, gradien dibuang, return false.
     */
    fun applyAccumulated(
        learningRate: Float,
        optimizerType: OptimizerType = OptimizerType.ADAMW,
        maxGradNorm: Float = 1.0f,
        chunksInBatch: Int = 1
    ): Boolean {
        val s = ensureSlots()
        val inv = 1.0f / chunksInBatch.coerceAtLeast(1)

        var sumSq = 0.0
        for (slot in s) {
            val g = slot.g
            for (i in g.indices) {
                val x = g[i] * inv
                sumSq += (x * x).toDouble()
            }
        }
        val norm = sqrt(sumSq)
        lastGradNorm = norm.toFloat()
        if (norm.isNaN() || norm.isInfinite()) {
            clearGradients(s)
            skippedUpdates++
            return false
        }
        val clip = if (norm > maxGradNorm && norm > 1e-12) (maxGradNorm / norm).toFloat() else 1.0f
        val scale = inv * clip

        adamStep++
        val weights = arrayOf(
            wEmb.data.getFloatArray(),
            wXh.data.getFloatArray(),
            wHh.data.getFloatArray(),
            bH.data.getFloatArray(),
            wOut.data.getFloatArray(),
            bOut.data.getFloatArray()
        )
        for (k in 0 until 6) {
            // Weight decay hanya untuk matriks (bukan bias: slot 3 dan 5)
            applyOptimizerUpdate(weights[k], s[k], learningRate, optimizerType, scale, k != 3 && k != 5)
        }
        clearGradients(s)
        return true
    }

    private fun applyOptimizerUpdate(
        weights: FloatArray,
        slot: OptSlot,
        lr: Float,
        optimizerType: OptimizerType,
        gradScale: Float,
        decay: Boolean
    ) {
        val g = slot.g
        val m = slot.m
        val v = slot.v
        when (optimizerType) {
            OptimizerType.ADAMW, OptimizerType.ADAM -> {
                val beta1 = 0.9f
                val beta2 = 0.999f
                val eps = 1e-8f
                val step = adamStep.coerceAtLeast(1)
                val bc1 = (1.0 - Math.pow(beta1.toDouble(), step.toDouble())).toFloat()
                val bc2 = (1.0 - Math.pow(beta2.toDouble(), step.toDouble())).toFloat()
                val stepLr = lr * (sqrt(bc2) / bc1)
                // AdamW: decoupled weight decay w <- w * (1 - lr * wd)
                val keep = if (optimizerType == OptimizerType.ADAMW && decay) 1.0f - lr * 0.01f else 1.0f
                for (i in weights.indices) {
                    val gi = g[i] * gradScale
                    val mi = beta1 * m[i] + (1f - beta1) * gi
                    val vi = beta2 * v[i] + (1f - beta2) * gi * gi
                    m[i] = mi
                    v[i] = vi
                    weights[i] = weights[i] * keep - stepLr * (mi / (sqrt(vi) + eps))
                }
            }
            OptimizerType.RMSPROP -> {
                val alpha = 0.99f
                val eps = 1e-8f
                for (i in weights.indices) {
                    val gi = g[i] * gradScale
                    val vi = alpha * v[i] + (1f - alpha) * gi * gi
                    v[i] = vi
                    weights[i] -= lr * (gi / (sqrt(vi) + eps))
                }
            }
            OptimizerType.SGD -> {
                val momentum = 0.9f
                for (i in weights.indices) {
                    val gi = g[i] * gradScale
                    val mi = momentum * m[i] + gi
                    m[i] = mi
                    weights[i] -= lr * mi
                }
            }
        }
    }

    /**
     * Satu langkah latih penuh (1 chunk = 1 update). Dipertahankan untuk kompatibilitas;
     * loop latih utama memakai accumulateGradients + applyAccumulated agar batch & hidden-carry nyata.
     */
    fun trainStep(
        inputTokens: IntArray,
        targetTokens: IntArray,
        learningRate: Float = 0.005f,
        optimizerType: OptimizerType = OptimizerType.ADAMW
    ): StepMetrics {
        require(inputTokens.size == targetTokens.size) { "Input and target length mismatch" }
        val T = inputTokens.size
        if (T == 0) return StepMetrics(0f, computeWOutL2Norm())

        val r = accumulateGradients(inputTokens, targetTokens, null)
        applyAccumulated(learningRate, optimizerType, 1.0f, 1)
        return StepMetrics(
            loss = (r.lossSum / r.tokens).toFloat(),
            wOutL2Norm = computeWOutL2Norm(),
            correct = r.correct,
            tokens = r.tokens
        )
    }

    /** Forward-only pada satu chunk (tanpa backprop / update). */
    fun evaluateStep(
        inputTokens: IntArray,
        targetTokens: IntArray
    ): StepMetrics {
        require(inputTokens.size == targetTokens.size) { "Input and target length mismatch" }
        val T = inputTokens.size
        if (T == 0) return StepMetrics(0f, computeWOutL2Norm())

        val tape = forwardSequence(inputTokens)
        var totalLoss = 0.0
        var correct = 0
        for (t in 0 until T) {
            val target = targetTokens[t].coerceIn(0, vocabSize - 1)
            totalLoss += -ln(tape.probs[t][target].coerceAtLeast(1e-7f).toDouble())
            if (argMax(tape.logits[t]) == target) correct++
        }
        return StepMetrics(
            loss = (totalLoss / T).toFloat(),
            wOutL2Norm = computeWOutL2Norm(),
            correct = correct,
            tokens = T
        )
    }

    /**
     * Evaluasi forward-only atas SELURUH sekuens (dipecah per chunk dengan hidden state dibawa),
     * mengembalikan jumlah loss, jumlah token, dan jumlah prediksi top-1 yang benar.
     * Loss rata-rata yang benar = lossSum / tokens (token-weighted), bukan rata-rata antar-sampel.
     */
    fun evaluateSequence(tokens: IntArray, chunkSize: Int = 128): EvalResult {
        val n = tokens.size
        if (n < 2) return EvalResult(0.0, 0, 0)
        val cs = chunkSize.coerceAtLeast(2)
        var h: FloatArray? = null
        var s = 0
        var lossSum = 0.0
        var correct = 0
        var count = 0
        while (s < n - 1) {
            val e = minOf(s + cs, n - 1)
            val inp = tokens.copyOfRange(s, e)
            val tgt = tokens.copyOfRange(s + 1, e + 1)
            val tape = forwardSequence(inp, h)
            for (t in inp.indices) {
                val target = tgt[t].coerceIn(0, vocabSize - 1)
                lossSum += -ln(tape.probs[t][target].coerceAtLeast(1e-7f).toDouble())
                if (argMax(tape.logits[t]) == target) correct++
            }
            count += inp.size
            h = tape.hStates[inp.size]
            s = e
        }
        return EvalResult(lossSum, count, correct)
    }

    /**
     * Autoregressive next-character text generation.
     */
    fun generate(prompt: String, maxChars: Int = 80, temperature: Float = 0.7f): String {
        if (prompt.isEmpty()) return ""
        val tokens = prompt.map { charToToken(it) }.toIntArray()
        val tape = forwardSequence(tokens)
        var currentHidden = tape.hStates[tokens.size]

        val generated = StringBuilder(prompt)

        val wEmbData = wEmb.data.getFloatArray()
        val wXhData = wXh.data.getFloatArray()
        val wHhData = wHh.data.getFloatArray()
        val bHData = bH.data.getFloatArray()
        val wOutData = wOut.data.getFloatArray()
        val bOutData = bOut.data.getFloatArray()

        val rng = Random.Default
        var lastSampledToken: Int? = null

        for (step in 0 until maxChars) {
            if (lastSampledToken != null) {
                val nextHidden = FloatArray(hiddenDim)
                val embOffset = lastSampledToken * embedDim
                for (i in 0 until hiddenDim) {
                    var sum = bHData[i]
                    val xhOffset = i * embedDim
                    for (d in 0 until embedDim) {
                        sum += wXhData[xhOffset + d] * wEmbData[embOffset + d]
                    }
                    val hhOffset = i * hiddenDim
                    for (j in 0 until hiddenDim) {
                        sum += wHhData[hhOffset + j] * currentHidden[j]
                    }
                    nextHidden[i] = kotlin.math.tanh(sum.toDouble()).toFloat()
                }
                currentHidden = nextHidden
            }

            val logits = FloatArray(vocabSize)
            var maxZ = Float.NEGATIVE_INFINITY
            val safeTemp = temperature.coerceAtLeast(0.01f)
            for (v in 0 until vocabSize) {
                var sum = bOutData[v]
                val outOffset = v * hiddenDim
                for (h in 0 until hiddenDim) {
                    sum += wOutData[outOffset + h] * currentHidden[h]
                }
                val scaled = sum / safeTemp
                logits[v] = scaled
                if (scaled > maxZ) maxZ = scaled
            }

            val chosenToken: Int
            if (safeTemp <= 0.05f) {
                chosenToken = argMax(logits)
            } else {
                var sumExp = 0.0
                val probs = DoubleArray(vocabSize)
                for (v in 0 until vocabSize) {
                    val expVal = exp((logits[v] - maxZ).toDouble())
                    probs[v] = expVal
                    sumExp += expVal
                }
                val r = rng.nextDouble() * sumExp
                var accum = 0.0
                var sampled = 0
                for (v in 0 until vocabSize) {
                    accum += probs[v]
                    if (accum >= r) {
                        sampled = v
                        break
                    }
                }
                chosenToken = sampled
            }

            val nextChar = tokenToChar(chosenToken)
            generated.append(nextChar)
            lastSampledToken = chosenToken

            if (nextChar == '\n' && generated.length > prompt.length + 30) break
        }

        return generated.toString()
    }

    /**
     * Simpan bobot ke JSON (format sama seperti sebelumnya, kompatibel import/export).
     * Streaming (tanpa JSONArray raksasa di heap) dan ATOMIC: tulis ke .tmp lalu rename,
     * sehingga crash/kill di tengah penulisan tidak merusak file bobot lama.
     * Melempar IllegalStateException jika ada bobot NaN/Inf (file lama tetap utuh).
     */
    fun saveToFile(file: File) {
        val parent = file.parentFile
        parent?.mkdirs()
        val tmp = File(parent, file.name + ".tmp")
        try {
            java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8), 1 shl 16).use { w ->
                w.write("{\"vocab\":")
                w.write(JSONObject.quote(vocab.joinToString("")))
                w.write(",\"embedDim\":$embedDim,\"hiddenDim\":$hiddenDim,\"paramsCount\":${countParameters()},\"savedAt\":${System.currentTimeMillis()}")
                writeFloatArray(w, "wEmb", wEmb.data.getFloatArray())
                writeFloatArray(w, "wXh", wXh.data.getFloatArray())
                writeFloatArray(w, "wHh", wHh.data.getFloatArray())
                writeFloatArray(w, "bH", bH.data.getFloatArray())
                writeFloatArray(w, "wOut", wOut.data.getFloatArray())
                writeFloatArray(w, "bOut", bOut.data.getFloatArray())
                w.write("}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun writeFloatArray(w: java.io.Writer, name: String, arr: FloatArray) {
        w.write(",\"$name\":[")
        for (i in arr.indices) {
            val f = arr[i]
            if (f.isNaN() || f.isInfinite()) {
                throw IllegalStateException("Bobot $name[$i] tidak finit (NaN/Inf); penyimpanan dibatalkan")
            }
            if (i > 0) w.write(",")
            w.write(f.toString())
        }
        w.write("]")
    }

    /**
     * Load trained weights from JSON file.
     */
    fun loadFromFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val json = JSONObject(file.readText())
            val savedVocab = json.optString("vocab", "")
            val savedEmbed = json.optInt("embedDim", -1)
            val savedHidden = json.optInt("hiddenDim", -1)
            val dimsMatch = savedEmbed == embedDim && savedHidden == hiddenDim
            if (savedVocab.isNotEmpty() && savedVocab.length == vocab.size && dimsMatch) {
                copyJsonToFloatArray(json.getJSONArray("wEmb"), wEmb.data.getFloatArray())
                copyJsonToFloatArray(json.getJSONArray("wXh"), wXh.data.getFloatArray())
                copyJsonToFloatArray(json.getJSONArray("wHh"), wHh.data.getFloatArray())
                copyJsonToFloatArray(json.getJSONArray("bH"), bH.data.getFloatArray())
                copyJsonToFloatArray(json.getJSONArray("wOut"), wOut.data.getFloatArray())
                copyJsonToFloatArray(json.getJSONArray("bOut"), bOut.data.getFloatArray())
                true
            } else {
                Log.w(
                    "CharRNNModel",
                    "loadFromFile ditolak: vocab/dimensi tidak cocok. " +
                        "file(vocab=${savedVocab.length}, embed=$savedEmbed, hidden=$savedHidden) " +
                        "!= model(vocab=${vocab.size}, embed=$embedDim, hidden=$hiddenDim). " +
                        "Gunakan CharRNNModel.fromFile(...) untuk membangun ulang instance."
                )
                false
            }
        } catch (e: Exception) {
            Log.e("CharRNNModel", "Failed to load weights: ${e.message}")
            false
        }
    }

    private fun copyJsonToFloatArray(jArr: org.json.JSONArray, dest: FloatArray) {
        val len = minOf(jArr.length(), dest.size)
        for (i in 0 until len) {
            dest[i] = jArr.getDouble(i).toFloat()
        }
    }

    /**
     * Konversi bobot ke struktur Tensor ExecuTorch (in-memory). Ini BUKAN ekspor file .pte.
     */
    fun toExecuTorchTensors(): Map<String, Tensor> {
        val outTensor = Tensor.fromBlob(wOut.data.getFloatArray(), longArrayOf(vocabSize.toLong(), hiddenDim.toLong()))
        val embTensor = Tensor.fromBlob(wEmb.data.getFloatArray(), longArrayOf(vocabSize.toLong(), embedDim.toLong()))
        val xhTensor = Tensor.fromBlob(wXh.data.getFloatArray(), longArrayOf(hiddenDim.toLong(), embedDim.toLong()))
        val hhTensor = Tensor.fromBlob(wHh.data.getFloatArray(), longArrayOf(hiddenDim.toLong(), hiddenDim.toLong()))
        val bHTensor = Tensor.fromBlob(bH.data.getFloatArray(), longArrayOf(hiddenDim.toLong()))
        val bOutTensor = Tensor.fromBlob(bOut.data.getFloatArray(), longArrayOf(vocabSize.toLong()))
        return mapOf(
            "w_out" to outTensor,
            "w_emb" to embTensor,
            "w_xh" to xhTensor,
            "w_hh" to hhTensor,
            "b_h" to bHTensor,
            "b_out" to bOutTensor
        )
    }

    /**
     * Forward pass satu langkah memakai container EValue ExecuTorch.
     */
    fun forwardEValue(inputToken: Int, lastHidden: FloatArray): Pair<EValue, FloatArray> {
        val tokenTensor = Tensor.fromBlob(floatArrayOf(inputToken.toFloat()), longArrayOf(1))
        val hTensor = Tensor.fromBlob(lastHidden, longArrayOf(1, hiddenDim.toLong()))
        val inputEValue = EValue.from(tokenTensor)
        val hiddenEValue = EValue.from(hTensor)

        val nextH = FloatArray(hiddenDim)
        val token = inputToken.coerceIn(0, vocabSize - 1)
        val wEmbData = wEmb.data.getFloatArray()
        val wXhData = wXh.data.getFloatArray()
        val wHhData = wHh.data.getFloatArray()
        val bHData = bH.data.getFloatArray()
        val wOutData = wOut.data.getFloatArray()
        val bOutData = bOut.data.getFloatArray()

        val embOffset = token * embedDim
        for (i in 0 until hiddenDim) {
            var sum = bHData[i]
            val xhOffset = i * embedDim
            for (d in 0 until embedDim) {
                sum += wXhData[xhOffset + d] * wEmbData[embOffset + d]
            }
            val hhOffset = i * hiddenDim
            for (j in 0 until hiddenDim) {
                sum += wHhData[hhOffset + j] * lastHidden[j]
            }
            nextH[i] = kotlin.math.tanh(sum.toDouble()).toFloat()
        }

        val logits = FloatArray(vocabSize)
        for (v in 0 until vocabSize) {
            var sum = bOutData[v]
            val outOffset = v * hiddenDim
            for (h in 0 until hiddenDim) {
                sum += wOutData[outOffset + h] * nextH[h]
            }
            logits[v] = sum
        }

        val outTensor = Tensor.fromBlob(logits, longArrayOf(1, vocabSize.toLong()))
        return Pair(EValue.from(outTensor), nextH)
    }

    companion object {
        val DEFAULT_VOCAB: List<Char> = (
            " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!?:;-_'\"/()[]{}+=*\n"
        ).toList()

        /** Ukuran kosakata default yang SESUNGGUHNYA (dihitung dari DEFAULT_VOCAB). */
        val DEFAULT_VOCAB_SIZE: Int = DEFAULT_VOCAB.size

        /**
         * Estimasi RAM NYATA untuk model + gradien + momen optimizer (4 salinan float32) + tape forward satu chunk.
         * Ini angka perhitungan dari jumlah parameter, bukan label profil.
         */
        fun estimateTrainingMemoryMb(vocabSize: Int, embedDim: Int, hiddenDim: Int, chunkSize: Int): Float {
            val v = vocabSize.toLong()
            val e = embedDim.toLong()
            val h = hiddenDim.toLong()
            val params = v * e + h * e + h * h + h + v * h + v
            val optimizerBytes = params * 4L * 4L
            val tapeBytes = chunkSize.toLong() * (h + 2L * v) * 4L
            return (optimizerBytes + tapeBytes) / (1024f * 1024f)
        }

        fun buildVocabFromDataset(texts: Iterable<String>): List<Char> {
            val charSet = linkedSetOf(' ', '\n', '?')
            for (text in texts) {
                for (ch in text) {
                    charSet.add(ch)
                }
            }
            return charSet.toList()
        }

        fun fitVocab(texts: Iterable<String>): List<Char> = buildVocabFromDataset(texts)

        /**
         * Jika dataset punya karakter di luar kosakata model, bangun model baru dengan vocab diperluas
         * dan transfer bobot lama yang beririsan.
         */
        fun expandVocabIfNeeded(
            currentModel: CharRNNModel,
            texts: Iterable<String>
        ): CharRNNModel {
            val neededChars = linkedSetOf<Char>()
            for (c in currentModel.vocab) neededChars.add(c)
            var hasNew = false
            for (t in texts) {
                for (c in t) {
                    if (neededChars.add(c)) {
                        hasNew = true
                    }
                }
            }
            if (!hasNew) return currentModel

            val newVocab = neededChars.toList()
            val newModel = CharRNNModel(
                vocab = newVocab,
                embedDim = currentModel.embedDim,
                hiddenDim = currentModel.hiddenDim
            )

            val oldWEmb = currentModel.wEmb.data.getFloatArray()
            val newWEmb = newModel.wEmb.data.getFloatArray()
            val oldWOut = currentModel.wOut.data.getFloatArray()
            val newWOut = newModel.wOut.data.getFloatArray()
            val oldBOut = currentModel.bOut.data.getFloatArray()
            val newBOut = newModel.bOut.data.getFloatArray()

            for ((oldIdx, char) in currentModel.vocab.withIndex()) {
                val newIdx = newModel.charToToken(char)
                System.arraycopy(oldWEmb, oldIdx * currentModel.embedDim, newWEmb, newIdx * newModel.embedDim, currentModel.embedDim)
                System.arraycopy(oldWOut, oldIdx * currentModel.hiddenDim, newWOut, newIdx * newModel.hiddenDim, currentModel.hiddenDim)
                newBOut[newIdx] = oldBOut[oldIdx]
            }

            System.arraycopy(currentModel.wXh.data.getFloatArray(), 0, newModel.wXh.data.getFloatArray(), 0, currentModel.hiddenDim * currentModel.embedDim)
            System.arraycopy(currentModel.wHh.data.getFloatArray(), 0, newModel.wHh.data.getFloatArray(), 0, currentModel.hiddenDim * currentModel.hiddenDim)
            System.arraycopy(currentModel.bH.data.getFloatArray(), 0, newModel.bH.data.getFloatArray(), 0, currentModel.hiddenDim)

            Log.i("ANTI_GIMMICK", "Vocab diperluas otomatis dari ${currentModel.vocabSize} -> ${newModel.vocabSize} karakter unik.")
            return newModel
        }

        /**
         * Bangun model dengan vocab & dimensi yang tersimpan di file, lalu muat bobotnya.
         * Null jika file tidak valid / tidak ada.
         */
        fun fromFile(file: File): CharRNNModel? {
            if (!file.exists() || file.length() == 0L) return null
            return try {
                val json = JSONObject(file.readText())
                val savedVocabStr = json.optString("vocab", "")
                val savedEmbed = json.optInt("embedDim", -1)
                val savedHidden = json.optInt("hiddenDim", -1)
                if (savedVocabStr.isEmpty() || savedEmbed <= 0 || savedHidden <= 0) return null
                val savedVocab = savedVocabStr.toList()
                if (savedVocab.isEmpty()) return null
                val model = CharRNNModel(vocab = savedVocab, embedDim = savedEmbed, hiddenDim = savedHidden)
                if (model.loadFromFile(file)) model else null
            } catch (e: Exception) {
                Log.e("CharRNNModel", "fromFile gagal: ${e.message}")
                null
            }
        }

        /**
         * Verifikasi: L2-norm W_out harus berubah setelah 1 langkah latih. Jika tidak, training palsu.
         */
        fun verifyRealTraining(): Boolean {
            val model = CharRNNModel(embedDim = 16, hiddenDim = 32)
            val beforeNorm = model.computeWOutL2Norm()
            val text = "halo nexora on device intelligence test"
            val tokens = text.map { model.charToToken(it) }.toIntArray()
            val inputTokens = tokens.sliceArray(0 until tokens.size - 1)
            val targetTokens = tokens.sliceArray(1 until tokens.size)

            val metrics = model.trainStep(inputTokens, targetTokens, learningRate = 0.05f)
            val afterNorm = model.computeWOutL2Norm()
            val delta = kotlin.math.abs(afterNorm - beforeNorm)

            Log.i("ANTI_GIMMICK", "[verifyRealTraining] W_out L2-Norm BEFORE: $beforeNorm | AFTER: $afterNorm | DELTA: $delta | LOSS: ${metrics.loss}")

            if (delta <= 1e-7f) {
                Log.e("ANTI_GIMMICK", "VERIFICATION FAILED: L2-norm unchanged! Optimizer did not modify tensor weights.")
                return false
            }
            return true
        }
    }
}

data class ForwardTape(
    val tokens: IntArray,
    val hStates: Array<FloatArray>,
    val logits: Array<FloatArray>,
    val probs: Array<FloatArray>
)

data class StepMetrics(
    val loss: Float,
    val wOutL2Norm: Float,
    val correct: Int = 0,
    val tokens: Int = 0
)
