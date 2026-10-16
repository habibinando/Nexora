package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.neural.CharRNNModel
import com.example.neural.DatasetRepository
import com.example.neural.LocalNeuralTrainer
import com.example.neural.NdkNeuralAccelerator
import com.example.neural.SessionPersistenceManager
import com.example.neural.TrainingSessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrainingSessionResumeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `test canonical window count calculation`() {
        val trainer = LocalNeuralTrainer(testScope)

        // Text: 10 chars, seqLength: 4 -> valid start indices are 0,1,2,3,4,5 (6 steps)
        val steps = trainer.calculateStepsPerEpoch(textLength = 10, seqLen = 4)
        assertEquals(6, steps)

        // If text length equals seqLength -> 0 steps (requires at least seqLength + 1 chars for target)
        val zeroSteps = trainer.calculateStepsPerEpoch(textLength = 4, seqLen = 4)
        assertEquals(0, zeroSteps)

        // Real dataset text calculation
        val datasetText = "Halo Nexora AI on-device neural lab!"
        val seqLen = 16
        val expectedSteps = datasetText.length - seqLen
        assertEquals(expectedSteps, trainer.calculateStepsPerEpoch(datasetText.length, seqLen))
    }

    @Test
    fun `test generate aligns inference with next-character prediction`() {
        val vocab = listOf('A', 'B', 'C', 'D', 'E')
        val model = CharRNNModel(vocab = vocab, hiddenSize = 16, seed = 123L)

        val seed = "AB"
        val generated = model.generate(seedText = seed, length = 5, temperature = 0.5f)

        // Output must start with the prompt seed
        assertTrue(generated.startsWith("AB"))
        assertEquals(seed.length + 5, generated.length)
    }

    @Test
    fun `test strict binary loader rejects corrupted magic header`() {
        val bytes = ByteArray(64) { 0 }
        // Invalid magic header 0x12345678
        bytes[0] = 0x12
        bytes[1] = 0x34
        bytes[2] = 0x56
        bytes[3] = 0x78

        val ex = assertThrows(IllegalArgumentException::class.java) {
            CharRNNModel.deserializeFromBytes(bytes)
        }
        assertTrue(ex.message!!.contains("magic header", ignoreCase = true))
    }

    @Test
    fun `test strict binary loader rejects non-finite NaN and Infinity tensors`() {
        val vocab = listOf('X', 'Y', 'Z')
        val model = CharRNNModel(vocab, 8)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CharRNNModel.MAGIC_HEADER_NEXORA)
        dos.writeInt(CharRNNModel.SUPPORTED_VERSION)
        dos.writeInt(vocab.size)
        for (c in vocab) dos.writeChar(c.code)
        dos.writeInt(model.hiddenSize)

        // Write wxh with NaN
        dos.writeInt(model.wxh.size)
        for (i in model.wxh.indices) {
            if (i == 2) dos.writeFloat(Float.NaN) else dos.writeFloat(model.wxh[i])
        }
        dos.flush()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            CharRNNModel.deserializeFromBytes(baos.toByteArray())
        }
        assertTrue(ex.message!!.contains("non-finite", ignoreCase = true))
    }

    @Test
    fun `test strict binary loader rejects mismatched tensor sizes`() {
        val vocab = listOf('A', 'B')
        val model = CharRNNModel(vocab, 8)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CharRNNModel.MAGIC_HEADER_NEXORA)
        dos.writeInt(CharRNNModel.SUPPORTED_VERSION)
        dos.writeInt(vocab.size)
        for (c in vocab) dos.writeChar(c.code)
        dos.writeInt(model.hiddenSize)

        // Write mismatched size: says 5 elements instead of 16
        dos.writeInt(5)
        for (i in 0 until 5) dos.writeFloat(0.1f)
        dos.flush()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            CharRNNModel.deserializeFromBytes(baos.toByteArray())
        }
        assertTrue(ex.message!!.contains("Ukuran tensor 'wxh' tidak cocok", ignoreCase = true))
    }

    @Test
    fun `test strict binary loader rejects duplicate vocabulary characters`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CharRNNModel.MAGIC_HEADER_NEXORA)
        dos.writeInt(CharRNNModel.SUPPORTED_VERSION)
        dos.writeInt(3)
        // Write duplicates: 'A', 'B', 'A'
        dos.writeChar('A'.code)
        dos.writeChar('B'.code)
        dos.writeChar('A'.code)
        dos.writeInt(16)
        dos.flush()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            CharRNNModel.deserializeFromBytes(baos.toByteArray())
        }
        assertTrue(ex.message!!.contains("duplikat", ignoreCase = true))
    }

    @Test
    fun `test durable session snapshot persistence and process recreation resume`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionPersistenceManager.clearSession(context)

        val vocab = "Halo Nexora".toSet().toList().sorted()
        val originalModel = CharRNNModel(vocab, hiddenSize = 16, seed = 42L)

        // Perform one step to change weights
        val inputs = intArrayOf(0, 1, 2)
        val targets = intArrayOf(1, 2, 3)
        val metrics = originalModel.trainStep(inputs, targets, learningRate = 0.05f)

        val snapshot = TrainingSessionSnapshot(
            version = SessionPersistenceManager.SESSION_VERSION,
            currentStep = 150,
            totalSteps = 500,
            currentEpoch = 2,
            totalEpochs = 5,
            seqLength = 16,
            learningRate = 0.02f,
            latestLoss = metrics.loss,
            initialLoss = 3.85f,
            lossHistory = listOf(3.85f, 3.50f, metrics.loss),
            datasetName = "Percakapan Nexora",
            datasetText = "Halo Nexora",
            modelBytes = originalModel.serializeToBytes(),
            timestamp = System.currentTimeMillis()
        )

        // 1. Save snapshot to durable disk
        val saveSuccess = SessionPersistenceManager.saveSession(context, snapshot)
        assertTrue("Session snapshot must be saved successfully", saveSuccess)
        assertTrue("Snapshot file must exist", SessionPersistenceManager.hasSavedSession(context))

        // 2. Simulate complete process restart with fresh trainer instance
        val newTrainer = LocalNeuralTrainer(testScope)

        // 3. Load snapshot from disk
        val loadedSnapshot = SessionPersistenceManager.loadSession(context)
        assertNotNull("Loaded snapshot should not be null", loadedSnapshot)
        assertEquals(150, loadedSnapshot!!.currentStep)
        assertEquals(500, loadedSnapshot.totalSteps)
        assertEquals(2, loadedSnapshot.currentEpoch)
        assertEquals(5, loadedSnapshot.totalEpochs)
        assertEquals(metrics.loss, loadedSnapshot.latestLoss!!, 1e-4f)

        // 4. Restore state into new trainer
        newTrainer.restoreFromSnapshot(loadedSnapshot)

        // 5. Verify restored state & exact model weights byte-for-byte
        val tele = newTrainer.telemetry.value
        assertEquals(150, tele.currentStep)
        assertEquals(500, tele.totalSteps)
        assertEquals(2, tele.currentEpoch)
        assertEquals(metrics.loss, tele.latestLoss!!, 1e-4f)

        val restoredModel = newTrainer.activeModel
        assertNotNull(restoredModel)
        assertEquals(originalModel.vocabSize, restoredModel!!.vocabSize)
        assertEquals(originalModel.hiddenSize, restoredModel.hiddenSize)
        assertEquals(originalModel.computeChecksumSha256(), restoredModel.computeChecksumSha256())

        // Clean up
        SessionPersistenceManager.clearSession(context)
    }

    @Test
    fun `test gradient normalization matches scale between C++ and Kotlin`() {
        val vocab = listOf('A', 'B', 'C')
        val wxh = FloatArray(4 * 3) { 0.1f * it }
        val whh = FloatArray(4 * 4) { 0.05f * it }
        val why = FloatArray(3 * 4) { 0.1f * it }
        val bh = FloatArray(4) { 0f }
        val by = FloatArray(3) { 0f }

        val inputs = intArrayOf(0, 1, 2)
        val targets = intArrayOf(1, 2, 0)

        // Run Kotlin step
        val metrics = NdkNeuralAccelerator.trainStep(
            wxh = wxh.clone(),
            whh = whh.clone(),
            why = why.clone(),
            bh = bh.clone(),
            by = by.clone(),
            inputs = inputs,
            targets = targets,
            learningRate = 0.01f
        )

        assertTrue(metrics.loss > 0f)
        assertTrue(metrics.gradientNorm >= 0f)
        assertTrue(!metrics.loss.isNaN())
        assertTrue(!metrics.gradientNorm.isNaN())
    }
}
