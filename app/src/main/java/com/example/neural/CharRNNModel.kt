package com.example.neural

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Real On-Device Recurrent Neural Network (Char-RNN) Model.
 * Computes real forward inference, real parameter counts, and real serialized sizes.
 */
class CharRNNModel(
    val vocab: List<Char>,
    val hiddenSize: Int = 64,
    val seed: Long = 42L
) {
    val vocabSize: Int = vocab.size
    val charToIdx: Map<Char, Int> = vocab.mapIndexed { index, c -> c to index }.toMap()
    val idxToChar: Map<Int, Char> = vocab.mapIndexed { index, c -> index to c }.toMap()

    // Real neural network weights
    val wxh: FloatArray = FloatArray(hiddenSize * vocabSize)
    val whh: FloatArray = FloatArray(hiddenSize * hiddenSize)
    val why: FloatArray = FloatArray(vocabSize * hiddenSize)
    val bh: FloatArray = FloatArray(hiddenSize)
    val by: FloatArray = FloatArray(vocabSize)

    val totalParameterCount: Long
        get() = (wxh.size + whh.size + why.size + bh.size + by.size).toLong()

    init {
        val rand = Random(seed)
        // Xavier / Glorot initialization
        val stdWxh = 1.0f / sqrt(vocabSize.toFloat())
        for (i in wxh.indices) wxh[i] = (rand.nextGaussian() * stdWxh).toFloat()

        val stdWhh = 1.0f / sqrt(hiddenSize.toFloat())
        for (i in whh.indices) whh[i] = (rand.nextGaussian() * stdWhh).toFloat()

        val stdWhy = 1.0f / sqrt(hiddenSize.toFloat())
        for (i in why.indices) why[i] = (rand.nextGaussian() * stdWhy).toFloat()
    }

    /**
     * Executes one training step using NdkNeuralAccelerator.
     */
    fun trainStep(
        inputIndices: IntArray,
        targetIndices: IntArray,
        learningRate: Float
    ): StepExecutionMetrics {
        return NdkNeuralAccelerator.trainStep(
            wxh = wxh,
            whh = whh,
            why = why,
            bh = bh,
            by = by,
            inputs = inputIndices,
            targets = targetIndices,
            learningRate = learningRate
        )
    }

    /**
     * Generates text continuation token by token using real forward inference.
     */
    fun generate(
        seedText: String,
        length: Int = 50,
        temperature: Float = 0.8f
    ): String {
        if (vocab.isEmpty() || length <= 0) return seedText

        var h = FloatArray(hiddenSize)
        val sb = StringBuilder(seedText)
        var lastChar = seedText.lastOrNull() ?: vocab.first()

        // Prime the hidden state with the seed
        for (c in seedText) {
            val idx = charToIdx[c] ?: 0
            h = forwardOneStep(idx, h)
        }

        val effectiveTemp = temperature.coerceIn(0.1f, 2.0f)
        val rand = Random()

        for (step in 0 until length) {
            val idx = charToIdx[lastChar] ?: 0
            h = forwardOneStep(idx, h)

            // Compute output logits
            val logits = FloatArray(vocabSize)
            var maxLogit = -1e9f
            for (k in 0 until vocabSize) {
                var sum = by[k]
                val offset = k * hiddenSize
                for (j in 0 until hiddenSize) {
                    sum += why[offset + j] * h[j]
                }
                logits[k] = sum / effectiveTemp
                if (logits[k] > maxLogit) maxLogit = logits[k]
            }

            // Softmax with temperature
            var sumExp = 0.0f
            for (k in 0 until vocabSize) {
                logits[k] = exp(logits[k] - maxLogit)
                sumExp += logits[k]
            }

            // Sample next character
            val r = rand.nextFloat() * sumExp
            var cumulative = 0.0f
            var chosenIdx = vocabSize - 1
            for (k in 0 until vocabSize) {
                cumulative += logits[k]
                if (cumulative >= r) {
                    chosenIdx = k
                    break
                }
            }

            val nextChar = idxToChar[chosenIdx] ?: ' '
            sb.append(nextChar)
            lastChar = nextChar
        }

        return sb.toString()
    }

    private fun forwardOneStep(xIdx: Int, prevH: FloatArray): FloatArray {
        val nextH = FloatArray(hiddenSize)
        val clampedIdx = xIdx.coerceIn(0, vocabSize - 1)
        for (i in 0 until hiddenSize) {
            var sum = bh[i] + wxh[i * vocabSize + clampedIdx]
            val offset = i * hiddenSize
            for (j in 0 until hiddenSize) {
                sum += whh[offset + j] * prevH[j]
            }
            nextH[i] = kotlin.math.tanh(sum)
        }
        return nextH
    }

    /**
     * Serializes model weights to a real binary byte array for checkpoint storage.
     */
    fun serializeToBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(MAGIC_HEADER)
        dos.writeInt(1) // version
        dos.writeInt(vocabSize)
        for (c in vocab) {
            dos.writeChar(c.code)
        }
        dos.writeInt(hiddenSize)

        fun writeFloats(arr: FloatArray) {
            dos.writeInt(arr.size)
            for (f in arr) dos.writeFloat(f)
        }

        writeFloats(wxh)
        writeFloats(whh)
        writeFloats(why)
        writeFloats(bh)
        writeFloats(by)

        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        const val MAGIC_HEADER = 0x4E455552 // "NEUR"

        fun deserializeFromBytes(bytes: ByteArray): CharRNNModel {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val magic = dis.readInt()
            require(magic == MAGIC_HEADER) { "Invalid model checkpoint file" }
            val version = dis.readInt()
            val vocabSize = dis.readInt()
            val vocabList = ArrayList<Char>(vocabSize)
            for (i in 0 until vocabSize) {
                vocabList.add(dis.readChar())
            }
            val hiddenSize = dis.readInt()

            val model = CharRNNModel(vocabList, hiddenSize)

            fun readFloats(target: FloatArray) {
                val size = dis.readInt()
                for (i in 0 until size.coerceAtMost(target.size)) {
                    target[i] = dis.readFloat()
                }
            }

            readFloats(model.wxh)
            readFloats(model.whh)
            readFloats(model.why)
            readFloats(model.bh)
            readFloats(model.by)

            return model
        }
    }
}
