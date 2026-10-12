package com.example.nexora.model.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // SHORT_TERM_CONVERSATION, LONG_TERM_KNOWLEDGE, USER_INFO, SUCCESSFUL_STRATEGY, FAILED_STRATEGY, EXPERIMENT, LESSON_LEARNED, TOOL_HISTORY, TRAINING_DATA, MODEL_VERSION
    val title: String,
    val content: String,
    val tags: String = "",
    val confidence: Float = 1.0f,
    val accessCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val metadataJson: String = "{}"
)

@Entity(tableName = "strategies")
data class StrategyEntity(
    @PrimaryKey val strategyId: String,
    val goal: String,
    val parentStrategies: String = "", // e.g. "STRAT-001,STRAT-002"
    val stepsJson: String = "[]",
    val requiredTools: String = "",
    val expectedResult: String = "",
    val riskOfFailure: String = "MEDIUM", // LOW, MEDIUM, HIGH
    val reasoningSummary: String = "",
    val isSuccessful: Boolean? = null,
    val failureCount: Int = 0,
    val successCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "experiments")
data class ExperimentEntity(
    @PrimaryKey val experimentId: String,
    val goal: String, // Objective
    val strategyId: String, // Strategy used
    val parentStrategies: String = "",
    val input: String,
    val operation: String = "", // e.g. "Arithmetic: Multiplication (128 × 42)"
    val expectedResult: String = "",
    val actualResult: String,
    val verificationStatus: String = "NOT_VERIFIED", // SUCCESS, FAILED, PARTIAL, NOT VERIFIED
    val isSuccess: Boolean = false,
    val failureReason: String = "",
    val lessonLearned: String = "",
    val modifications: String = "",
    val evaluationScore: Int = 0, // 0 to 100
    val attemptNumber: Int = 1,
    val finalHumanReadableAnswer: String = "", // e.g. "5,376"
    val memoryVerificationJson: String = "", // Verified memory record
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0
)

@Entity(tableName = "tool_history")
data class ToolHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toolName: String,
    val inputParams: String,
    val outputResult: String,
    val isError: Boolean = false,
    val errorMessage: String = "",
    val executionTimeMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "training_data")
data class TrainingDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceExperimentId: String = "",
    val prompt: String,
    val completion: String,
    val category: String = "GENERAL_REASONING",
    val score: Int = 100,
    val isIncludedInDataset: Boolean = true,
    val datasetName: String = "Nexora-Experience-V1",
    val timestamp: Long = System.currentTimeMillis()
)

/** Alias for persistent dataset training samples requested by the specification */
typealias TrainingSample = TrainingDataEntity

@Entity(tableName = "adapter_modules")
data class AdapterModuleEntity(
    @PrimaryKey val moduleId: String,
    val name: String,
    val version: String,
    val description: String,
    val parameterCount: String,
    val baseModel: String,
    val trainingLoss: Float,
    val benchmarkScore: Float,
    val isEnabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "model_versions")
data class ModelVersionEntity(
    @PrimaryKey val versionId: String,
    val modelName: String,
    val sizeMb: Long,
    val quantization: String,
    val contextLength: Int,
    val requiredRamMb: Long,
    val isCurrentActive: Boolean = false,
    val installedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String, // USER, NEXORA, SYSTEM, TOOL
    val text: String,
    val toolCallInfo: String? = null,
    val experimentId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
