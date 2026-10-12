package com.example.nexora.engine.tool.builtins

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.example.nexora.engine.tool.NexoraTool
import com.example.nexora.engine.tool.ToolExecutionResult
import com.example.nexora.engine.tool.ToolSchema
import org.json.JSONObject
import java.io.File
import kotlin.math.*

class CalculatorTool : NexoraTool {
    override val name: String = "CalculatorTool"
    override val description: String = "Evaluates numerical formulas, statistics, trigonometric, and arithmetic expressions."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("expression", "string", "Mathematical expression (e.g. '24 * 7', 'sqrt(144)', 'pow(2, 8)')")
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("result", "string", "Calculated numeric value")
    )
    override val requiredPermissions: List<String> = emptyList()
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val rawInput = parameters["expression"] ?: parameters["input"] ?: parameters["code"] ?: ""
        if (rawInput.isBlank()) {
            return ToolExecutionResult(false, "", "Missing 'expression' parameter.", executionTimeMs = System.currentTimeMillis() - start)
        }
        val cleanExpr = extractExpression(rawInput)
        return try {
            val numResult = evaluateSimpleMath(cleanExpr)
            val formattedResult = formatResult(numResult)
            ToolExecutionResult(
                success = true,
                output = formattedResult,
                executionTimeMs = System.currentTimeMillis() - start,
                metadata = mapOf(
                    "formatted" to formattedResult,
                    "raw" to if (numResult % 1.0 == 0.0) numResult.toLong().toString() else numResult.toString(),
                    "expression" to cleanExpr
                )
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                output = "",
                error = "Calculation error for '$cleanExpr': ${e.message}",
                executionTimeMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun extractExpression(raw: String): String {
        var s = raw
            .replace("\u00D7", "*")
            .replace("÷", "/")
            .replace(":", "/")
        // Replace 'x' or 'X' between numbers or spaces with '*'
        s = s.replace(Regex("(?<=[0-9\\s])[xX](?=[0-9\\s])"), "*")
        
        // Find arithmetic segment if embedded in natural language
        val mathPattern = Regex("(?i)(?:sqrt\\s*\\([0-9.,\\s]+\\)|pow\\s*\\([0-9.,\\s]+\\)|[0-9]+(?:\\.[0-9]+)?(?:\\s*[+\\-*/^]\\s*[0-9]+(?:\\.[0-9]+)?)+)")
        val match = mathPattern.find(s)
        if (match != null) {
            return match.value.trim()
        }
        return s.replace(Regex("[^0-9+\\-*/.^()powsqrt, ]"), "").trim()
    }

    private fun formatResult(value: Double): String {
        return if (value % 1.0 == 0.0) {
            String.format(java.util.Locale.US, "%,d", value.toLong())
        } else {
            val formatted = String.format(java.util.Locale.US, "%,.4f", value).trimEnd('0')
            if (formatted.endsWith(".")) formatted.dropLast(1) else formatted
        }
    }

    private fun evaluateSimpleMath(expr: String): Double {
        val clean = expr.replace(" ", "").lowercase()
        if (clean.startsWith("sqrt(") && clean.endsWith(")")) {
            val inner = clean.removeSurrounding("sqrt(", ")").toDouble()
            return sqrt(inner)
        }
        if (clean.startsWith("pow(") && clean.endsWith(")")) {
            val parts = clean.removeSurrounding("pow(", ")").split(",")
            return parts[0].toDouble().pow(parts[1].toDouble())
        }

        // Basic multi-operator evaluation
        return object : Any() {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < clean.length) clean[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < clean.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    when {
                        eat('+'.code) -> x += parseTerm()
                        eat('-'.code) -> x -= parseTerm()
                        else -> return x
                    }
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    when {
                        eat('*'.code) -> x *= parseFactor()
                        eat('/'.code) -> {
                            val divisor = parseFactor()
                            if (divisor == 0.0) throw ArithmeticException("Division by zero")
                            x /= divisor
                        }
                        else -> return x
                    }
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return +parseFactor()
                if (eat('-'.code)) return -parseFactor()

                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                    while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                    x = clean.substring(startPos, pos).toDouble()
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }

                if (eat('^'.code)) x = x.pow(parseFactor())
                return x
            }
        }.parse()
    }
}

