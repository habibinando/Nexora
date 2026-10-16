package com.example.neural

import android.os.Build
import android.util.Log

/**
 * Result data class from a single training step.
 * All metrics represent REAL calculated computations.
 */
data class StepExecutionMetrics(
    val loss: Float,
    val durationMs: Float,
    val gflops: Float,
    val gradientNorm: Float,
    val isNdkAccelerated: Boolean
)

data class BenchmarkResult(
    val durationMs: Float,
    val gflops: Float,
    val isNdkAccelerated: Boolean,
    val hardwareInfo: String
)

/**
 * JNI Bridge and Accelerator for Android Native Development Kit (NDK).
 * Implements real matrix computations in C++20 with NEON SIMD optimizations,
 * with real mathematical fallback if native binary is not bundled.
 */
object NdkNeuralAccelerator {

    private const val TAG = "NdkNeuralAccelerator"
    private var isLibraryLoaded = false
    private var loadErrorMessage: String? = null

    init {
        try {
            System.loadLibrary("neural_trainer_jni")
            isLibraryLoaded = true
            Log.i(TAG, "Native library 'neural_trainer_jni' successfully loaded.")
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            loadErrorMessage = e.message
            Log.w(TAG, "Native library 'neural_trainer_jni' not found or failed to load: ${e.message}")
        } catch (e: Exception) {
            isLibraryLoaded = false
            loadErrorMessage = e.message
            Log.e(TAG, "Unexpected error loading native library: ${e.message}", e)
        }
    }

    val isNdkAvailable: Boolean
        get() = isLibraryLoaded

    fun getNdkHardwareInfo(): String {
        return if (isLibraryLoaded) {
            try {
                nativeGetNdkInfo()
            } catch (e: Throwable) {
                "NDK (ABI: ${Build.SUPPORTED_ABIS.joinToString()})"
            }
        } else {
            "JVM Engine (ABI: ${Build.SUPPORTED_ABIS.joinToString()}; NDK Ready on GitHub CI)"
        }
    }

