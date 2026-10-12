package com.example.nexora.engine.model

import kotlinx.coroutines.flow.Flow

data class ModelInfo(
    val id: String,
    val name: String,
    val sizeMb: Long,
    val quantization: String,
    val contextLength: Int,
    val requiredRamMb: Long,
    val isLoaded: Boolean = false,
    val cpuThreads: Int = 4,
    val isGpuEnabled: Boolean = false,
    val description: String = "",
    val customDownloadUrl: String = "",
    val supportsNativeThinking: Boolean = true
)

data class ToolCallRequest(
    val toolName: String,
    val arguments: Map<String, String>,
    val callId: String = ""
)

data class ModelContext(
    val systemPrompt: String = "",
    val conversationHistory: List<Pair<String, String>> = emptyList(), // sender to text
    val recalledMemories: List<String> = emptyList(),
    val candidateStrategies: List<String> = emptyList(),
    val availableTools: List<String> = emptyList(),
    val nonThinkingMode: Boolean = false,
    val apiKey: String = "",
    val preferredModel: String = "gemini-2.5-flash"
)

data class ModelResult(
    val text: String,
    val tokenCount: Int,
    val latencyMs: Long,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val reasoningSummary: String = ""
)

interface ModelEngine {
    val modelInfo: ModelInfo
    suspend fun isLoaded(): Boolean
    suspend fun load(): Boolean
    suspend fun unload()
    suspend fun generateStream(
        prompt: String,
        context: ModelContext
    ): Flow<String>
    suspend fun generate(
        prompt: String,
        context: ModelContext
    ): ModelResult
}