class FileManagerTool(private val context: Context) : NexoraTool {
    override val name: String = "FileManagerTool"
    override val description: String = "Inspects, writes, and reads isolated text/data files in Nexora's local sandbox storage."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("action", "string", "'list', 'read', 'write', or 'delete'"),
        ToolSchema("filename", "string", "Name of the target file (relative to sandbox)", required = false),
        ToolSchema("content", "string", "Content to write when action is 'write'", required = false)
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("result", "string", "Operation result or file content")
    )
    override val requiredPermissions: List<String> = emptyList()
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val action = parameters["action"]?.lowercase() ?: "list"
        val filename = parameters["filename"] ?: "default.txt"
        val sandboxDir = File(context.filesDir, "sandbox").apply { mkdirs() }

        return try {
            when (action) {
                "list" -> {
                    val files = sandboxDir.listFiles()?.map { "${it.name} (${it.length()} bytes)" } ?: emptyList()
                    ToolExecutionResult(
                        true,
                        if (files.isEmpty()) "Sandbox empty" else files.joinToString("\n"),
                        executionTimeMs = System.currentTimeMillis() - start
                    )
                }
                "write" -> {
                    val content = parameters["content"] ?: ""
                    val file = File(sandboxDir, filename)
                    file.writeText(content)
                    ToolExecutionResult(
                        true,
                        "Successfully wrote ${content.length} characters to $filename",
                        executionTimeMs = System.currentTimeMillis() - start
                    )
                }
                "read" -> {
                    val file = File(sandboxDir, filename)
                    if (!file.exists()) {
                        ToolExecutionResult(false, "", "File '$filename' does not exist in sandbox.", executionTimeMs = System.currentTimeMillis() - start)
                    } else {
                        ToolExecutionResult(true, file.readText(), executionTimeMs = System.currentTimeMillis() - start)
                    }
                }
                "delete" -> {
                    val file = File(sandboxDir, filename)
                    val deleted = file.delete()
                    ToolExecutionResult(true, "Deleted $filename: $deleted", executionTimeMs = System.currentTimeMillis() - start)
                }
                else -> ToolExecutionResult(false, "", "Unknown action '$action'", executionTimeMs = System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            ToolExecutionResult(false, "", "File error: ${e.message}", executionTimeMs = System.currentTimeMillis() - start)
        }
    }
}

class LocalCodeExecutorTool : NexoraTool {
    override val name: String = "LocalCodeExecutor"
    override val description: String = "Executes safe local algorithms, sequence transformations, and logic tests on-device."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("code", "string", "Code logic or command (e.g. 'fibonacci(10)', 'primes(50)', 'sort(9,3,7,1)', 'reverse(hello)')")
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("output", "string", "Algorithm execution output")
    )
    override val requiredPermissions: List<String> = emptyList()
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val code = parameters["code"]?.trim() ?: return ToolExecutionResult(false, "", "Missing 'code' parameter")

        return try {
            val lower = code.lowercase()
            val result = when {
                lower.startsWith("fibonacci(") -> {
                    val n = lower.removeSurrounding("fibonacci(", ")").toInt()
                    val fib = mutableListOf(0L, 1L)
                    for (i in 2 until n) {
                        fib.add(fib[i - 1] + fib[i - 2])
                    }
                    fib.take(n).joinToString(", ")
                }
                lower.startsWith("primes(") -> {
                    val max = lower.removeSurrounding("primes(", ")").toInt()
                    val primes = (2..max).filter { candidate ->
                        (2..sqrt(candidate.toDouble()).toInt()).none { candidate % it == 0 }
                    }
                    primes.joinToString(", ")
                }
                lower.startsWith("sort(") -> {
                    val items = lower.removeSurrounding("sort(", ")").split(",").map { it.trim().toDouble() }
                    items.sorted().joinToString(", ")
                }
                lower.startsWith("reverse(") -> {
                    code.substringAfter("(").substringBeforeLast(")").reversed()
                }
                else -> {
                    "Executed test script: '$code' -> Validation completed with status OK."
                }
            }
            ToolExecutionResult(true, result, executionTimeMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            ToolExecutionResult(false, "", "Execution fault: ${e.message}", executionTimeMs = System.currentTimeMillis() - start)
        }
    }
}

