package com.example.neural

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class TrainingSessionSnapshot(
    val version: Int = 1,
    val currentStep: Int,
    val totalSteps: Int,
    val currentEpoch: Int,
    val totalEpochs: Int,
    val seqLength: Int,
    val learningRate: Float,
    val latestLoss: Float?,
    val initialLoss: Float?,
    val lossHistory: List<Float>,
    val datasetName: String,
    val datasetText: String,
    val modelBytes: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrainingSessionSnapshot

        if (version != other.version) return false
        if (currentStep != other.currentStep) return false
        if (totalSteps != other.totalSteps) return false
        if (currentEpoch != other.currentEpoch) return false
        if (totalEpochs != other.totalEpochs) return false
        if (seqLength != other.seqLength) return false
        if (learningRate != other.learningRate) return false
        if (latestLoss != other.latestLoss) return false
        if (initialLoss != other.initialLoss) return false
        if (lossHistory != other.lossHistory) return false
        if (datasetName != other.datasetName) return false
        if (datasetText != other.datasetText) return false
        if (!modelBytes.contentEquals(other.modelBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + currentStep
        result = 31 * result + totalSteps
        result = 31 * result + currentEpoch
        result = 31 * result + totalEpochs
        result = 31 * result + seqLength
        result = 31 * result + learningRate.hashCode()
        result = 31 * result + (latestLoss?.hashCode() ?: 0)
        result = 31 * result + (initialLoss?.hashCode() ?: 0)
        result = 31 * result + lossHistory.hashCode()
        result = 31 * result + datasetName.hashCode()
        result = 31 * result + datasetText.hashCode()
        result = 31 * result + modelBytes.contentHashCode()
        return result
    }
}

object SessionPersistenceManager {
    const val MAGIC_HEADER_SESSION = 0x53455353 // "SESS"
    const val SESSION_VERSION = 1
    private const val SESSION_FILENAME = "active_training_session.bin"

    private fun getSessionFile(context: Context): File {
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, SESSION_FILENAME)
    }

    fun hasSavedSession(context: Context): Boolean {
        val file = getSessionFile(context)
        return file.exists() && file.length() > 0
    }

    /**
     * Atomically saves session snapshot to disk on Dispatchers.IO with fsync.
     */
    suspend fun saveSession(context: Context, snapshot: TrainingSessionSnapshot): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getSessionFile(context)
            val tempFile = File(file.parentFile, "${file.name}.tmp_${System.nanoTime()}")

            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)

            dos.writeInt(MAGIC_HEADER_SESSION)
            dos.writeInt(SESSION_VERSION)
            dos.writeInt(snapshot.currentStep)
            dos.writeInt(snapshot.totalSteps)
            dos.writeInt(snapshot.currentEpoch)
            dos.writeInt(snapshot.totalEpochs)
            dos.writeInt(snapshot.seqLength)
            dos.writeFloat(snapshot.learningRate)

            dos.writeBoolean(snapshot.latestLoss != null)
            snapshot.latestLoss?.let { dos.writeFloat(it) }

            dos.writeBoolean(snapshot.initialLoss != null)
            snapshot.initialLoss?.let { dos.writeFloat(it) }

            dos.writeInt(snapshot.lossHistory.size)
            for (loss in snapshot.lossHistory) {
                dos.writeFloat(loss)
            }

            // DataOutputStream.writeUTF memiliki batas keras 65535 byte.
            // Potong nama dataset jika terlalu panjang agar tidak melempar UTFDataFormatException.
            val nameBytesUtf8 = snapshot.datasetName.toByteArray(Charsets.UTF_8)
            val safeDatasetName = if (nameBytesUtf8.size > 65000) snapshot.datasetName.take(20000) else snapshot.datasetName
            dos.writeUTF(safeDatasetName)
            val textBytes = snapshot.datasetText.toByteArray(Charsets.UTF_8)
            dos.writeLong(textBytes.size)
            dos.write(textBytes)

            dos.writeLong(snapshot.modelBytes.size)
            dos.write(snapshot.modelBytes)
            dos.writeLong(snapshot.timestamp)

            dos.flush()
            val bytes = baos.toByteArray()

            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                } catch (e: Exception) {
                    Files.move(
                        tempFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                }
            } else {
                if (!tempFile.renameTo(file)) {
                    file.delete()
                    tempFile.renameTo(file)
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun saveSessionBlocking(context: Context, snapshot: TrainingSessionSnapshot): Boolean {
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            saveSession(context, snapshot)
        }
    }

    /**
     * Loads session snapshot from disk on Dispatchers.IO.
     */
    suspend fun loadSession(context: Context): TrainingSessionSnapshot? = withContext(Dispatchers.IO) {
        val file = getSessionFile(context)
        if (!file.exists() || file.length() == 0L) return@withContext null

        try {
            val bytes = file.readBytes()
            deserializeSession(bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun loadSessionBlocking(context: Context): TrainingSessionSnapshot? {
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            loadSession(context)
        }
    }

    fun deserializeSession(bytes: ByteArray): TrainingSessionSnapshot {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        if (magic != MAGIC_HEADER_SESSION) {
            throw IllegalArgumentException("Magic header sesi tidak valid (bukan file sesi Nexora).")
        }

        val version = dis.readInt()
        if (version != SESSION_VERSION) {
            throw IllegalArgumentException("Versi snapshot sesi ($version) tidak didukung.")
        }

        val currentStep = dis.readInt()
        val totalSteps = dis.readInt()
        val currentEpoch = dis.readInt()
        val totalEpochs = dis.readInt()
        val seqLength = dis.readInt()
        val learningRate = dis.readFloat()

        val hasLatestLoss = dis.readBoolean()
        val latestLoss = if (hasLatestLoss) dis.readFloat() else null

        val hasInitialLoss = dis.readBoolean()
        val initialLoss = if (hasInitialLoss) dis.readFloat() else null

        val lossHistorySize = dis.readInt()
        if (lossHistorySize < 0 || lossHistorySize > 5000) {
            throw IllegalArgumentException("Ukuran riwayat loss ($lossHistorySize) tidak valid.")
        }
        val lossHistory = ArrayList<Float>(lossHistorySize)
        for (i in 0 until lossHistorySize) {
            lossHistory.add(dis.readFloat())
        }

        val datasetName = dis.readUTF()
        val textBytesSize = dis.readInt()
        if (textBytesSize <= 0) {
            throw IllegalArgumentException("Ukuran teks dataset sesi tidak boleh nol.")
        }
        val textBytes = ByteArray(textBytesSize)
        dis.readFully(textBytes)
        val datasetText = String(textBytes, Charsets.UTF_8)

        val modelBytesSize = dis.readInt()
        if (modelBytesSize <= 0) {
            throw IllegalArgumentException("Ukuran binary model sesi tidak valid.")
        }
        val modelBytes = ByteArray(modelBytesSize)
        dis.readFully(modelBytes)
        val timestamp = dis.readLong()

        // Validate model bytes integrity strictly
        CharRNNModel.deserializeFromBytes(modelBytes)

        return TrainingSessionSnapshot(
            version = version,
            currentStep = currentStep,
            totalSteps = totalSteps,
            currentEpoch = currentEpoch,
            totalEpochs = totalEpochs,
            seqLength = seqLength,
            learningRate = learningRate,
            latestLoss = latestLoss,
            initialLoss = initialLoss,
            lossHistory = lossHistory,
            datasetName = datasetName,
            datasetText = datasetText,
            modelBytes = modelBytes,
            timestamp = timestamp
        )
    }

    fun clearSession(context: Context): Boolean {
        val file = getSessionFile(context)
        return if (file.exists()) file.delete() else true
    }
}
