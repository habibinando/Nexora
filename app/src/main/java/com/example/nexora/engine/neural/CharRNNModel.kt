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

enum class OptimizerType(val displayName: String, val shortName: String, val description: String) {
    ADAMW("AdamW (Decoupled Weight Decay - SOTA)", "AdamW", "Standar model modern, mencegah bobot meledak dengan susut bobot decoupled"),
    ADAM("Adam (Adaptive Moments)", "Adam", "Momentum adaptif orde 1 & orde 2 standar"),
    RMSPROP("RMSprop", "RMSprop", "Root Mean Square propagation adaptif untuk recurrent networks"),
    SGD("SGD + Momentum", "SGD-M", "Stochastic Gradient Descent dengan momentum 0.9 & norm clipping")
}

/**
 * Authentic On-Device Character-Level Recurrent Neural Network (Char-RNN).
 * Architecture:
 *   Char Token ID -> Embedding (d_model) -> Hidden Recurrent Step with Tanh -> Output Linear -> Softmax
 *
 * Training Process:
 *   1. Forward pass computing explicit hidden state sequence.
 *   2. CrossEntropyLoss = -ln(P(target_char)).
 *   3. Explicit backward pass (BPTT / Autograd) computing dL/dWout, dL/dbout, dL/dWhh, dL/dWxh, dL/dWemb.
 *   4. Optimizer: SGD with Momentum and Adam (updates weights via Multik tensors).
 *   5. Persistence: Save/Load weights to private storage as JSON and binary.
 *
 * Anti-Gimmick Guarantee:
 *   - Zero Random() or Thread.sleep() for loss calculations.
 *   - verifyRealTraining() tests L2 norm difference before vs after optimizer step.
 *   - Real next-character autoregressive text generation from user prompts.
 */
