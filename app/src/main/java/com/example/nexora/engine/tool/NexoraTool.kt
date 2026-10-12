package com.example.nexora.engine.tool

data class ToolSchema(
    val name: String,
    val type: String, // string, number, boolean, json
    val description: String,
    val required: Boolean = true
)

data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val executionTimeMs: Long = 0,
    val metadata: Map<String, String> = emptyMap()
)

interface NexoraTool {
    val name: String
    val description: String
    val inputSchema: List<ToolSchema>
    val outputSchema: List<ToolSchema>
    val requiredPermissions: List<String>
    val isInternetRequired: Boolean
        get() = false
    var isEnabled: Boolean

    suspend fun execute(parameters: Map<String, String>): ToolExecutionResult
}
