package com.example.nexora.engine.experiment

enum class ErrorClassification {
    TOOL_FAILURE,
    INVALID_INPUT,
    INCORRECT_STRATEGY,
    MISSING_INFORMATION,
    MODEL_ERROR,
    EXTERNAL_SERVICE_FAILURE,
    DEVICE_RESOURCE_LIMITATION
}

data class DiagnosticReport(
    val classification: ErrorClassification,
    val rootCause: String,
    val discoveredLesson: String,
    val recommendedModification: String,
    val confidence: Float
)

class SelfDebugger {

    fun diagnose(
        errorMessage: String,
        contextInfo: String
    ): DiagnosticReport {
        val lower = "$errorMessage $contextInfo".lowercase()

        return when {
            lower.contains("ram") || lower.contains("out of memory") || lower.contains("heap") || lower.contains("resource") -> {
                DiagnosticReport(
                    classification = ErrorClassification.DEVICE_RESOURCE_LIMITATION,
                    rootCause = "Exceeded device heap or RAM threshold during model/tool computation.",
                    discoveredLesson = "Check DeviceMemoryState before dispatching large batch operations or loading large weights.",
                    recommendedModification = "Reduce batch context, increase quantization compression, or unload inactive adapters.",
                    confidence = 0.95f
                )
            }
            lower.contains("missing") || lower.contains("required parameter") || lower.contains("null") -> {
                DiagnosticReport(
                    classification = ErrorClassification.INVALID_INPUT,
                    rootCause = "Tool was invoked with missing or ill-typed argument parameters.",
                    discoveredLesson = "Enforce schema validation before invoking tool execution.",
                    recommendedModification = "Implement argument type coercion and default fallback parameters.",
                    confidence = 0.92f
                )
            }
            lower.contains("network") || lower.contains("offline") || lower.contains("internet") || lower.contains("connection") -> {
                DiagnosticReport(
                    classification = ErrorClassification.EXTERNAL_SERVICE_FAILURE,
                    rootCause = "Attempted network tool while device is offline or internet permission is disabled.",
                    discoveredLesson = "Nexora's core must always complete objectives locally without relying on external servers.",
                    recommendedModification = "Switch to on-device simulated or cached knowledge engines.",
                    confidence = 0.98f
                )
            }
            lower.contains("tool") || lower.contains("not registered") || lower.contains("disabled") -> {
                DiagnosticReport(
                    classification = ErrorClassification.TOOL_FAILURE,
                    rootCause = "Target tool failed during execution or is disabled in ToolRegistry.",
                    discoveredLesson = "Check tool enabled status and register alternative handler prior to dispatch.",
                    recommendedModification = "Route through fallback tool or perform direct computation.",
                    confidence = 0.90f
                )
            }
            lower.contains("strategy") || lower.contains("unexpected") || lower.contains("diverged") -> {
                DiagnosticReport(
                    classification = ErrorClassification.INCORRECT_STRATEGY,
                    rootCause = "The chosen strategy steps were flawed or based on mistaken initial assumptions.",
                    discoveredLesson = "Flawed strategy lineage: decompose goal into smaller granular verification milestones.",
                    recommendedModification = "Synthesize hybrid strategy incorporating proven elements from previous attempts.",
                    confidence = 0.88f
                )
            }
            else -> {
                DiagnosticReport(
                    classification = ErrorClassification.MODEL_ERROR,
                    rootCause = "Inference or tool parsing error: $errorMessage",
                    discoveredLesson = "Ensure prompt structure aligns with model context formatting.",
                    recommendedModification = "Retry with explicit system prompt constraints and simpler task framing.",
                    confidence = 0.80f
                )
            }
        }
    }
}