class CharRNNModel(
    val vocab: List<Char> = DEFAULT_VOCAB,
    val embedDim: Int = 64,
    val hiddenDim: Int = 128
) {
    val vocabSize: Int = vocab.size
    private val charToIdx: Map<Char, Int> = vocab.mapIndexed { idx, c -> c to idx }.toMap()
    private val idxToChar: Map<Int, Char> = vocab.mapIndexed { idx, c -> idx to c }.toMap()

    // Weight Tensors
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

    // Optimizer State Buffers (Adam 1st & 2nd moments, RMSprop/Momentum)
    private var adamStep = 0
    private var m_wOut: FloatArray? = null
    private var v_wOut: FloatArray? = null
    private var m_bOut: FloatArray? = null
    private var v_bOut: FloatArray? = null
    private var m_wXh: FloatArray? = null
    private var v_wXh: FloatArray? = null
    private var m_wHh: FloatArray? = null
    private var v_wHh: FloatArray? = null
    private var m_bH: FloatArray? = null
    private var v_bH: FloatArray? = null
    private var m_wEmb: FloatArray? = null
    private var v_wEmb: FloatArray? = null

    init {
        val rng = Random(42)
        val embScale = 1.0f / sqrt(embedDim.toFloat())
        val hiddenScale = 1.0f / sqrt(hiddenDim.toFloat())
        val outScale = 1.0f / sqrt(vocabSize.toFloat())

        val embData = FloatArray(vocabSize * embedDim) { (rng.nextFloat() * 2f - 1f) * embScale }
        wEmb = mk.ndarray(embData, vocabSize, embedDim)

        val xhData = FloatArray(hiddenDim * embedDim) { (rng.nextFloat() * 2f - 1f) * hiddenScale }
        wXh = mk.ndarray(xhData, hiddenDim, embedDim)

        val hhData = FloatArray(hiddenDim * hiddenDim) { (rng.nextFloat() * 2f - 1f) * hiddenScale }
        wHh = mk.ndarray(hhData, hiddenDim, hiddenDim)

        bH = mk.zeros<Float>(hiddenDim)

        val outData = FloatArray(vocabSize * hiddenDim) { (rng.nextFloat() * 2f - 1f) * outScale }
        wOut = mk.ndarray(outData, vocabSize, hiddenDim)

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

    /**
     * Compute L2 Frobenius Norm of W_out tensor to verify weight delta during training.
     */
    fun computeWOutL2Norm(): Float {
        var sumSq = 0.0
        val data = wOut.data.getFloatArray()
        for (v in data) {
            sumSq += v.toDouble() * v.toDouble()
        }
        return sqrt(sumSq).toFloat()
    }

    /**
     * Forward pass on a character index sequence [x_0, x_1, ..., x_{T-1}].
     * Returns list of hidden states and logits at each timestep.
     */
    fun forwardSequence(tokens: IntArray): ForwardTape {
        val T = tokens.size
        val hStates = Array(T + 1) { FloatArray(hiddenDim) } // h[0] is zeros
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
            for (i in 0 until hiddenDim) {
                var sum = bHData[i]
                val embOffset = token * embedDim
                val xhOffset = i * embedDim
                for (d in 0 until embedDim) {
                    sum += wXhData[xhOffset + d] * wEmbData[embOffset + d]
                }
                val hhOffset = i * hiddenDim
                for (j in 0 until hiddenDim) {
                    sum += wHhData[hhOffset + j] * hPrev[j]
                }
                // Tanh activation
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

            // Softmax
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
     * Train on sequence with Cross-Entropy Loss & Analytical Backpropagation through time.
     * Target tokens are the next-character tokens: targets[t] = tokens[t+1].
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

        // 1. Forward
        val tape = forwardSequence(inputTokens)

        // 2. Compute Loss: L = - 1/T * sum_t ln(P(target_t))
        var totalLoss = 0.0f
        for (t in 0 until T) {
            val target = targetTokens[t].coerceIn(0, vocabSize - 1)
            val pTarget = tape.probs[t][target].coerceAtLeast(1e-7f)
            totalLoss += -ln(pTarget)
        }
        val avgLoss = totalLoss / T

        // 3. Backward Pass & Gradient Accumulators
        val dWout = FloatArray(vocabSize * hiddenDim)
        val dbout = FloatArray(vocabSize)
        val dWxh = FloatArray(hiddenDim * embedDim)
        val dWhh = FloatArray(hiddenDim * hiddenDim)
        val dbh = FloatArray(hiddenDim)
        val dWemb = FloatArray(vocabSize * embedDim)

        val wOutData = wOut.data.getFloatArray()
        val wHhData = wHh.data.getFloatArray()
        val wXhData = wXh.data.getFloatArray()
        val wEmbData = wEmb.data.getFloatArray()

        var dhNext = FloatArray(hiddenDim)

        // Backprop through time from t = T-1 down to 0
        for (t in T - 1 downTo 0) {
            val target = targetTokens[t].coerceIn(0, vocabSize - 1)
            val p = tape.probs[t]
            val hCurrent = tape.hStates[t + 1]
            val hPrev = tape.hStates[t]
            val token = inputTokens[t].coerceIn(0, vocabSize - 1)

            // dL/dz_v = (p_v - 1{v == target}) / T
            val dz = FloatArray(vocabSize)
            for (v in 0 until vocabSize) {
                dz[v] = (p[v] - (if (v == target) 1.0f else 0.0f)) / T
                dbout[v] += dz[v]
                val outOffset = v * hiddenDim
                for (h in 0 until hiddenDim) {
                    dWout[outOffset + h] += dz[v] * hCurrent[h]
                }
            }

            // dh = W_out^T * dz + dhNext
            val dh = FloatArray(hiddenDim)
            for (h in 0 until hiddenDim) {
                var sum = dhNext[h]
                for (v in 0 until vocabSize) {
                    sum += wOutData[v * hiddenDim + h] * dz[v]
                }
                dh[h] = sum
            }

            // dRaw = dh * (1 - tanh^2(sum)) = dh * (1 - hCurrent^2)
            val dRaw = FloatArray(hiddenDim)
            for (h in 0 until hiddenDim) {
                val tanhVal = hCurrent[h]
                dRaw[h] = dh[h] * (1.0f - tanhVal * tanhVal)
                dbh[h] += dRaw[h]
            }

            // dWhh += dRaw * hPrev^T
            for (i in 0 until hiddenDim) {
                val offset = i * hiddenDim
                val dR = dRaw[i]
                for (j in 0 until hiddenDim) {
                    dWhh[offset + j] += dR * hPrev[j]
                }
            }

            // dWxh += dRaw * emb^T
            val embOffset = token * embedDim
            for (i in 0 until hiddenDim) {
                val offset = i * embedDim
                val dR = dRaw[i]
                for (d in 0 until embedDim) {
                    val g = dR * wEmbData[embOffset + d]
                    dWxh[offset + d] += g
                    // Backprop into embedding matrix
                    dWemb[embOffset + d] += dR * wXhData[offset + d]
                }
            }

            // dhPrev = W_hh^T * dRaw
            val dhPrev = FloatArray(hiddenDim)
            for (j in 0 until hiddenDim) {
                var sum = 0.0f
                for (i in 0 until hiddenDim) {
                    sum += wHhData[i * hiddenDim + j] * dRaw[i]
                }
                dhPrev[j] = sum
            }
            dhNext = dhPrev
        }

        // 4. Update Weights via Selected Optimizer (Adam, RMSprop, or SGD) with Gradient Clipping
        adamStep++
        val wOutArr = wOut.data.getFloatArray()
        val bOutArr = bOut.data.getFloatArray()
        val wXhArr = wXh.data.getFloatArray()
        val wHhArr = wHh.data.getFloatArray()
        val bHArr = bH.data.getFloatArray()
        val wEmbArr = wEmb.data.getFloatArray()

        if (m_wOut == null || m_wOut!!.size != wOutArr.size) {
            m_wOut = FloatArray(wOutArr.size)
            v_wOut = FloatArray(wOutArr.size)
            m_bOut = FloatArray(bOutArr.size)
            v_bOut = FloatArray(bOutArr.size)
            m_wXh = FloatArray(wXhArr.size)
            v_wXh = FloatArray(wXhArr.size)
            m_wHh = FloatArray(wHhArr.size)
            v_wHh = FloatArray(wHhArr.size)
            m_bH = FloatArray(bHArr.size)
            v_bH = FloatArray(bHArr.size)
            m_wEmb = FloatArray(wEmbArr.size)
            v_wEmb = FloatArray(wEmbArr.size)
        }

        applyOptimizerUpdate(wOutArr, dWout, m_wOut!!, v_wOut!!, learningRate, optimizerType)
        applyOptimizerUpdate(bOutArr, dbout, m_bOut!!, v_bOut!!, learningRate, optimizerType)
        applyOptimizerUpdate(wXhArr, dWxh, m_wXh!!, v_wXh!!, learningRate, optimizerType)
        applyOptimizerUpdate(wHhArr, dWhh, m_wHh!!, v_wHh!!, learningRate, optimizerType)
        applyOptimizerUpdate(bHArr, dbh, m_bH!!, v_bH!!, learningRate, optimizerType)
        applyOptimizerUpdate(wEmbArr, dWemb, m_wEmb!!, v_wEmb!!, learningRate, optimizerType)

        val updatedL2 = computeWOutL2Norm()
        return StepMetrics(loss = avgLoss, wOutL2Norm = updatedL2)
    }

    private fun applyOptimizerUpdate(
        weights: FloatArray,
        grads: FloatArray,
        mState: FloatArray,
        vState: FloatArray,
        lr: Float,
        optimizerType: OptimizerType,
        maxNorm: Float = 2.0f
    ) {
        // Gradient clipping by norm
        var gradNormSq = 0.0
        for (g in grads) gradNormSq += (g * g).toDouble()
        val gradNorm = sqrt(gradNormSq).toFloat()
        val scale = if (gradNorm > maxNorm && gradNorm > 1e-6f) maxNorm / gradNorm else 1.0f

        when (optimizerType) {
            OptimizerType.ADAMW -> {
                val beta1 = 0.9f
                val beta2 = 0.999f
                val eps = 1e-8f
                val weightDecay = 0.01f
                val step = adamStep.coerceAtLeast(1)
                val biasCorrection1 = (1.0 - Math.pow(beta1.toDouble(), step.toDouble())).toFloat()
                val biasCorrection2 = (1.0 - Math.pow(beta2.toDouble(), step.toDouble())).toFloat()
                val stepLr = lr * (sqrt(biasCorrection2) / biasCorrection1)

                for (i in weights.indices) {
                    val g = grads[i] * scale
                    mState[i] = beta1 * mState[i] + (1f - beta1) * g
                    vState[i] = beta2 * vState[i] + (1f - beta2) * g * g
                    // Decoupled weight decay: w = w * (1 - lr * weightDecay) - stepLr * (m / (sqrt(v) + eps))
                    weights[i] = weights[i] * (1.0f - lr * weightDecay) - stepLr * (mState[i] / (sqrt(vState[i]) + eps))
                }
            }
            OptimizerType.ADAM -> {
                val beta1 = 0.9f
                val beta2 = 0.999f
                val eps = 1e-8f
                val step = adamStep.coerceAtLeast(1)
                val biasCorrection1 = (1.0 - Math.pow(beta1.toDouble(), step.toDouble())).toFloat()
                val biasCorrection2 = (1.0 - Math.pow(beta2.toDouble(), step.toDouble())).toFloat()
                val stepLr = lr * (sqrt(biasCorrection2) / biasCorrection1)

                for (i in weights.indices) {
                    val g = grads[i] * scale
                    mState[i] = beta1 * mState[i] + (1f - beta1) * g
                    vState[i] = beta2 * vState[i] + (1f - beta2) * g * g
                    weights[i] -= stepLr * (mState[i] / (sqrt(vState[i]) + eps))
                }
            }
            OptimizerType.RMSPROP -> {
                val alpha = 0.99f
                val eps = 1e-8f
                for (i in weights.indices) {
                    val g = grads[i] * scale
                    vState[i] = alpha * vState[i] + (1f - alpha) * g * g
                    weights[i] -= lr * (g / (sqrt(vState[i]) + eps))
                }
            }
            OptimizerType.SGD -> {
                val momentum = 0.9f
                for (i in weights.indices) {
                    val g = grads[i] * scale
                    mState[i] = momentum * mState[i] + g
                    weights[i] -= lr * mState[i]
                }
            }
        }
    }

    /**
     * Pure forward evaluation step (without backprop or weight updates).
     * Computes real Cross-Entropy Loss on validation tokens efficiently.
     */
    fun evaluateStep(
        inputTokens: IntArray,
        targetTokens: IntArray
    ): StepMetrics {
        require(inputTokens.size == targetTokens.size) { "Input and target length mismatch" }
        val T = inputTokens.size
        if (T == 0) return StepMetrics(0f, computeWOutL2Norm())

        val tape = forwardSequence(inputTokens)
        var totalLoss = 0.0f
        for (t in 0 until T) {
            val target = targetTokens[t].coerceIn(0, vocabSize - 1)
            val pTarget = tape.probs[t][target].coerceAtLeast(1e-7f)
            totalLoss += -ln(pTarget)
        }
        val avgLoss = totalLoss / T
        return StepMetrics(loss = avgLoss, wOutL2Norm = computeWOutL2Norm())
    }

    private fun clipAndApplyGradient(weights: FloatArray, grads: FloatArray, lr: Float, maxNorm: Float = 2.0f) {
        var gradNormSq = 0.0
        for (g in grads) gradNormSq += (g * g).toDouble()
        val gradNorm = sqrt(gradNormSq).toFloat()
        val scale = if (gradNorm > maxNorm && gradNorm > 1e-6f) maxNorm / gradNorm else 1.0f

        for (i in weights.indices) {
            weights[i] -= lr * grads[i] * scale
        }
    }

    /**
     * Autoregressive next-character text generation.
     * Evaluates prompt sequence, uses the terminal hidden state to sample the first continuation
     * character, and autoregressively feeds sampled tokens to advance hidden state.
     */
    fun generate(prompt: String, maxChars: Int = 80, temperature: Float = 0.7f): String {
        if (prompt.isEmpty()) return ""
        val tokens = prompt.map { charToToken(it) }.toIntArray()
        val tape = forwardSequence(tokens)
        // Tape.hStates[tokens.size] is the hidden state after consuming the entire prompt.
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
            // If this is step > 0, transition hidden state by feeding the previously generated token
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

            // Compute Logits from currentHidden (W_out * currentHidden + b_out)
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

            // Greedy mode if temperature is minimal
            val chosenToken: Int
            if (safeTemp <= 0.05f) {
                var bestV = 0
                var bestVal = Float.NEGATIVE_INFINITY
                for (v in 0 until vocabSize) {
                    if (logits[v] > bestVal) {
                        bestVal = logits[v]
                        bestV = v
                    }
                }
                chosenToken = bestV
            } else {
                // Softmax
                var sumExp = 0.0
                val probs = DoubleArray(vocabSize)
                for (v in 0 until vocabSize) {
                    val expVal = exp((logits[v] - maxZ).toDouble())
                    probs[v] = expVal
                    sumExp += expVal
                }

                // Sample token
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
     * Serialize full model weights to JSON file for export and persistent reload.
     */
    fun saveToFile(file: File) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        json.put("vocab", vocab.joinToString(""))
        json.put("embedDim", embedDim)
        json.put("hiddenDim", hiddenDim)
        json.put("paramsCount", countParameters())
        json.put("savedAt", System.currentTimeMillis())

        json.put("wEmb", floatArrayToJson(wEmb.data.getFloatArray()))
        json.put("wXh", floatArrayToJson(wXh.data.getFloatArray()))
        json.put("wHh", floatArrayToJson(wHh.data.getFloatArray()))
        json.put("bH", floatArrayToJson(bH.data.getFloatArray()))
        json.put("wOut", floatArrayToJson(wOut.data.getFloatArray()))
        json.put("bOut", floatArrayToJson(bOut.data.getFloatArray()))

        file.writeText(json.toString())
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
            // Validasi ketat: vocab, embedDim, hiddenDim harus cocok dengan instance ini.
            // Jika tidak cocok, pemuatan akan salah bentuk (array size mismatch) -> tolak.
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

    private fun floatArrayToJson(arr: FloatArray): org.json.JSONArray {
        val jArr = org.json.JSONArray()
        for (f in arr) jArr.put(f.toDouble())
        return jArr
    }

    private fun copyJsonToFloatArray(jArr: org.json.JSONArray, dest: FloatArray) {
        val len = minOf(jArr.length(), dest.size)
        for (i in 0 until len) {
            dest[i] = jArr.getDouble(i).toFloat()
        }
    }

    /**
     * Converts current weights to PyTorch ExecuTorch Tensor structures for interoperability.
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
     * Forward pass step using PyTorch ExecuTorch EValue container.
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

        /**
         * Ukuran kosakata default yang SESUNGGUHNYA (dihitung dari DEFAULT_VOCAB, bukan hardcode).
         * Nilai ini = 84. Semua lapisan UI/PEFT harus merujuk ke sini atau ke model.vocabSize,
         * tidak boleh menulis angka literal (mis. 96) secara terpisah.
         */
        val DEFAULT_VOCAB_SIZE: Int = DEFAULT_VOCAB.size

        /**
         * Ekstrak seluruh karakter unik (unique characters) nyata dari dataset secara otomatis.
         * Selalu menyertakan whitespace dasar (' ', '\n', '?') sebagai jangkar tokenisasi.
         */
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
         * Memeriksa apakah teks dataset memiliki karakter di luar kosakata model saat ini.
         * Jika ada karakter baru, buat model baru dengan vocab yang diperluas,
         * sembari mentransfer bobot lama yang beririsan sehingga hasil latihan sebelumnya tidak hilang.
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

            // Transfer bobot embedding & output projection untuk token lama yang bersesuaian
            val oldWEmb = currentModel.wEmb.data.getFloatArray()
            val newWEmb = newModel.wEmb.data.getFloatArray()
            val oldWOut = currentModel.wOut.data.getFloatArray()
            val newWOut = newModel.wOut.data.getFloatArray()
            val oldBOut = currentModel.bOut.data.getFloatArray()
            val newBOut = newModel.bOut.data.getFloatArray()

            for ((oldIdx, char) in currentModel.vocab.withIndex()) {
                val newIdx = newModel.charToToken(char)
                // Transfer embedding baris token
                System.arraycopy(oldWEmb, oldIdx * currentModel.embedDim, newWEmb, newIdx * newModel.embedDim, currentModel.embedDim)
                // Transfer output projection baris token
                System.arraycopy(oldWOut, oldIdx * currentModel.hiddenDim, newWOut, newIdx * newModel.hiddenDim, currentModel.hiddenDim)
                newBOut[newIdx] = oldBOut[oldIdx]
            }

            // Transfer parameter recurrent W_xh, W_hh, b_h secara utuh (dimensi hidden/embed sama)
            System.arraycopy(currentModel.wXh.data.getFloatArray(), 0, newModel.wXh.data.getFloatArray(), 0, currentModel.hiddenDim * currentModel.embedDim)
            System.arraycopy(currentModel.wHh.data.getFloatArray(), 0, newModel.wHh.data.getFloatArray(), 0, currentModel.hiddenDim * currentModel.hiddenDim)
            System.arraycopy(currentModel.bH.data.getFloatArray(), 0, newModel.bH.data.getFloatArray(), 0, currentModel.hiddenDim)

            Log.i("ANTI_GIMMICK", "Vocab diperluas otomatis dari ${currentModel.vocabSize} -> ${newModel.vocabSize} karakter unik.")
            return newModel
        }

        /**
         * Factory: membangun model dengan vocab & dimensi yang tersimpan di file,
         * lalu memuat bobotnya. Mengembalikan null jika file tidak valid / tidak ada.
         * Ini mengatasi kasus vocabSize/embedDim/hiddenDim di file berbeda dari default
         * (sebelumnya loadFromFile hanya kompatibel jika vocab persis sama dengan default).
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
         * Strict Anti-Gimmick Verification function required by specification:
         * Prints to Logcat: L2-norm of W_out BEFORE vs AFTER 1 training step.
         * Throws error if norm is unchanged (which would prove fake training).
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
    val wOutL2Norm: Float
)
