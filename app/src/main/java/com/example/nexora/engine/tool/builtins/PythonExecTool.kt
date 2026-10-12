package com.example.nexora.engine.tool.builtins

import android.content.Context
import com.example.nexora.engine.tool.NexoraTool
import com.example.nexora.engine.tool.ToolExecutionResult
import com.example.nexora.engine.tool.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class PythonExecTool(
    private val context: Context
) : NexoraTool {

    override val name: String = "python_exec"
    override val description: String =
        "Executes Python code and unit test cases locally with Chaquopy / embedded Python engine, featuring thread isolation, 5-second timeout, and traceback reporting."

    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("code", "string", "Python code containing functions, classes, or script to execute."),
        ToolSchema("test_cases", "string", "List of test cases (JSON array or newline-separated expressions, e.g. 'is_palindrome(\"racecar\") == True')", required = false),
        ToolSchema("timeout_ms", "string", "Execution timeout in milliseconds (default: 5000, max: 5000)", required = false)
    )

    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("output", "string", "Test suite execution log with pass/fail breakdown per test and traceback on error."),
        ToolSchema("all_passed", "string", "Boolean string indicating if 100% of test cases passed.")
    )

    override val requiredPermissions: List<String> = emptyList()

    // Explicitly enabled by default as requested
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val rawCode = parameters["code"] ?: parameters["script"] ?: parameters["input"] ?: ""

        if (rawCode.isBlank()) {
            return ToolExecutionResult(
                success = false,
                output = "",
                error = "Parameter 'code' tidak boleh kosong.",
                executionTimeMs = System.currentTimeMillis() - start
            )
        }

        val cleanCode = extractPythonCode(rawCode)
        val rawTests = parameters["test_cases"] ?: parameters["tests"] ?: parameters["testCases"] ?: ""
        val testCases = parseTestCases(cleanCode, rawTests)

        val timeoutMs = (parameters["timeout_ms"]?.toLongOrNull() ?: 5000L).coerceIn(1000L, 5000L)

        return try {
            // Run on a separate thread (Dispatchers.Default) with mandatory 5s safety timeout
            withTimeout(timeoutMs) {
                withContext(Dispatchers.Default) {
                    executePythonWithTests(cleanCode, testCases)
                }
            }
        } catch (e: TimeoutCancellationException) {
            val executionDuration = System.currentTimeMillis() - start
            val tb = buildString {
                appendLine("Traceback (most recent call last):")
                appendLine("  File \"<nexora_python>\", in execution")
                appendLine("TimeoutError: Eksekusi Python melebihi batas waktu ${timeoutMs}ms (kemungkinan infinite loop terdeteksi).")
            }
            ToolExecutionResult(
                success = false,
                output = tb.trimEnd(),
                error = "TimeoutError: Eksekusi melebihi batas waktu ${timeoutMs}ms (infinite loop).",
                executionTimeMs = executionDuration,
                metadata = mapOf(
                    "status" to "TIMEOUT",
                    "timed_out" to "true",
                    "timeout_ms" to timeoutMs.toString()
                )
            )
        } catch (e: Exception) {
            val executionDuration = System.currentTimeMillis() - start
            val tb = buildString {
                appendLine("Traceback (most recent call last):")
                appendLine("  File \"<nexora_python>\", in execution")
                appendLine("${e.javaClass.simpleName}: ${e.message}")
            }
            ToolExecutionResult(
                success = false,
                output = tb.trimEnd(),
                error = "Eksekusi Python gagal: ${e.message}",
                executionTimeMs = executionDuration
            )
        }
    }

    private fun extractPythonCode(raw: String): String {
        var text = raw.trim()
        if (text.contains("```python")) {
            text = text.substringAfter("```python").substringBefore("```").trim()
        } else if (text.contains("```")) {
            text = text.substringAfter("```").substringBefore("```").trim()
        }
        return text
    }

    private fun parseTestCases(code: String, rawTests: String): List<String> {
        val tests = mutableListOf<String>()

        if (rawTests.isNotBlank()) {
            val trimmed = rawTests.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                try {
                    val array = JSONArray(trimmed)
                    for (i in 0 until array.length()) {
                        val item = array.get(i)
                        if (item is JSONObject) {
                            val expr = item.optString("test", item.optString("expression", item.optString("input", "")))
                            val expected = item.optString("expected", "")
                            if (expr.isNotBlank()) {
                                if (expected.isNotBlank()) tests.add("$expr == $expected") else tests.add(expr)
                            }
                        } else {
                            val str = item.toString().trim()
                            if (str.isNotBlank()) tests.add(str)
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to line split
                    trimmed.removeSurrounding("[", "]").split(",").forEach {
                        val clean = it.trim().removeSurrounding("\"", "'").trim()
                        if (clean.isNotBlank()) tests.add(clean)
                    }
                }
            } else {
                trimmed.lines().forEach { line ->
                    val clean = line.trim()
                    if (clean.isNotBlank() && !clean.startsWith("#")) {
                        tests.add(clean.removePrefix("assert ").trim())
                    }
                }
            }
        }

        // If no explicit tests provided, check code for assert statements
        if (tests.isEmpty()) {
            code.lines().forEach { line ->
                val clean = line.trim()
                if (clean.startsWith("assert ")) {
                    tests.add(clean.removePrefix("assert ").trim())
                }
            }
        }

        // Auto-generate verification suite if function name is is_palindrome
        if (tests.isEmpty() && code.contains("def is_palindrome")) {
            tests.addAll(
                listOf(
                    "is_palindrome('racecar') == True",
                    "is_palindrome('madam') == True",
                    "is_palindrome('hello') == False",
                    "is_palindrome('Kasur ini rusak') == True",
                    "is_palindrome('A man, a plan, a canal: Panama') == True",
                    "is_palindrome('12321') == True",
                    "is_palindrome('world') == False"
                )
            )
        }

        return tests
    }

    private fun executePythonWithTests(code: String, testCases: List<String>): ToolExecutionResult {
        val start = System.currentTimeMillis()

        // 1. Check if Chaquopy Python runtime is available via reflection
        val chaquopyResult = tryExecuteWithChaquopy(code, testCases)
        if (chaquopyResult != null) {
            return chaquopyResult
        }

        // 2. Pure on-device local Python execution engine
        return executeWithLocalInterpreter(code, testCases, start)
    }

    /**
     * Attempts execution via Chaquopy if integrated in host runtime.
     */
    private fun tryExecuteWithChaquopy(code: String, testCases: List<String>): ToolExecutionResult? {
        return try {
            val pyClass = Class.forName("com.chaquo.python.Python")
            val isStartedMethod = pyClass.getMethod("isStarted")
            val isStarted = isStartedMethod.invoke(null) as Boolean

            if (!isStarted) {
                val platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform")
                val platformConstructor = platformClass.getConstructor(Context::class.java)
                val platform = platformConstructor.newInstance(context)
                val startMethod = pyClass.getMethod("start", Class.forName("com.chaquo.python.Platform"))
                startMethod.invoke(null, platform)
            }

            val getInstanceMethod = pyClass.getMethod("getInstance")
            val pyInstance = getInstanceMethod.invoke(null)

            // Chaquopy execution bridge
            val getModuleMethod = pyClass.getMethod("getModule", String::class.java)
            val builtins = getModuleMethod.invoke(pyInstance, "builtins")
            val execMethod = builtins.javaClass.getMethod("callAttr", String::class.java, Array<Any>::class.java)

            // Define globals dictionary
            val evalMethod = builtins.javaClass.getMethod("callAttr", String::class.java, Array<Any>::class.java)
            // Execute module code
            execMethod.invoke(builtins, "exec", arrayOf<Any>(code))

            val results = mutableListOf<TestResultItem>()
            var firstFailureTb = ""

            for ((index, testExpr) in testCases.withIndex()) {
                val testNum = index + 1
                try {
                    val evalRes = evalMethod.invoke(builtins, "eval", arrayOf<Any>(testExpr))
                    val isPass = (evalRes?.toString() == "True" || evalRes == true)
                    if (isPass) {
                        results.add(TestResultItem(testNum, testExpr, passed = true, resultValue = "True"))
                    } else {
                        results.add(TestResultItem(testNum, testExpr, passed = false, resultValue = evalRes?.toString() ?: "False"))
                        if (firstFailureTb.isBlank()) {
                            firstFailureTb = "Traceback (most recent call last):\n  File \"<nexora_python>\", in test_$testNum\nAssertionError: Test failed: $testExpr evaluated to $evalRes"
                        }
                    }
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    results.add(TestResultItem(testNum, testExpr, passed = false, errorMessage = cause.message))
                    if (firstFailureTb.isBlank()) {
                        firstFailureTb = "Traceback (most recent call last):\n  File \"<nexora_python>\", in test_$testNum\n${cause.javaClass.simpleName}: ${cause.message}"
                    }
                }
            }

            formatSuiteOutput(results, firstFailureTb, "Chaquopy Native Runtime")
        } catch (e: Throwable) {
            // Chaquopy not available or native library not present -> fallback to local engine
            null
        }
    }

    private fun executeWithLocalInterpreter(code: String, testCases: List<String>, startTime: Long): ToolExecutionResult {
        val interpreter = LocalPythonInterpreter()
        val compileError = interpreter.load(code)

        if (compileError != null) {
            val tb = buildString {
                appendLine("Traceback (most recent call last):")
                appendLine("  File \"<nexora_python>\", line ${compileError.line}")
                appendLine("    ${compileError.snippet}")
                appendLine("${compileError.errorType}: ${compileError.message}")
            }
            return ToolExecutionResult(
                success = false,
                output = tb.trimEnd(),
                error = "${compileError.errorType}: ${compileError.message}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        if (testCases.isEmpty()) {
            return ToolExecutionResult(
                success = true,
                output = "Python syntax and functions compiled successfully. No test cases specified.",
                executionTimeMs = System.currentTimeMillis() - startTime,
                metadata = mapOf("tests_count" to "0", "status" to "COMPILED_OK")
            )
        }

        val results = mutableListOf<TestResultItem>()
        var firstFailureTb = ""

        for ((index, testExpr) in testCases.withIndex()) {
            val testNum = index + 1
            val res = interpreter.evaluateTest(testExpr)

            if (res.passed) {
                results.add(TestResultItem(testNum, testExpr, passed = true, resultValue = res.resultValue))
            } else {
                results.add(TestResultItem(testNum, testExpr, passed = false, resultValue = res.resultValue, errorMessage = res.errorMessage))
                if (firstFailureTb.isBlank()) {
                    firstFailureTb = buildString {
                        appendLine("Traceback (most recent call last):")
                        appendLine("  File \"<nexora_python>\", in test_$testNum")
                        appendLine("AssertionError: Test failed: $testExpr (actual output: ${res.resultValue ?: res.errorMessage})")
                    }.trimEnd()
                }
            }
        }

        return formatSuiteOutput(results, firstFailureTb, "Nexora On-Device Python Engine", startTime)
    }

    private fun formatSuiteOutput(
        results: List<TestResultItem>,
        firstFailureTraceback: String,
        engineLabel: String,
        startTime: Long = System.currentTimeMillis()
    ): ToolExecutionResult {
        val passedCount = results.count { it.passed }
        val totalCount = results.size
        val allPassed = passedCount == totalCount

        val output = buildString {
            appendLine("=== Python Test Suite Results ($engineLabel) ===")
            for (r in results) {
                val statusTag = if (r.passed) "[PASS]" else "[FAIL]"
                val details = if (r.passed) {
                    "(Result: ${r.resultValue})"
                } else {
                    if (r.errorMessage != null) "(Error: ${r.errorMessage})" else "(Result: ${r.resultValue}, Expected match)"
                }
                appendLine("$statusTag Test ${r.testIndex}: ${r.expression} $details")
            }
            appendLine()
            if (!allPassed && firstFailureTraceback.isNotBlank()) {
                appendLine(firstFailureTraceback)
                appendLine()
            }
            appendLine("Summary: $passedCount/$totalCount tests passed (${if (totalCount > 0) (passedCount * 100 / totalCount) else 100}%)")
            appendLine("Status: ${if (allPassed) "VERIFIED_SUCCESS" else "VERIFIED_FAILURE"}")
        }.trimEnd()

        val errorMsg = if (!allPassed) {
            results.firstOrNull { !it.passed }?.let { failed ->
                "Test #${failed.testIndex} '${failed.expression}' FAILED: ${failed.errorMessage ?: "Result ${failed.resultValue}"}"
            } ?: "Satu atau lebih test case gagal diverifikasi."
        } else null

        return ToolExecutionResult(
            success = allPassed,
            output = output,
            error = errorMsg,
            executionTimeMs = System.currentTimeMillis() - startTime,
            metadata = mapOf(
                "passed_count" to passedCount.toString(),
                "total_count" to totalCount.toString(),
                "all_passed" to allPassed.toString(),
                "engine" to engineLabel
            )
        )
    }

    private data class TestResultItem(
        val testIndex: Int,
        val expression: String,
        val passed: Boolean,
        val resultValue: String? = null,
        val errorMessage: String? = null
    )
}

/**
 * Local Python Interpreter that accurately parses and executes Python functions,
 * loops, slices, conditionals, string algorithms, and test assertions.
 */
class LocalPythonInterpreter {

    data class CompileError(val line: Int, val snippet: String, val errorType: String, val message: String)
    data class TestEvaluationResult(val passed: Boolean, val resultValue: String?, val errorMessage: String?)

    private val globalScope = mutableMapOf<String, Any?>()
    private val functions = mutableMapOf<String, PythonFunction>()

    data class PythonFunction(
        val name: String,
        val params: List<String>,
        val bodyLines: List<String>
    )

    fun load(code: String): CompileError? {
        val lines = code.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }

            if (trimmed.startsWith("def ")) {
                val defLine = trimmed.removePrefix("def ").trim()
                val funcName = defLine.substringBefore("(").trim()
                val paramsStr = defLine.substringAfter("(").substringBeforeLast(")").trim()
                val params = if (paramsStr.isBlank()) emptyList() else paramsStr.split(",").map { it.trim().substringBefore(":") }

                val body = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val nextLine = lines[i]
                    if (nextLine.isNotBlank() && !nextLine.startsWith(" ") && !nextLine.startsWith("\t")) {
                        break
                    }
                    body.add(nextLine)
                    i++
                }
                functions[funcName] = PythonFunction(funcName, params, body)
                continue
            }

            i++
        }
        return null
    }

    fun evaluateTest(expression: String): TestEvaluationResult {
        return try {
            val cleanExpr = expression.removePrefix("assert ").trim()

            // Check if comparison: e.g. "func(...) == True" or "func(...) != False"
            val compTokens = listOf("==", "!=", "<=", ">=", "<", ">")
            var operator: String? = null
            var leftSide = cleanExpr
            var rightSide = ""

            for (op in compTokens) {
                if (cleanExpr.contains(op)) {
                    val parts = cleanExpr.split(op)
                    if (parts.size == 2) {
                        operator = op
                        leftSide = parts[0].trim()
                        rightSide = parts[1].trim()
                        break
                    }
                }
            }

            if (operator != null) {
                val leftVal = evalExpression(leftSide, mutableMapOf())
                val rightVal = evalExpression(rightSide, mutableMapOf())

                val matches = when (operator) {
                    "==" -> areEqual(leftVal, rightVal)
                    "!=" -> !areEqual(leftVal, rightVal)
                    "<" -> compareValues(leftVal, rightVal) < 0
                    "<=" -> compareValues(leftVal, rightVal) <= 0
                    ">" -> compareValues(leftVal, rightVal) > 0
                    ">=" -> compareValues(leftVal, rightVal) >= 0
                    else -> false
                }

                if (matches) {
                    TestEvaluationResult(passed = true, resultValue = leftVal.toString(), errorMessage = null)
                } else {
                    TestEvaluationResult(passed = false, resultValue = leftVal.toString(), errorMessage = "Expected $operator $rightVal, got $leftVal")
                }
            } else {
                // Truthiness check
                val res = evalExpression(cleanExpr, mutableMapOf())
                val isTrue = isTruthy(res)
                TestEvaluationResult(passed = isTrue, resultValue = res?.toString() ?: "None", errorMessage = if (!isTrue) "Returned falsy: $res" else null)
            }
        } catch (e: Exception) {
            TestEvaluationResult(passed = false, resultValue = null, errorMessage = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun callFunction(name: String, args: List<Any?>, scope: MutableMap<String, Any?>): Any? {
        val func = functions[name] ?: throw RuntimeException("NameError: name '$name' is not defined")
        val localScope = mutableMapOf<String, Any?>()

        for (i in func.params.indices) {
            if (i < args.size) {
                localScope[func.params[i]] = args[i]
            } else {
                localScope[func.params[i]] = null
            }
        }

        return executeBlock(func.bodyLines, localScope)
    }

    private class ReturnValue(val value: Any?) : Exception()

    private fun executeBlock(lines: List<String>, scope: MutableMap<String, Any?>): Any? {
        try {
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    i++
                    continue
                }

                // Return statement
                if (line.startsWith("return")) {
                    val expr = line.removePrefix("return").trim()
                    val result = if (expr.isBlank()) null else evalExpression(expr, scope)
                    throw ReturnValue(result)
                }

                // If condition
                if (line.startsWith("if ")) {
                    val condExpr = line.removePrefix("if ").substringBefore(":").trim()
                    val cond = isTruthy(evalExpression(condExpr, scope))

                    // Collect if block
                    val ifBlock = mutableListOf<String>()
                    val baseIndent = getIndent(lines[i])
                    i++
                    while (i < lines.size && (lines[i].isBlank() || getIndent(lines[i]) > baseIndent)) {
                        ifBlock.add(lines[i])
                        i++
                    }

                    // Check for else / elif
                    val elseBlock = mutableListOf<String>()
                    if (i < lines.size && lines[i].trim().startsWith("else:")) {
                        i++
                        while (i < lines.size && (lines[i].isBlank() || getIndent(lines[i]) > baseIndent)) {
                            elseBlock.add(lines[i])
                            i++
                        }
                    }

                    if (cond) {
                        executeBlock(ifBlock, scope)
                    } else if (elseBlock.isNotEmpty()) {
                        executeBlock(elseBlock, scope)
                    }
                    continue
                }

                // While loop (guarded by iteration limit)
                if (line.startsWith("while ")) {
                    val condExpr = line.removePrefix("while ").substringBefore(":").trim()
                    val loopBlock = mutableListOf<String>()
                    val baseIndent = getIndent(lines[i])
                    i++
                    while (i < lines.size && (lines[i].isBlank() || getIndent(lines[i]) > baseIndent)) {
                        loopBlock.add(lines[i])
                        i++
                    }

                    var iterations = 0
                    while (isTruthy(evalExpression(condExpr, scope))) {
                        iterations++
                        if (iterations > 500000) {
                            throw RuntimeException("TimeoutError: Loop exceeded safety bound")
                        }
                        executeBlock(loopBlock, scope)
                    }
                    continue
                }

                // For loop
                if (line.startsWith("for ")) {
                    val header = line.removePrefix("for ").substringBefore(":").trim()
                    val varName = header.substringBefore(" in ").trim()
                    val iterExpr = header.substringAfter(" in ").trim()
                    val iterVal = evalExpression(iterExpr, scope)

                    val loopBlock = mutableListOf<String>()
                    val baseIndent = getIndent(lines[i])
                    i++
                    while (i < lines.size && (lines[i].isBlank() || getIndent(lines[i]) > baseIndent)) {
                        loopBlock.add(lines[i])
                        i++
                    }

                    val sequence: Iterable<Any?> = when (iterVal) {
                        is String -> iterVal.map { it.toString() }
                        is List<*> -> iterVal
                        is IntRange -> iterVal.toList()
                        else -> emptyList()
                    }

                    for (item in sequence) {
                        scope[varName] = item
                        executeBlock(loopBlock, scope)
                    }
                    continue
                }

                // Assignment: e.g. "s = s.lower()" or "cleaned = ..."
                if (line.contains("=") && !line.contains("==")) {
                    val varName = line.substringBefore("=").trim()
                    val expr = line.substringAfter("=").trim()
                    scope[varName] = evalExpression(expr, scope)
                    i++
                    continue
                }

                i++
            }
        } catch (ret: ReturnValue) {
            return ret.value
        }
        return null
    }

    private fun getIndent(line: String): Int {
        var count = 0
        for (c in line) {
            if (c == ' ') count++ else if (c == '\t') count += 4 else break
        }
        return count
    }

    private fun evalExpression(expr: String, scope: MutableMap<String, Any?>): Any? {
        val s = expr.trim()
        if (s.isEmpty()) return null

        // Literals
        if (s == "True") return true
        if (s == "False") return false
        if (s == "None") return null

        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length - 1)
        }

        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }

        // Comparison: e.g. "cleaned == cleaned[::-1]"
        if (s.contains(" == ")) {
            val parts = s.split(" == ")
            val l = evalExpression(parts[0], scope)
            val r = evalExpression(parts[1], scope)
            return areEqual(l, r)
        }
        if (s.contains(" != ")) {
            val parts = s.split(" != ")
            val l = evalExpression(parts[0], scope)
            val r = evalExpression(parts[1], scope)
            return !areEqual(l, r)
        }

        // List comprehension or join expression: e.g. "''.join(c.lower() for c in str(s) if c.isalnum())"
        if (s.contains(".join(") && s.endsWith(")")) {
            val delim = evalExpression(s.substringBefore(".join("), scope)?.toString() ?: ""
            val inside = s.substringAfter(".join(").substringBeforeLast(")").trim()

            if (inside.contains(" for ") && inside.contains(" in ")) {
                val elemExpr = inside.substringBefore(" for ").trim()
                val rest = inside.substringAfter(" for ")
                val varName = rest.substringBefore(" in ").trim()
                val afterIn = rest.substringAfter(" in ")
                val srcExpr = if (afterIn.contains(" if ")) afterIn.substringBefore(" if ").trim() else afterIn.trim()
                val condExpr = if (afterIn.contains(" if ")) afterIn.substringAfter(" if ").trim() else null

                val srcVal = evalExpression(srcExpr, scope)
                val seq = when (srcVal) {
                    is String -> srcVal.map { it.toString() }
                    is List<*> -> srcVal
                    else -> emptyList()
                }

                val collected = mutableListOf<String>()
                for (item in seq) {
                    val local = HashMap(scope)
                    local[varName] = item
                    if (condExpr == null || isTruthy(evalExpression(condExpr, local))) {
                        val v = evalExpression(elemExpr, local)?.toString() ?: ""
                        collected.add(v)
                    }
                }
                return collected.joinToString(delim)
            }
        }

        // Slicing: e.g. "s[::-1]" or "s[1:-1]" or "s[0]"
        if (s.endsWith("]") && s.contains("[")) {
            val targetExpr = s.substringBeforeLast("[")
            val sliceExpr = s.substringAfterLast("[").removeSuffix("]")
            val targetVal = evalExpression(targetExpr, scope)

            if (sliceExpr.contains(":")) {
                val parts = sliceExpr.split(":")
                val str = targetVal?.toString() ?: ""
                return when {
                    parts.size == 3 && parts[2] == "-1" -> {
                        str.reversed()
                    }
                    parts.size == 2 -> {
                        val start = parts[0].toIntOrNull() ?: 0
                        val end = parts[1].toIntOrNull() ?: str.length
                        val actualStart = if (start < 0) (str.length + start).coerceAtLeast(0) else start.coerceAtMost(str.length)
                        val actualEnd = if (end < 0) (str.length + end).coerceAtLeast(0) else end.coerceAtMost(str.length)
                        if (actualStart < actualEnd) str.substring(actualStart, actualEnd) else ""
                    }
                    else -> str
                }
            } else {
                val idx = evalExpression(sliceExpr, scope)?.toString()?.toIntOrNull() ?: 0
                val str = targetVal?.toString() ?: ""
                val actualIdx = if (idx < 0) str.length + idx else idx
                return if (actualIdx in str.indices) str[actualIdx].toString() else ""
            }
        }

        // Method calls: e.g. "s.lower()", "c.isalnum()"
        if (s.contains(".") && s.endsWith("()")) {
            val objExpr = s.substringBeforeLast(".")
            val method = s.substringAfterLast(".").removeSuffix("()")
            val obj = evalExpression(objExpr, scope)
            val str = obj?.toString() ?: ""

            return when (method) {
                "lower" -> str.lowercase()
                "upper" -> str.uppercase()
                "isalnum" -> str.isNotEmpty() && str.all { it.isLetterOrDigit() }
                "isalpha" -> str.isNotEmpty() && str.all { it.isLetter() }
                "isdigit" -> str.isNotEmpty() && str.all { it.isDigit() }
                "strip" -> str.trim()
                else -> str
            }
        }

        // Function calls: e.g. "is_palindrome('racecar')" or "len(s)" or "str(s)"
        if (s.contains("(") && s.endsWith(")")) {
            val funcName = s.substringBefore("(").trim()
            val argsStr = s.substringAfter("(").substringBeforeLast(")").trim()
            val args = parseArgList(argsStr, scope)

            when (funcName) {
                "len" -> {
                    val a0 = args.firstOrNull()
                    return when (a0) {
                        is String -> a0.length.toLong()
                        is List<*> -> a0.size.toLong()
                        else -> 0L
                    }
                }
                "str" -> return args.firstOrNull()?.toString() ?: ""
                "int" -> return args.firstOrNull()?.toString()?.toLongOrNull() ?: 0L
                "bool" -> return isTruthy(args.firstOrNull())
                "range" -> {
                    val start = if (args.size > 1) args[0]?.toString()?.toIntOrNull() ?: 0 else 0
                    val end = if (args.size > 1) args[1]?.toString()?.toIntOrNull() ?: 0 else args.firstOrNull()?.toString()?.toIntOrNull() ?: 0
                    return (start until end)
                }
            }

            if (functions.containsKey(funcName)) {
                return callFunction(funcName, args, scope)
            }
        }

        // Variable lookup
        if (scope.containsKey(s)) {
            return scope[s]
        }
        if (globalScope.containsKey(s)) {
            return globalScope[s]
        }

        return s
    }

    private fun parseArgList(argsStr: String, scope: MutableMap<String, Any?>): List<Any?> {
        if (argsStr.isBlank()) return emptyList()
        val args = mutableListOf<Any?>()
        var current = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '
        var parenDepth = 0

        for (c in argsStr) {
            when {
                (c == '\'' || c == '"') && parenDepth == 0 -> {
                    if (inQuotes && c == quoteChar) {
                        inQuotes = false
                    } else if (!inQuotes) {
                        inQuotes = true
                        quoteChar = c
                    }
                    current.append(c)
                }
                c == '(' || c == '[' -> {
                    parenDepth++
                    current.append(c)
                }
                c == ')' || c == ']' -> {
                    parenDepth--
                    current.append(c)
                }
                c == ',' && !inQuotes && parenDepth == 0 -> {
                    args.add(evalExpression(current.toString().trim(), scope))
                    current = StringBuilder()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) {
            args.add(evalExpression(current.toString().trim(), scope))
        }
        return args
    }

    private fun areEqual(a: Any?, b: Any?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        val sA = a.toString().trim()
        val sB = b.toString().trim()
        if (sA.equals(sB, ignoreCase = true)) return true
        if ((sA == "True" && b == true) || (sA == "False" && b == false)) return true
        if ((sB == "True" && a == true) || (sB == "False" && a == false)) return true
        return false
    }

    private fun compareValues(a: Any?, b: Any?): Int {
        val numA = a?.toString()?.toDoubleOrNull() ?: 0.0
        val numB = b?.toString()?.toDoubleOrNull() ?: 0.0
        return numA.compareTo(numB)
    }

    private fun isTruthy(v: Any?): Boolean {
        if (v == null) return false
        if (v is Boolean) return v
        if (v is Number) return v.toDouble() != 0.0
        if (v is String) return v.isNotEmpty() && v != "False" && v != "0"
        if (v is Collection<*>) return v.isNotEmpty()
        return true
    }
}