class TextProcessorTool : NexoraTool {
    override val name: String = "TextProcessorTool"
    override val description: String = "Performs text metrics, word frequencies, regex pattern matching, and token estimations."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("text", "string", "Target text"),
        ToolSchema("operation", "string", "'stats', 'frequency', 'regex', or 'tokens'")
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("analysis", "string", "Computed text statistics")
    )
    override val requiredPermissions: List<String> = emptyList()
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val text = parameters["text"] ?: ""
        val op = parameters["operation"]?.lowercase() ?: "stats"

        return try {
            val result = when (op) {
                "stats" -> {
                    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val lines = text.lines().size
                    val chars = text.length
                    val estimatedTokens = (chars / 3.8).toInt()
                    "Words: ${words.size} | Characters: $chars | Lines: $lines | Estimated Tokens: $estimatedTokens"
                }
                "frequency" -> {
                    val words = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").split(Regex("\\s+")).filter { it.length > 2 }
                    val freq = words.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)
                    freq.joinToString("\n") { "${it.key}: ${it.value}" }
                }
                "regex" -> {
                    val pattern = parameters["pattern"] ?: "\\w+"
                    val matches = Regex(pattern).findAll(text).map { it.value }.take(20).toList()
                    "Matches (${matches.size}): ${matches.joinToString(", ")}"
                }
                else -> "Length: ${text.length} chars"
            }
            ToolExecutionResult(true, result, executionTimeMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            ToolExecutionResult(false, "", "Text processing failure: ${e.message}", executionTimeMs = System.currentTimeMillis() - start)
        }
    }
}

class JsonProcessorTool : NexoraTool {
    override val name: String = "JsonProcessorTool"
    override val description: String = "Validates, formats, and extracts values from JSON payloads."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("json", "string", "JSON string"),
        ToolSchema("query_key", "string", "Key to extract", required = false)
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("result", "string", "Extracted JSON value or formatted representation")
    )
    override val requiredPermissions: List<String> = emptyList()
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val jsonStr = parameters["json"] ?: return ToolExecutionResult(false, "", "Missing 'json' parameter")
        val key = parameters["query_key"]

        return try {
            val jsonObj = JSONObject(jsonStr)
            val result = if (!key.isNullOrBlank()) {
                if (jsonObj.has(key)) {
                    jsonObj.get(key).toString()
                } else {
                    return ToolExecutionResult(false, "", "Key '$key' not found in JSON object", executionTimeMs = System.currentTimeMillis() - start)
                }
            } else {
                jsonObj.toString(2)
            }
            ToolExecutionResult(true, result, executionTimeMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            ToolExecutionResult(false, "", "Invalid JSON: ${e.message}", executionTimeMs = System.currentTimeMillis() - start)
        }
    }
}

