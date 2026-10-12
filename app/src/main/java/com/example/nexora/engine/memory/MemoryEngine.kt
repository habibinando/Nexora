package com.example.nexora.engine.memory

import com.example.nexora.model.database.MemoryEntity
import com.example.nexora.model.database.NexoraDao
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

data class MemoryVerificationReport(
    val memoryId: Long,
    val memoryType: String,
    val storedStrategy: String,
    val lessonLearned: String,
    val sourceTaskId: String,
    val storedResult: String,
    val persistenceVerificationStatus: String, // "CONFIRMED" or "FAILED"
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toFormattedSummary(): String {
        return """
            Memory Verification:
            • Memory ID: #$memoryId
            • Type: $memoryType
            • Stored Strategy: $storedStrategy
            • Lesson Learned: $lessonLearned
            • Source Task: $sourceTaskId
            • Stored Result: $storedResult
            • Persistence Status: $persistenceVerificationStatus
        """.trimIndent()
    }
}

class MemoryEngine(private val dao: NexoraDao) {

    val allMemories: Flow<List<MemoryEntity>> = dao.getAllMemories()

    fun getMemoriesByType(type: String): Flow<List<MemoryEntity>> = dao.getMemoriesByType(type)

    fun searchMemories(query: String): Flow<List<MemoryEntity>> = dao.searchMemories(query)

    suspend fun addMemory(
        type: String,
        title: String,
        content: String,
        tags: String = "",
        confidence: Float = 1.0f
    ): Long {
        return dao.insertMemory(
            MemoryEntity(
                type = type,
                title = title,
                content = content,
                tags = tags,
                confidence = confidence
            )
        )
    }

    suspend fun recordVerifiedMemory(
        type: String,
        title: String,
        content: String,
        tags: String = "",
        confidence: Float = 1.0f,
        storedStrategy: String = "",
        lessonLearned: String = "",
        sourceTaskId: String = "",
        storedResult: String = ""
    ): MemoryVerificationReport {
        val id = dao.insertMemory(
            MemoryEntity(
                type = type,
                title = title,
                content = content,
                tags = tags,
                confidence = confidence
            )
        )

        // Confirm persistence by reading back from SQLite
        val readBack = dao.getMemoryById(id)
        val isConfirmed = readBack != null && readBack.content == content

        return MemoryVerificationReport(
            memoryId = id,
            memoryType = type,
            storedStrategy = storedStrategy.ifBlank { title },
            lessonLearned = lessonLearned.ifBlank { content },
            sourceTaskId = sourceTaskId,
            storedResult = storedResult,
            persistenceVerificationStatus = if (isConfirmed) "CONFIRMED (Row #$id verified in SQLite store)" else "FAILED (Persistence could not be confirmed)"
        )
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        dao.updateMemory(memory)
    }

    suspend fun deleteMemory(id: Long) {
        dao.deleteMemoryById(id)
    }

    suspend fun clearAll() {
        dao.clearAllMemories()
    }

    suspend fun exportMemoriesToJson(): String {
        val list = dao.getAllMemoriesList()
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type)
                put("title", item.title)
                put("content", item.content)
                put("tags", item.tags)
                put("confidence", item.confidence.toDouble())
                put("timestamp", item.timestamp)
                put("metadata", item.metadataJson)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    suspend fun importMemoriesFromJson(jsonString: String): Int {
        var count = 0
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                dao.insertMemory(
                    MemoryEntity(
                        type = obj.optString("type", "LONG_TERM_KNOWLEDGE"),
                        title = obj.optString("title", "Imported Memory"),
                        content = obj.optString("content", ""),
                        tags = obj.optString("tags", "imported"),
                        confidence = obj.optDouble("confidence", 1.0).toFloat(),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        metadataJson = obj.optString("metadata", "{}")
                    )
                )
                count++
            }
        } catch (_: Exception) {}
        return count
    }
}
