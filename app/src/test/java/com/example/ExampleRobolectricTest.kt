package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.nexora.engine.experiment.EvaluationEngine
import com.example.nexora.engine.experiment.ExperimentEngine
import com.example.nexora.engine.memory.MemoryEngine
import com.example.nexora.engine.model.ModelManager
import com.example.nexora.engine.strategy.StrategyEngine
import com.example.nexora.engine.tool.ToolRegistry
import com.example.nexora.engine.tool.builtins.CalculatorTool
import com.example.nexora.model.database.NexoraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context verifies app name is Nexora`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Nexora", appName)
    }

    @Test
    fun `calculator tool evaluates 128 times 42 and formats result as 5376`() = runBlocking {
        val calc = CalculatorTool()

        // Test with Unicode multiplication sign '×'
        val resUnicode = calc.execute(mapOf("expression" to "128 × 42"))
        assertTrue("Calculation with '×' must succeed", resUnicode.success)
        assertEquals("5,376", resUnicode.output)

        // Test with standard '*'
        val resAsterisk = calc.execute(mapOf("expression" to "128 * 42"))
        assertTrue("Calculation with '*' must succeed", resAsterisk.success)
        assertEquals("5,376", resAsterisk.output)

        // Test with 'x'
        val resLetterX = calc.execute(mapOf("expression" to "128 x 42"))
        assertTrue("Calculation with 'x' must succeed", resLetterX.success)
        assertEquals("5,376", resLetterX.output)
    }

    @Test
    fun `tool registry logs every execution into Call Logs and detects high risk operations`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.nexoraDao()
        val toolRegistry = ToolRegistry(context, dao)

        // 1. Verify Strict Local-First default: WebSearchTool is disabled by default
        val webSearch = toolRegistry.getTool("WebSearchTool")
        assertNotNull(webSearch)
        assertFalse("WebSearchTool must be disabled by default in Strict Local-First mode", webSearch!!.isEnabled)

        // 2. High-risk operations detection
        assertTrue("FileManager write is high risk", toolRegistry.isHighRiskOperation("FileManagerTool", mapOf("action" to "write")))
        assertTrue("FileManager delete is high risk", toolRegistry.isHighRiskOperation("FileManagerTool", mapOf("action" to "delete")))
        assertFalse("FileManager read is low risk", toolRegistry.isHighRiskOperation("FileManagerTool", mapOf("action" to "read")))
        assertTrue("LocalCodeExecutor is high risk", toolRegistry.isHighRiskOperation("LocalCodeExecutor", mapOf("code" to "sort(1,2)")))

        // 3. Execute tool and check Call Logs persistence
        val initialHistory = dao.getRecentToolHistoryList(50)
        assertEquals(0, initialHistory.size)

        val res = toolRegistry.executeTool("CalculatorTool", mapOf("expression" to "25 * 25"))
        assertTrue(res.success)
        assertEquals("625", res.output)

        val historyAfter = dao.getRecentToolHistoryList(50)
        assertEquals("Call Logs must have 1 recorded entry after execution", 1, historyAfter.size)
        assertEquals("CalculatorTool", historyAfter[0].toolName)
        assertEquals("625", historyAfter[0].outputResult)
        assertFalse(historyAfter[0].isError)

        db.close()
    }

    @Test
    fun `evaluation engine tests semantic content alignment for non-math goals`() {
        val evaluator = EvaluationEngine()

        // Test 1: Code goal with valid code output
        val reportCodeSuccess = evaluator.evaluate(
            goal = "Create a python script to filter odd numbers",
            input = "filter odd numbers",
            expectedResult = "",
            actualResult = "def filter_odds(arr): return [x for x in arr if x % 2 != 0]",
            executionError = null,
            executionDurationMs = 120
        )
        assertEquals("SUCCESS", reportCodeSuccess.verificationStatus)
        assertTrue(reportCodeSuccess.didObjectiveHappen)
        assertTrue(reportCodeSuccess.overallScore >= 80)

        // Test 2: Code goal with missing code (non-semantic response)
        val reportCodeFail = evaluator.evaluate(
            goal = "Create a python script to filter odd numbers",
            input = "filter odd numbers",
            expectedResult = "",
            actualResult = "Today is very sunny and we went outside for a walk.",
            executionError = null,
            executionDurationMs = 100
        )
        assertEquals("FAILED", reportCodeFail.verificationStatus)
        assertFalse(reportCodeFail.didObjectiveHappen)
        assertTrue(reportCodeFail.overallScore < 50)
    }

    @Test
    fun `experiment engine executes 128 times 42 verifies 5376 and confirms memory persistence`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.nexoraDao()

        val memoryEngine = MemoryEngine(dao)
        val strategyEngine = StrategyEngine(dao)
        val toolRegistry = ToolRegistry(context, dao)
        val modelManager = ModelManager(context)
        val experimentEngine = ExperimentEngine(dao, strategyEngine, toolRegistry, modelManager, memoryEngine)

        // Run concrete experiment for 128 × 42
        val experiment = experimentEngine.runExperiment(
            goal = "Calculate 128 × 42",
            inputPayload = "128 × 42",
            expectedResult = "5,376"
        )

        // 1. Verify experiment received input
        assertEquals("128 × 42", experiment.input)

        // 2. Verify actual result is "5,376"
        assertEquals("5,376", experiment.actualResult)
        assertEquals("5,376", experiment.finalHumanReadableAnswer)

        // 3. Verify verification passes and score is 100
        assertEquals("SUCCESS", experiment.verificationStatus)
        assertTrue(experiment.isSuccess)
        assertEquals(100, experiment.evaluationScore)

        // 4. Verify memory persistence was actually confirmed in SQLite
        assertTrue("Memory report must show persistence confirmed", experiment.memoryVerificationJson.contains("CONFIRMED"))

        // 5. Verify the stored memory can be retrieved again from SQLite
        val allMemories = dao.getAllMemoriesList()
        val found = allMemories.any { it.content.contains("5,376") }
        assertTrue("Stored memory containing '5,376' must be retrievable from database", found)

        // 6. Verify Call Logs captured the tool execution
        val callLogs = dao.getRecentToolHistoryList(50)
        assertTrue("Call logs must contain recorded tool execution", callLogs.isNotEmpty())

        db.close()
    }

    @Test
    fun `python_exec is registered and enabled by default in ToolRegistry`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nexoraDao()
        val registry = ToolRegistry(context, dao)

        val tool = registry.getTool("python_exec")
        assertNotNull("python_exec tool must be registered in ToolRegistry", tool)
        assertTrue("python_exec must be enabled by default", tool!!.isEnabled)
        assertEquals("python_exec", tool.name)

        db.close()
    }

    @Test
    fun `python_exec executes is_palindrome and passes genuine test cases`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nexoraDao()
        val registry = ToolRegistry(context, dao)

        val palindromeCode = """
            def is_palindrome(s):
                cleaned = ''.join(c.lower() for c in str(s) if c.isalnum())
                return cleaned == cleaned[::-1]
        """.trimIndent()

        val testCases = """
            [
                "is_palindrome('racecar') == True",
                "is_palindrome('madam') == True",
                "is_palindrome('hello') == False",
                "is_palindrome('A man a plan a canal Panama') == True",
                "is_palindrome('12321') == True"
            ]
        """.trimIndent()

        val result = registry.executeTool(
            "python_exec",
            mapOf(
                "code" to palindromeCode,
                "test_cases" to testCases
            )
        )

        assertTrue("Execution must succeed when all test cases pass: ${result.error}", result.success)
        assertTrue("Output must show VERIFIED_SUCCESS", result.output.contains("VERIFIED_SUCCESS"))
        assertTrue("Output must confirm 5/5 tests passed", result.output.contains("5/5 tests passed"))
        assertNull("Error should be null on 100% pass", result.error)

        db.close()
    }

    @Test
    fun `python_exec reports failure and traceback when test cases fail`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nexoraDao()
        val registry = ToolRegistry(context, dao)

        // Buggy code that always returns True
        val buggyCode = """
            def is_palindrome(s):
                return True
        """.trimIndent()

        val testCases = """
            [
                "is_palindrome('racecar') == True",
                "is_palindrome('hello') == False"
            ]
        """.trimIndent()

        val result = registry.executeTool(
            "python_exec",
            mapOf(
                "code" to buggyCode,
                "test_cases" to testCases
            )
        )

        assertFalse("Execution must fail when a test case fails", result.success)
        assertTrue("Output must contain traceback", result.output.contains("Traceback"))
        assertTrue("Output must show VERIFIED_FAILURE", result.output.contains("VERIFIED_FAILURE"))
        assertNotNull("Error message must be present", result.error)

        db.close()
    }

    @Test
    fun `python_exec halts on infinite loop within 5 seconds with timeout traceback`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nexoraDao()
        val registry = ToolRegistry(context, dao)

        val infiniteLoopCode = """
            def is_palindrome(s):
                while True:
                    pass
                return True
        """.trimIndent()

        val result = registry.executeTool(
            "python_exec",
            mapOf(
                "code" to infiniteLoopCode,
                "test_cases" to "is_palindrome('racecar') == True",
                "timeout_ms" to "1500" // Quick timeout test
            )
        )

        assertFalse("Infinite loop must fail with timeout", result.success)
        assertTrue("Output must contain TimeoutError or Timeout", result.output.contains("Timeout") || result.error?.contains("Timeout") == true)
        assertTrue("Traceback must be recorded", result.output.contains("Traceback"))

        db.close()
    }

    @Test
    fun `is_palindrome experiment uses Strategy 1 with python_exec and verifies genuine success`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nexoraDao()

        val memoryEngine = MemoryEngine(dao)
        val strategyEngine = StrategyEngine(dao)
        val toolRegistry = ToolRegistry(context, dao)
        val modelManager = ModelManager(context)
        val experimentEngine = ExperimentEngine(dao, strategyEngine, toolRegistry, modelManager, memoryEngine)

        // 1. Verify Strategy 1 chooses python_exec
        val strat1 = strategyEngine.generateInitialStrategy("is_palindrome", "")
        assertTrue("Strategy 1 must use python_exec for is_palindrome", strat1.requiredTools.contains("python_exec"))

        // 2. Run experiment for is_palindrome
        val experiment = experimentEngine.runExperiment(
            goal = "Implement and verify is_palindrome function",
            inputPayload = "def is_palindrome(s):\n    cleaned = ''.join(c.lower() for c in str(s) if c.isalnum())\n    return cleaned == cleaned[::-1]"
        )

        assertTrue("Experiment must succeed", experiment.isSuccess)
        assertEquals("SUCCESS", experiment.verificationStatus)
        assertTrue("Output must show verified tests", experiment.actualResult.contains("VERIFIED_SUCCESS"))
        assertTrue("Confirmed memory must be recorded", experiment.memoryVerificationJson.contains("CONFIRMED"))

        // 3. Verify memory stored confirmed lesson
        val memories = dao.getAllMemoriesList()
        assertTrue("Verified lesson must be persisted in memory", memories.any { it.title.contains("is_palindrome") })

        db.close()
    }

    @Test
    fun `BUG 2 - model activation is strictly rejected if file is not on disk`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = ModelManager(context)
        val ingestionManager = com.example.nexora.engine.model.ModelIngestionManager(context, modelManager)

        // Attempting to activate QWEN3-1.7B-THINKING when file is not yet downloaded/imported
        var activationSuccess = true
        var failureMessage = ""
        ingestionManager.activateModel("QWEN3-1.7B-THINKING") { success, msg ->
            activationSuccess = success
            failureMessage = msg
        }

        assertFalse("Model must NOT be activated without file on disk", activationSuccess)
        assertTrue("Failure message must state file is missing on disk", failureMessage.contains("belum ada di disk", ignoreCase = true) || failureMessage.contains("File model belum ada", ignoreCase = true))

        // Check enum status remains BELUM_TERPASANG
        val qwenEntry = ingestionManager.catalogEntries.value.first { it.id == "QWEN3-1.7B-THINKING" }
        assertEquals(com.example.nexora.engine.model.ModelInstallStatus.BELUM_TERPASANG, qwenEntry.status)
    }

    @Test
    fun `BUG 3 - toggle persistence is instantaneous and single source of truth synchronized`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexoraDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nexoraDao()
        val settingsStore = com.example.nexora.model.settings.NexoraSettingsStore.getInstance(context)
        val registry = ToolRegistry(context, dao)

        // 1. Strict Local-First toggling WebSearchTool synchronously
        settingsStore.setOfflineEnforced(true)
        assertTrue(settingsStore.offlineEnforced.value)
        assertFalse("WebSearchTool must be disabled when Strict Local-First is ON", settingsStore.isToolEnabled("WebSearchTool"))

        // 2. Toggling WebSearchTool ON should automatically disengage strict offline
        registry.setToolEnabled("WebSearchTool", true)
        assertTrue("WebSearchTool must be enabled", settingsStore.isToolEnabled("WebSearchTool"))
        assertFalse("Strict Local-First must disengage when WebSearch is explicitly enabled", settingsStore.offlineEnforced.value)

        // 3. Toggling Non-Thinking Mode
        settingsStore.setNonThinkingMode(true)
        assertTrue(settingsStore.nonThinkingMode.value)
        settingsStore.setNonThinkingMode(false)
        assertFalse(settingsStore.nonThinkingMode.value)

        // 4. Toggling GPU acceleration
        settingsStore.setGpuEnabled(true)
        assertTrue(settingsStore.gpuEnabled.value)
        settingsStore.setGpuEnabled(false)
        assertFalse(settingsStore.gpuEnabled.value)

        db.close()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `BUG 1 - layout test on small phone screen 360x640 verifies activity boots cleanly`() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `BUG 1 - layout test on normal phone screen 412x915 verifies activity boots cleanly`() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun `BUG 1 - layout test on tablet screen 800x1280 verifies activity boots cleanly`() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
    }
}
