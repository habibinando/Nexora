package com.example.nexora.engine.tool

import android.content.Context
import com.example.nexora.engine.tool.builtins.*
import com.example.nexora.model.database.NexoraDao
import com.example.nexora.model.database.ToolHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ToolRegistry(
    private val context: Context,
    private val dao: NexoraDao
) {
    private val settingsStore = com.example.nexora.model.settings.NexoraSettingsStore.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _tools = mutableMapOf<String, NexoraTool>()
    private val _toolsState = MutableStateFlow<List<NexoraTool>>(emptyList())
    val toolsState: StateFlow<List<NexoraTool>> = _toolsState.asStateFlow()

    init {
        registerTool(CalculatorTool())
        registerTool(FileManagerTool(context))
        registerTool(LocalCodeExecutorTool())
        registerTool(TextProcessorTool())
        registerTool(JsonProcessorTool())
        registerTool(AndroidUtilityTool(context))

        // PythonExecTool with Chaquopy / local execution - enabled by default
        registerTool(PythonExecTool(context))
        
        // Strict Local-First Mode is default ON: WebSearch is disabled by default unless persisted otherwise
        registerTool(WebSearchTool())
        
        refreshState()

        // Single Source of Truth: Listen continuously to settingsStore.toolStates
        scope.launch {
            settingsStore.toolStates.collect { stateMap ->
                for ((canonical, tool) in _tools) {
                    val isEnabled = stateMap[canonical] ?: settingsStore.isToolEnabled(canonical)
                    tool.isEnabled = isEnabled
                }
                refreshState()
            }
        }
    }

    fun registerTool(tool: NexoraTool) {
        val canonical = canonicalToolName(tool.name)
        tool.isEnabled = settingsStore.isToolEnabled(canonical)
        _tools[canonical] = tool
        refreshState()
    }

    fun getTool(name: String): NexoraTool? {
        val canonical = canonicalToolName(name)
        return _tools[canonical]
    }

    fun getAllTools(): List<NexoraTool> = _tools.values.toList()

    fun setToolEnabled(name: String, enabled: Boolean) {
        val canonical = canonicalToolName(name)
        _tools[canonical]?.isEnabled = enabled
        settingsStore.setToolEnabled(canonical, enabled)
        refreshState()
    }

    private fun refreshState() {
        // Create new ImmutableToolSnapshot data classes so Compose recomposition detects state mutation instantly
        _toolsState.value = _tools.values.map { tool ->
            val canonical = canonicalToolName(tool.name)
            val isEnabled = settingsStore.isToolEnabled(canonical)
            tool.isEnabled = isEnabled
            ImmutableToolSnapshot(
                delegate = tool,
                isEnabled = isEnabled
            )
        }
    }

    data class ImmutableToolSnapshot(
        val delegate: NexoraTool,
        override var isEnabled: Boolean
    ) : NexoraTool by delegate

    fun canonicalToolName(name: String): String {
        return when (name.lowercase().trim()) {
            "calculator", "calculatortool" -> "CalculatorTool"
            "text_processor", "textprocessor", "textprocessortool" -> "TextProcessorTool"
            "file_manager", "filemanager", "filemanagertool" -> "FileManagerTool"
            "code_executor", "localcodeexecutor", "local_code_executor" -> "LocalCodeExecutor"
            "python_exec", "pythonexec", "python", "py_exec", "python_executor", "pythonexectool" -> "python_exec"
            "json_processor", "jsonprocessor", "jsonprocessortool" -> "JsonProcessorTool"
            "android_utility", "androidutilitytool" -> "AndroidUtilityTool"
            "web_search", "websearch", "websearchtool" -> "WebSearchTool"
            else -> name
        }
    }

    /**
     * Identifies high-risk tool operations that MANDATORILY require Human Oversight (Approve/Deny):
     * 1. FileManagerTool (write / delete operations)
     * 2. LocalCodeExecutor
     * 3. WebSearchTool (network egress)
     */
    fun isHighRiskOperation(name: String, parameters: Map<String, String>): Boolean {
        val canonical = canonicalToolName(name)
        return when (canonical) {
            "FileManagerTool" -> {
                val action = parameters["action"]?.lowercase()?.trim() ?: ""
                action == "write" || action == "delete"
            }
            "LocalCodeExecutor" -> true
            "WebSearchTool" -> true
            else -> false
        }
    }

    suspend fun executeTool(
        name: String,
        parameters: Map<String, String>
    ): ToolExecutionResult {
        val canonical = canonicalToolName(name)
        val tool = _tools[canonical]

        if (tool == null) {
            val errorMsg = "Tool '$name' tidak terdaftar dalam Nexora ToolRegistry."
            dao.insertToolHistory(
                ToolHistoryEntity(
                    toolName = name,
                    inputParams = parameters.toString(),
                    outputResult = "",
                    isError = true,
                    errorMessage = errorMsg,
                    timestamp = System.currentTimeMillis()
                )
            )
            return ToolExecutionResult(false, "", errorMsg)
        }

        if (!tool.isEnabled) {
            val errorMsg = "Tool '$canonical' dinonaktifkan dalam konfigurasi Strict Local-First."
            dao.insertToolHistory(
                ToolHistoryEntity(
                    toolName = canonical,
                    inputParams = parameters.toString(),
                    outputResult = "",
                    isError = true,
                    errorMessage = errorMsg,
                    timestamp = System.currentTimeMillis()
                )
            )
            return ToolExecutionResult(false, "", errorMsg)
        }

        val result = tool.execute(parameters)

        // GUARANTEED PERSISTENT RECORDING in Call Logs
        dao.insertToolHistory(
            ToolHistoryEntity(
                toolName = tool.name,
                inputParams = parameters.toString(),
                outputResult = result.output,
                isError = !result.success,
                errorMessage = result.error ?: "",
                executionTimeMs = result.executionTimeMs,
                timestamp = System.currentTimeMillis()
            )
        )

        return result
    }
}
