package com.example.neural

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Real On-Device Recurrent Neural Network (Nexora Char-RNN) Model.
 * Computes real forward inference, real parameter counts, and strict binary serialization.
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
        require(vocabSize in 1..MAX_ALLOWED_VOCAB) { "Vocab size $vocabSize harus antara 1 dan $MAX_ALLOWED_VOCAB" }
        require(hiddenSize in MIN_ALLOWED_HIDDEN..MAX_ALLOWED_HIDDEN) { "Hidden size $hiddenSize harus antara $MIN_ALLOWED_HIDDEN dan $MAX_ALLOWED_HIDDEN" }

        val rand = Random(seed)
        // Xavier / Glorot initialization
        val stdWxh = 1.0f / sqrt(vocabSize.coerceAtLeast(1).toFloat())
        for (i in wxh.indices) wxh[i] = (rand.nextGaussian() * stdWxh).toFloat()

        val stdWhh = 1.0f / sqrt(hiddenSize.coerceAtLeast(1).toFloat())
        for (i in whh.indices) whh[i] = (rand.nextGaussian() * stdWhh).toFloat()

        val stdWhy = 1.0f / sqrt(hiddenSize.coerceAtLeast(1).toFloat())
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
     * Aligns inference state with target next-character prediction:
     * 1. Seed primes hidden state h up to seedText.last().
     * 2. Logits for the next character are computed directly from this primed h.
     * 3. Once nextChar is sampled, h is advanced by forwardOneStep(nextChar, h) for subsequent steps.
     */
    fun generate(
        seedText: String,
        length: Int = 50,
        temperature: Float = 0.8f
    ): String {
        if (vocab.isEmpty() || length <= 0) return seedText

        var h = FloatArray(hiddenSize)

        // 1. Prime the hidden state with the seedText without double-processing
        if (seedText.isNotEmpty()) {
            for (c in seedText) {
                val idx = charToIdx[c] ?: 0
                h = forwardOneStep(idx, h)
            }
        } else {
            // Default priming if seed is empty
            h = forwardOneStep(0, h)
        }

        val sb = StringBuilder(seedText)
        val effectiveTemp = temperature.coerceIn(0.1f, 2.0f)
        val rand = Random()

        // 2. Autoregressive generation
        for (step in 0 until length) {
            // Compute output projection logits directly from current context h
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

            // Softmax with temperature scaling
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

            // Advance hidden state with the newly sampled token for the next prediction
            h = forwardOneStep(chosenIdx, h)
        }

        return sb.toString()
    }

    /**
     * Computes real SHA-256 hash of all model weights to ensure verifiable checkpoint integrity.
     */
    fun computeChecksumSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun hashFloats(arr: FloatArray) {
            val byteBuf = java.nio.ByteBuffer.allocate(arr.size * 4)
            for (f in arr) byteBuf.putFloat(f)
            digest.update(byteBuf.array())
        }
        hashFloats(wxh)
        hashFloats(whh)
        hashFloats(why)
        hashFloats(bh)
        hashFloats(by)
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    fun forwardOneStep(xIdx: Int, prevH: FloatArray): FloatArray {
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
        serializeToStream(baos)
        return baos.toByteArray()
    }

    fun serializeToStream(outputStream: OutputStream) {
        val dos = DataOutputStream(outputStream)
        dos.writeInt(MAGIC_HEADER_NEXORA)
        dos.writeInt(SUPPORTED_VERSION)
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
    }

    companion object {
        const val MAGIC_HEADER_NEXORA = 0x4E45584F // "NEXO"
        const val MAGIC_HEADER_LEGACY = 0x4E455552 // "NEUR"
        const val SUPPORTED_VERSION = 1

        const val MAX_ALLOWED_VOCAB = 10_000
        const val MIN_ALLOWED_HIDDEN = 4
        const val MAX_ALLOWED_HIDDEN = 2048

        fun deserializeFromBytes(bytes: ByteArray): CharRNNModel {
            return deserializeFromStream(ByteArrayInputStream(bytes))
        }

        /**
         * Strictly validates magic header, version, vocabulary boundaries,
         * tensor dimensions, truncated stream EOF, and finite float values.
         */
        fun deserializeFromStream(inputStream: InputStream): CharRNNModel {
            val dis = DataInputStream(inputStream)
            try {
                val magic = dis.readInt()
                if (magic != MAGIC_HEADER_NEXORA && magic != MAGIC_HEADER_LEGACY) {
                    throw IllegalArgumentException("Format file model tidak dikenal (magic header 0x${Integer.toHexString(magic).uppercase()} tidak valid).")
                }

                val version = dis.readInt()
                if (version != SUPPORTED_VERSION) {
                    throw IllegalArgumentException("Versi model ($version) tidak didukung. Versi yang didukung adalah $SUPPORTED_VERSION.")
                }

                val vocabSize = dis.readInt()
                if (vocabSize <= 0 || vocabSize > MAX_ALLOWED_VOCAB) {
                    throw IllegalArgumentException("Dimensi vocabulary ($vocabSize) di luar batas aman (1..$MAX_ALLOWED_VOCAB).")
                }

                val vocabList = ArrayList<Char>(vocabSize)
                val uniqueCheck = HashSet<Char>(vocabSize)
                for (i in 0 until vocabSize) {
                    val c = dis.readChar()
                    if (!uniqueCheck.add(c)) {
                        throw IllegalArgumentException("Karakter duplikat dalam vocabulary ditemukan: '$c'.")
                    }
                    vocabList.add(c)
                }

                val hiddenSize = dis.readInt()
                if (hiddenSize < MIN_ALLOWED_HIDDEN || hiddenSize > MAX_ALLOWED_HIDDEN) {
                    throw IllegalArgumentException("Dimensi hidden size ($hiddenSize) di luar batas aman ($MIN_ALLOWED_HIDDEN..$MAX_ALLOWED_HIDDEN).")
                }

                val model = CharRNNModel(vocabList, hiddenSize)

                fun readFloatsStrict(tensorName: String, target: FloatArray) {
                    val size = dis.readInt()
                    if (size != target.size) {
                        throw IllegalArgumentException("Ukuran tensor '$tensorName' tidak cocok: diharapkan ${target.size} float, ditemukan $size float.")
                    }
                    for (i in target.indices) {
                        val f = dis.readFloat()
                        if (f.isNaN() || f.isInfinite()) {
                            throw IllegalArgumentException("Nilai non-finite (NaN/Inf) terdeteksi pada tensor '$tensorName' indeks $i: $f.")
                        }
                        target[i] = f
                    }
                }

                readFloatsStrict("wxh", model.wxh)
                readFloatsStrict("whh", model.whh)
                readFloatsStrict("why", model.why)
                readFloatsStrict("bh", model.bh)
                readFloatsStrict("by", model.by)

                return model
            } catch (e: EOFException) {
                throw IllegalArgumentException("Data file model terpotong sebelum semua bobot selesai dibaca (stream truncated).", e)
            }
        }
    }
}
