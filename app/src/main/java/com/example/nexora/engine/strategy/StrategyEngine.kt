package com.example.nexora.engine.strategy

import com.example.nexora.model.database.NexoraDao
import com.example.nexora.model.database.StrategyEntity
import org.json.JSONArray
import java.util.UUID

data class StrategyPlan(
    val strategyId: String,
    val goal: String,
    val parentStrategies: List<String> = emptyList(),
    val steps: List<String>,
    val requiredTools: List<String>,
    val expectedResult: String,
    val riskOfFailure: String, // "LOW", "MEDIUM", "HIGH"
    val reasoningSummary: String
) {
    fun toEntity(isSuccessful: Boolean? = null): StrategyEntity {
        val stepsJson = JSONArray(steps).toString()
        return StrategyEntity(
            strategyId = strategyId,
            goal = goal,
            parentStrategies = parentStrategies.joinToString(","),
            stepsJson = stepsJson,
            requiredTools = requiredTools.joinToString(","),
            expectedResult = expectedResult,
            riskOfFailure = riskOfFailure,
            reasoningSummary = reasoningSummary,
            isSuccessful = isSuccessful
        )
    }
}

data class FailureAnalysis(
    val strategyId: String,
    val whatWasAttempted: String,
    val whatPartWorked: String,
    val whatPartFailed: String,
    val whyDidItFail: String,
    val whatAssumptionWasWrong: String,
    val whatShouldNotBeRepeated: String,
    val whatAlternativeShouldBeTested: String,
    val combinableComponents: List<String>
)

class StrategyEngine(private val dao: NexoraDao) {

    /**
     * Searches previous experiences in the Strategy Library for existing solutions or known pitfalls.
     */
    suspend fun searchPreviousExperiences(goal: String): List<StrategyEntity> {
        return dao.findSimilarStrategies(goal)
    }

    /**
     * Generates Strategy 1 based on goal and prior library wisdom.
     */
    suspend fun generateInitialStrategy(goal: String, input: String): StrategyPlan {
        val stratId = "STRAT-${UUID.randomUUID().toString().take(6).uppercase()}"
        val previous = searchPreviousExperiences(goal)
        val successfulPrior = previous.filter { it.isSuccessful == true }

        val steps: List<String>
        val tools: List<String>
        val risk: String
        val reasoning: String

        val lowerGoal = goal.lowercase()
        when {
            lowerGoal.contains("palindrome") || lowerGoal.contains("is_palindrome") ||
                    lowerGoal.contains("python") || lowerGoal.contains("code") ||
                    lowerGoal.contains("coding") || lowerGoal.contains("koding") ||
                    lowerGoal.contains("script") || lowerGoal.contains("algoritma") ||
                    lowerGoal.contains("function") || lowerGoal.contains("program") -> {
                steps = listOf(
                    "Analyze goal requirements and synthesize Python function implementation",
                    "Construct concrete unit test suite with edge cases and boundary inputs",
                    "Execute Python code and verify all test cases locally via python_exec tool",
                    "Validate execution traceback and confirm 100% test pass rate"
                )
                tools = listOf("python_exec")
                risk = "LOW"
                reasoning = "Strategy 1 uses python_exec to verify code against real test cases with 5s timeout and traceback verification."
            }
            lowerGoal.contains("calculate") || lowerGoal.contains("compute") || lowerGoal.contains("math") -> {
                steps = listOf(
                    "Parse mathematical tokens and check boundary limits",
                    "Dispatch expression to verified CalculatorTool",
                    "Format resulting value and check numerical precision"
                )
                tools = listOf("CalculatorTool")
                risk = "LOW"
                reasoning = "Direct evaluation via verified local calculator."
            }
            lowerGoal.contains("file") || lowerGoal.contains("save") || lowerGoal.contains("sandbox") -> {
                steps = listOf(
                    "Inspect local sandbox directory",
                    "Verify file existence and permission flags",
                    "Execute file read/write operation via FileManagerTool"
                )
                tools = listOf("FileManagerTool")
                risk = "MEDIUM"
                reasoning = "Sandbox I/O requires verification of existing file headers."
            }
            lowerGoal.contains("device") || lowerGoal.contains("battery") || lowerGoal.contains("system") -> {
                steps = listOf(
                    "Query Android system telemetry and hardware metrics",
                    "Correlate battery and memory thresholds"
                )
                tools = listOf("AndroidUtilityTool")
                risk = "LOW"
                reasoning = "Local hardware status accessed via Android system services."
            }
            else -> {
                steps = listOf(
                    "Analyze goal objectives and decompose into sub-goals",
                    "Query relevant long-term memory for heuristic rules",
                    "Execute code logic verification with LocalCodeExecutor"
                )
                tools = listOf("LocalCodeExecutor", "TextProcessorTool")
                risk = if (successfulPrior.isNotEmpty()) "LOW" else "MEDIUM"
                reasoning = if (successfulPrior.isNotEmpty()) {
                    "Found ${successfulPrior.size} successful ancestor strategies in Library. Adapting proven pattern."
                } else {
                    "Formulating novel hypothesis from first principles."
                }
            }
        }

        return StrategyPlan(
            strategyId = stratId,
            goal = goal,
            parentStrategies = emptyList(),
            steps = steps,
            requiredTools = tools,
            expectedResult = "Successful execution of: $goal",
            riskOfFailure = risk,
            reasoningSummary = reasoning
        )
    }