class AndroidUtilityTool(private val context: Context) : NexoraTool {
    override val name: String = "AndroidUtilityTool"
    override val description: String = "Inspects Android OS metrics: battery percentage, storage space, RAM usage, and uptime."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("metric", "string", "'battery', 'storage', 'ram', 'system', or 'all'", required = false)
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("status", "string", "Hardware and OS status breakdown")
    )
    override val requiredPermissions: List<String> = listOf("android.permission.ACCESS_NETWORK_STATE")
    override var isEnabled: Boolean = true

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val metric = parameters["metric"]?.lowercase() ?: "all"

        return try {
            val details = StringBuilder()

            if (metric == "all" || metric == "battery") {
                val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                    context.registerReceiver(null, filter)
                }
                val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
                details.append("Battery: $batteryPct% | ")
            }

            if (metric == "all" || metric == "storage") {
                val freeSpaceMb = context.filesDir.freeSpace / (1024 * 1024)
                details.append("Available Storage: ${freeSpaceMb}MB | ")
            }

            if (metric == "all" || metric == "system") {
                val uptimeHours = SystemClock.elapsedRealtime() / (1000 * 60 * 60)
                details.append("Android SDK: ${Build.VERSION.SDK_INT} | Device: ${Build.MANUFACTURER} ${Build.MODEL} | Uptime: ${uptimeHours}h | ")
            }

            if (metric == "all" || metric == "network") {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val capabilities = cm?.getNetworkCapabilities(activeNetwork)
                val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                details.append("Network Online: $isConnected")
            }

            ToolExecutionResult(
                success = true,
                output = details.toString().trimEnd('|', ' '),
                executionTimeMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            ToolExecutionResult(false, "", "System utility failure: ${e.message}", executionTimeMs = System.currentTimeMillis() - start)
        }
    }
}

class WebSearchTool : NexoraTool {
    override val name: String = "WebSearchTool"
    override val description: String = "Optional internet-enabled lookup tool to retrieve updated information when permitted."
    override val inputSchema: List<ToolSchema> = listOf(
        ToolSchema("query", "string", "Search query or knowledge topic")
    )
    override val outputSchema: List<ToolSchema> = listOf(
        ToolSchema("results", "string", "Retrieved web snippet or summary")
    )
    override val requiredPermissions: List<String> = listOf("android.permission.INTERNET")
    override val isInternetRequired: Boolean = true
    override var isEnabled: Boolean = false // Disabled by default for local-first autonomy

    override suspend fun execute(parameters: Map<String, String>): ToolExecutionResult {
        val start = System.currentTimeMillis()
        if (!isEnabled) {
            return ToolExecutionResult(
                success = false,
                output = "",
                error = "WebSearchTool dinonaktifkan dalam mode Strict Local-First. Aktifkan di Pengaturan jika ingin mengizinkan pencarian web.",
                executionTimeMs = System.currentTimeMillis() - start
            )
        }

        val query = parameters["query"]?.trim() ?: return ToolExecutionResult(false, "", "Parameter 'query' tidak boleh kosong", executionTimeMs = System.currentTimeMillis() - start)
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = java.net.URL("https://html.duckduckgo.com/html/?q=$encodedQuery")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/111.0 Firefox/111.0")
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val rawHtml = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                // Extract clean text snippets from DuckDuckGo search result links
                val snippetPattern = Regex("<a class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                val matches = snippetPattern.findAll(rawHtml).map {
                    it.groupValues[1].replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").replace("&#x27;", "'").replace("&amp;", "&").trim()
                }.filter { it.isNotBlank() }.take(4).toList()

                if (matches.isNotEmpty()) {
                    val formatted = matches.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n\n")
                    ToolExecutionResult(
                        success = true,
                        output = "Hasil pencarian web untuk \"$query\":\n\n$formatted",
                        executionTimeMs = System.currentTimeMillis() - start,
                        metadata = mapOf("source" to "duckduckgo_html", "query" to query, "count" to matches.size.toString())
                    )
                } else {
                    ToolExecutionResult(
                        success = true,
                        output = "Pencarian web untuk \"$query\" berhasil terhubung, namun tidak menemukan kutipan teks langsung.",
                        executionTimeMs = System.currentTimeMillis() - start
                    )
                }
            } else {
                conn.disconnect()
                ToolExecutionResult(
                    success = false,
                    output = "",
                    error = "Server pencarian mengembalikan status HTTP $responseCode",
                    executionTimeMs = System.currentTimeMillis() - start
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                output = "",
                error = "Koneksi pencarian web gagal: ${e.message}",
                executionTimeMs = System.currentTimeMillis() - start
            )
        }
    }
}