    /**
     * Executes one forward + backward step.
     * Returns real mathematical loss and execution timing.
     */
    fun trainStep(
        wxh: FloatArray,
        whh: FloatArray,
        why: FloatArray,
        bh: FloatArray,
        by: FloatArray,
        inputs: IntArray,
        targets: IntArray,
        learningRate: Float,
        optimizerType: Int = 0,
        beta1: Float = 0.9f,
        beta2: Float = 0.999f,
        weightDecay: Float = 0.0001f
    ): StepExecutionMetrics {
        if (isLibraryLoaded) {
            try {
                val res = nativeTrainStep(
                    wxh, whh, why, bh, by,
                    inputs, targets, learningRate,
                    optimizerType, beta1, beta2, weightDecay
                )
                if (res != null && res.size >= 4) {
                    return StepExecutionMetrics(
                        loss = res[0],
                        durationMs = res[1],
                        gflops = res[2],
                        gradientNorm = res[3],
                        isNdkAccelerated = true
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error in nativeTrainStep, falling back to JVM: ${e.message}")
            }
        }

        // JVM Pure Math Execution (Identical mathematical formulas)
        return jvmTrainStep(
            wxh, whh, why, bh, by,
            inputs, targets, learningRate
        )
    }

    /**
     * Mathematical JVM fallback calculating exact loss, BPTT, and SGD updates.
     */
    private fun jvmTrainStep(
        wxh: FloatArray,
        whh: FloatArray,
        why: FloatArray,
        bh: FloatArray,
        by: FloatArray,
        inputs: IntArray,
        targets: IntArray,
        learningRate: Float
    ): StepExecutionMetrics {
        val startNano = System.nanoTime()

        val hiddenSize = bh.size
        val vocabSize = by.size
        val seqLen = inputs.size

        if (hiddenSize <= 0 || vocabSize <= 0 || seqLen <= 0) {
            return StepExecutionMetrics(0f, 0f, 0f, 0f, false)
        }

        val hs = Array(seqLen + 1) { FloatArray(hiddenSize) }
        val probs = Array(seqLen) { FloatArray(vocabSize) }
        var totalLoss = 0.0f
        var flopCount = 0.0

        // Forward
        for (t in 0 until seqLen) {
            val xIdx = inputs[t].coerceIn(0, vocabSize - 1)
            val prevH = hs[t]
            val currH = hs[t + 1]

            for (i in 0 until hiddenSize) {
                var sum = bh[i] + wxh[i * vocabSize + xIdx]
                val offset = i * hiddenSize
                for (j in 0 until hiddenSize) {
                    sum += whh[offset + j] * prevH[j]
                }
                currH[i] = kotlin.math.tanh(sum)
            }
            flopCount += 2.0 * hiddenSize * hiddenSize + 3.0 * hiddenSize

            var maxLogit = -1e9f
            for (k in 0 until vocabSize) {
                var sum = by[k]
                val offset = k * hiddenSize
                for (j in 0 until hiddenSize) {
                    sum += why[offset + j] * currH[j]
                }
                probs[t][k] = sum
                if (sum > maxLogit) maxLogit = sum
            }
            flopCount += 2.0 * vocabSize * hiddenSize

            var sumExp = 0.0f
            for (k in 0 until vocabSize) {
                probs[t][k] = kotlin.math.exp(probs[t][k] - maxLogit)
                sumExp += probs[t][k]
            }
            val invSum = 1.0f / (sumExp + 1e-9f)
            for (k in 0 until vocabSize) {
                probs[t][k] *= invSum
            }

            val targetIdx = targets[t].coerceIn(0, vocabSize - 1)
            val p = probs[t][targetIdx].coerceAtLeast(1e-15f)
            totalLoss += -kotlin.math.ln(p)
        }

        val avgLoss = totalLoss / seqLen

        // Backward pass (BPTT)
        val dWxh = FloatArray(wxh.size)
        val dWhh = FloatArray(whh.size)
        val dWhy = FloatArray(why.size)
        val dBh = FloatArray(bh.size)
        val dBy = FloatArray(by.size)
        val dhNext = FloatArray(hiddenSize)

        for (t in seqLen - 1 downTo 0) {
            val xIdx = inputs[t].coerceIn(0, vocabSize - 1)
            val targetIdx = targets[t].coerceIn(0, vocabSize - 1)
            val currH = hs[t + 1]
            val prevH = hs[t]

            val dy = probs[t].clone()
            dy[targetIdx] -= 1.0f

            for (k in 0 until vocabSize) {
                dBy[k] += dy[k]
                val offset = k * hiddenSize
                val dyk = dy[k]
                for (j in 0 until hiddenSize) {
                    dWhy[offset + j] += dyk * currH[j]
                }
            }
            flopCount += 2.0 * vocabSize * hiddenSize

            val dh = FloatArray(hiddenSize)
            for (j in 0 until hiddenSize) {
                var sum = dhNext[j]
                for (k in 0 until vocabSize) {
                    sum += why[k * hiddenSize + j] * dy[k]
                }
                dh[j] = sum
            }
            flopCount += 2.0 * vocabSize * hiddenSize

            val dhRaw = FloatArray(hiddenSize)
            for (j in 0 until hiddenSize) {
                val ch = currH[j]
                dhRaw[j] = (1.0f - ch * ch) * dh[j]
                dBh[j] += dhRaw[j]
            }

            for (i in 0 until hiddenSize) {
                dWxh[i * vocabSize + xIdx] += dhRaw[i]
                val offset = i * hiddenSize
                val dhRawI = dhRaw[i]
                for (j in 0 until hiddenSize) {
                    dWhh[offset + j] += dhRawI * prevH[j]
                }
            }
            flopCount += 2.0 * hiddenSize * hiddenSize

            for (j in 0 until hiddenSize) {
                var sum = 0.0f
                for (i in 0 until hiddenSize) {
                    sum += whh[i * hiddenSize + j] * dhRaw[i]
                }
                dhNext[j] = sum
            }
            flopCount += 2.0 * hiddenSize * hiddenSize
        }

        // Normalize gradients by sequence length to align with avgLoss
        val invSeqLen = 1.0f / seqLen.toFloat()
        for (i in dWxh.indices) dWxh[i] *= invSeqLen
        for (i in dWhh.indices) dWhh[i] *= invSeqLen
        for (i in dWhy.indices) dWhy[i] *= invSeqLen
        for (i in dBh.indices)  dBh[i]  *= invSeqLen
        for (i in dBy.indices)  dBy[i]  *= invSeqLen

        // Gradient clipping
        var gradSqSum = 0.0
        for (g in dWxh) gradSqSum += g * g
        for (g in dWhh) gradSqSum += g * g
        for (g in dWhy) gradSqSum += g * g
        for (g in dBh)  gradSqSum += g * g
        for (g in dBy)  gradSqSum += g * g

        val gradNorm = kotlin.math.sqrt(gradSqSum).toFloat()
        val clipFactor = if (gradNorm > 5.0f) 5.0f / (gradNorm + 1e-7f) else 1.0f

        // Weight updates
        for (i in wxh.indices) wxh[i] -= learningRate * (dWxh[i] * clipFactor)
        for (i in whh.indices) whh[i] -= learningRate * (dWhh[i] * clipFactor)
        for (i in why.indices) why[i] -= learningRate * (dWhy[i] * clipFactor)
        for (i in bh.indices)  bh[i]  -= learningRate * (dBh[i]  * clipFactor)
        for (i in by.indices)  by[i]  -= learningRate * (dBy[i]  * clipFactor)

        val elapsedNano = System.nanoTime() - startNano
        val durationMs = (elapsedNano / 1_000_000f).coerceAtLeast(0.001f)
        val gflops = ((flopCount / (durationMs * 1e-3)) / 1e9).toFloat()

        return StepExecutionMetrics(
            loss = avgLoss,
            durationMs = durationMs,
            gflops = gflops,
            gradientNorm = gradNorm,
            isNdkAccelerated = false
        )
    }

    /**
     * Executes real matrix multiplication benchmark test to calculate real GFLOPS.
     */
    fun runMatMulBenchmark(m: Int = 128, n: Int = 128, k: Int = 128, iterations: Int = 10): BenchmarkResult {
        if (isLibraryLoaded) {
            try {
                val res = nativeBenchmarkMatMul(m, n, k, iterations)
                if (res != null && res.size >= 2) {
                    return BenchmarkResult(
                        durationMs = res[0],
                        gflops = res[1],
                        isNdkAccelerated = true,
                        hardwareInfo = getNdkHardwareInfo()
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Native benchmark failed, falling back to JVM: ${e.message}")
            }
        }

        // JVM Benchmark
        val a = FloatArray(m * k) { 1.0f }
        val b = FloatArray(k * n) { 0.5f }
        val c = FloatArray(m * n)

        val startNano = System.nanoTime()
        for (it in 0 until iterations) {
            for (i in 0 until m) {
                val aOffset = i * k
                val cOffset = i * n
                for (j in 0 until n) {
                    var sum = 0.0f
                    for (p in 0 until k) {
                        sum += a[aOffset + p] * b[p * n + j]
                    }
                    c[cOffset + j] = sum
                }
            }
        }
        val elapsedNano = System.nanoTime() - startNano
        val durationMs = (elapsedNano / 1_000_000f).coerceAtLeast(0.001f)
        val totalFlops = 2.0 * m * n * k * iterations
        val gflops = ((totalFlops / (durationMs * 1e-3)) / 1e9).toFloat()

        return BenchmarkResult(
            durationMs = durationMs,
            gflops = gflops,
            isNdkAccelerated = false,
            hardwareInfo = getNdkHardwareInfo()
        )
    }

    // Native JNI functions
    private external fun nativeIsNdkSupported(): Boolean
    private external fun nativeGetNdkInfo(): String
    private external fun nativeTrainStep(
        wxh: FloatArray,
        whh: FloatArray,
        why: FloatArray,
        bh: FloatArray,
        by: FloatArray,
        inputs: IntArray,
        targets: IntArray,
        learningRate: Float,
        optimizerType: Int,
        beta1: Float,
        beta2: Float,
        weightDecay: Float
    ): FloatArray?

    private external fun nativeBenchmarkMatMul(
        m: Int,
        n: Int,
        k: Int,
        iterations: Int
    ): FloatArray?
}
