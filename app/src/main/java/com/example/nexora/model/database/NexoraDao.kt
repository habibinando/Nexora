package com.example.nexora.model.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NexoraDao {

    // --- Memories ---
    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY timestamp DESC")
    fun getMemoriesByType(type: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMemories(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMemoriesList(query: String): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    suspend fun getAllMemoriesList(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: Long): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // --- Strategies ---
    @Query("SELECT * FROM strategies ORDER BY timestamp DESC")
    fun getAllStrategies(): Flow<List<StrategyEntity>>

    @Query("SELECT * FROM strategies WHERE isSuccessful = 1 ORDER BY timestamp DESC")
    fun getSuccessfulStrategies(): Flow<List<StrategyEntity>>

    @Query("SELECT * FROM strategies WHERE isSuccessful = 0 ORDER BY timestamp DESC")
    fun getFailedStrategies(): Flow<List<StrategyEntity>>

    @Query("SELECT * FROM strategies WHERE strategyId = :id")
    suspend fun getStrategyById(id: String): StrategyEntity?

    @Query("SELECT * FROM strategies WHERE goal LIKE '%' || :goalQuery || '%' ORDER BY timestamp DESC")
    suspend fun findSimilarStrategies(goalQuery: String): List<StrategyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrategy(strategy: StrategyEntity)

    @Update
    suspend fun updateStrategy(strategy: StrategyEntity)

    @Query("DELETE FROM strategies WHERE strategyId = :id")
    suspend fun deleteStrategyById(id: String)

    // --- Experiments ---
    @Query("SELECT * FROM experiments ORDER BY timestamp DESC")
    fun getAllExperiments(): Flow<List<ExperimentEntity>>

    @Query("SELECT * FROM experiments WHERE experimentId = :id")
    suspend fun getExperimentById(id: String): ExperimentEntity?

    @Query("SELECT * FROM experiments WHERE goal LIKE '%' || :goalQuery || '%' ORDER BY timestamp DESC")
    suspend fun findExperimentsByGoal(goalQuery: String): List<ExperimentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExperiment(experiment: ExperimentEntity)

    @Query("SELECT COUNT(*) FROM experiments")
    fun getExperimentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM experiments WHERE isSuccess = 1")
    fun getSuccessfulExperimentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM experiments WHERE isSuccess = 0")
    fun getFailedExperimentCount(): Flow<Int>

    // --- Tool History ---
    @Query("SELECT * FROM tool_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentToolHistory(limit: Int = 100): Flow<List<ToolHistoryEntity>>

    @Query("SELECT * FROM tool_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentToolHistoryList(limit: Int = 100): List<ToolHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolHistory(entry: ToolHistoryEntity): Long

    @Query("DELETE FROM tool_history")
    suspend fun clearToolHistory()

    // --- Training Data ---
    @Query("SELECT * FROM training_data ORDER BY timestamp DESC")
    fun getAllTrainingData(): Flow<List<TrainingDataEntity>>

    @Query("SELECT * FROM training_data WHERE isIncludedInDataset = 1")
    suspend fun getIncludedTrainingDataList(): List<TrainingDataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrainingData(item: TrainingDataEntity): Long

    @Update
    suspend fun updateTrainingData(item: TrainingDataEntity)

    @Query("DELETE FROM training_data WHERE id = :id")
    suspend fun deleteTrainingData(id: Long)

    @Query("DELETE FROM training_data WHERE id IN (:ids)")
    suspend fun deleteTrainingDataByIds(ids: List<Long>)

    @Query("DELETE FROM training_data")
    suspend fun clearAllTrainingData()

    // --- Adapter Modules ---
    @Query("SELECT * FROM adapter_modules ORDER BY createdAt DESC")
    fun getAllAdapterModules(): Flow<List<AdapterModuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdapterModule(module: AdapterModuleEntity)

    @Query("UPDATE adapter_modules SET isEnabled = :isEnabled WHERE moduleId = :id")
    suspend fun setAdapterEnabled(id: String, isEnabled: Boolean)

    // --- Model Versions ---
    @Query("SELECT * FROM model_versions ORDER BY installedAt DESC")
    fun getAllModelVersions(): Flow<List<ModelVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModelVersion(version: ModelVersionEntity)

    @Query("UPDATE model_versions SET isCurrentActive = (versionId = :activeVersionId)")
    suspend fun setActiveModelVersion(activeVersionId: String)

    @Query("SELECT * FROM model_versions WHERE isCurrentActive = 1 LIMIT 1")
    suspend fun getActiveModelVersion(): ModelVersionEntity?

    // --- Chat Messages ---
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllChatMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllChatMessagesList(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatMessages()
}