    /**
     * Conducts in-depth failure analysis as required by section 4.
     */
    fun analyzeFailure(
        strategy: StrategyPlan,
        errorMessage: String,
        actualOutput: String
    ): FailureAnalysis {
        val workingPart = if (actualOutput.isNotBlank()) "Initial parsing and setup succeeded" else "Strategy registration completed"
        val failedPart = if (errorMessage.isNotBlank()) errorMessage else "Execution result diverged from expected outcome"

        return FailureAnalysis(
            strategyId = strategy.strategyId,
            whatWasAttempted = strategy.steps.joinToString(" -> "),
            whatPartWorked = workingPart,
            whatPartFailed = failedPart,
            whyDidItFail = "Assumption invalid: Execution encountered error: $failedPart",
            whatAssumptionWasWrong = "Assumed standard execution parameters without fallback redundancy.",
            whatShouldNotBeRepeated = "Do not reuse identical unvalidated inputs or single-point failure tools.",
            whatAlternativeShouldBeTested = "Apply input sanitization and multi-stage fallback routing.",
            combinableComponents = listOf(
                "Keep working part: $workingPart",
                "Retain validated tool: ${strategy.requiredTools.firstOrNull() ?: "CalculatorTool"}"
            )
        )
    }

    /**
     * Synthesizes Strategy 3 from Strategy 1 + Strategy 2 + improvements.
     */
    fun synthesizeHybridStrategy(
        goal: String,
        failedStrategies: List<StrategyPlan>,
        analyses: List<FailureAnalysis>
    ): StrategyPlan {
        val newStratId = "STRAT-${UUID.randomUUID().toString().take(6).uppercase()}-HYBRID"
        val parentIds = failedStrategies.map { it.strategyId }

        val combinedTools = failedStrategies.flatMap { it.requiredTools }.distinct().toMutableList()
        if (!combinedTools.contains("TextProcessorTool")) {
            combinedTools.add("TextProcessorTool")
        }

        val combinedSteps = mutableListOf<String>()
        combinedSteps.add("Pre-validate arguments and sanitize inputs based on previous failures")
        for (analysis in analyses) {
            combinedSteps.add("Incorporate fix for: ${analysis.whatPartFailed.take(45)}")
        }
        combinedSteps.add("Execute resilient hybrid execution pipeline with automatic fallback")

        return StrategyPlan(
            strategyId = newStratId,
            goal = goal,
            parentStrategies = parentIds,
            steps = combinedSteps,
            requiredTools = combinedTools,
            expectedResult = "Synthesized success combining functional parts of [${parentIds.joinToString(", ")}]",
            riskOfFailure = "LOW",
            reasoningSummary = "Strategy 3 synthesized from [${parentIds.joinToString(" + ")}] with failure-avoidance heuristics applied."
        )
    }

    /**
     * Synthesizes Strategy 4: New orthogonal approach if previous hybrid attempts failed.
     */
    fun synthesizeNewParadigmStrategy(
        goal: String,
        allFailed: List<StrategyPlan>
    ): StrategyPlan {
        val newStratId = "STRAT-${UUID.randomUUID().toString().take(6).uppercase()}-PARADIGM"
        val parentIds = allFailed.map { it.strategyId }

        return StrategyPlan(
            strategyId = newStratId,
            goal = goal,
            parentStrategies = parentIds,
            steps = listOf(
                "Bypass previous failed pipelines entirely",
                "Execute alternative verification using LocalCodeExecutor with defensive bounds",
                "Cross-validate output against local system memory"
            ),
            requiredTools = listOf("LocalCodeExecutor", "AndroidUtilityTool"),
            expectedResult = "Orthogonal paradigm execution for: $goal",
            riskOfFailure = "LOW",
            reasoningSummary = "Strategy 4: Shifted paradigm after analyzing failures in ${parentIds.size} prior strategies."
        )
    }
}
