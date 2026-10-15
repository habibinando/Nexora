package com.example.neural

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ModelCheckpoint(
    val filename: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val formattedSize: String,
    val timestamp: Long,
    val formattedDate: String,
    val parametersCount: Long,
    val vocabSize: Int,
    val hiddenSize: Int
)

object ModelCheckpointManager {

    private fun getCheckpointDir(context: Context): File {
        val dir = File(context.filesDir, "checkpoints")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveCheckpoint(
        context: Context,
        model: CharRNNModel,
        customName: String? = null
    ): ModelCheckpoint {
        val dir = getCheckpointDir(context)
        val timestamp = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
        val safeName = customName?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "neural_adapter_$dateStr"
        val file = File(dir, "$safeName.bin")

        val bytes = model.serializeToBytes()
        file.writeBytes(bytes)

        val sizeBytes = file.length()
        val displayDate = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

        return ModelCheckpoint(
            filename = file.name,
            filePath = file.absolutePath,
            fileSizeBytes = sizeBytes,
            formattedSize = formatBytes(sizeBytes),
            timestamp = timestamp,
            formattedDate = displayDate,
            parametersCount = model.totalParameterCount,
            vocabSize = model.vocabSize,
            hiddenSize = model.hiddenSize
        )
    }

    fun listCheckpoints(context: Context): List<ModelCheckpoint> {
        val dir = getCheckpointDir(context)
        val files = dir.listFiles { f -> f.extension == "bin" } ?: return emptyList()

        val results = mutableListOf<ModelCheckpoint>()
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        for (file in files.sortedByDescending { it.lastModified() }) {
            try {
                val sizeBytes = file.length()
                val bytes = file.readBytes()
                val model = CharRNNModel.deserializeFromBytes(bytes)
                results.add(
                    ModelCheckpoint(
                        filename = file.name,
                        filePath = file.absolutePath,
                        fileSizeBytes = sizeBytes,
                        formattedSize = formatBytes(sizeBytes),
                        timestamp = file.lastModified(),
                        formattedDate = sdf.format(Date(file.lastModified())),
                        parametersCount = model.totalParameterCount,
                        vocabSize = model.vocabSize,
                        hiddenSize = model.hiddenSize
                    )
                )
            } catch (e: Exception) {
                // If corrupted file
                val sizeBytes = file.length()
                results.add(
                    ModelCheckpoint(
                        filename = file.name,
                        filePath = file.absolutePath,
                        fileSizeBytes = sizeBytes,
                        formattedSize = formatBytes(sizeBytes),
                        timestamp = file.lastModified(),
                        formattedDate = sdf.format(Date(file.lastModified())),
                        parametersCount = 0L,
                        vocabSize = 0,
                        hiddenSize = 0
                    )
                )
            }
        }
        return results
    }

    fun loadCheckpoint(filePath: String): CharRNNModel? {
        val file = File(filePath)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            CharRNNModel.deserializeFromBytes(bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteCheckpoint(filePath: String): Boolean {
        val file = File(filePath)
        return if (file.exists()) file.delete() else false
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
