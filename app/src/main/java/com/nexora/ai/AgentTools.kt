package com.nexora.ai

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow

internal data class AgentToolCall(val name: String, val arguments: JSONObject)

internal object AgentToolRuntime {
    private val callPattern = Regex(
        """<nexora_tool_call>\s*(\{.*?})\s*</nexora_tool_call>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun promptInstructions(): String = """
        Nexora exposes a small allowlisted tool registry. Use a tool only when it materially helps.
        To invoke exactly one tool, output only this envelope, with valid JSON and no Markdown:
        <nexora_tool_call>{"name":"web_search","arguments":{"query":"search terms"}}</nexora_tool_call>
        Available tools:
        - web_search: search public web pages. Arguments: {"query":"..."}.
        - web_open: read a public HTTPS web page. Arguments: {"url":"https://..."}.
        - calculator: evaluate a mathematical expression. Arguments: {"expression":"..."}.
        - current_time: get the device's local date/time. Arguments: {}.
        Tool results arrive in a following user message. Use those results; do not fabricate
        search results, web pages, calculations, or tool execution. A tool can be disabled.
        If a tool reports an error, explain the limitation and continue honestly.
        Never emit a tool-call envelope as the final user-facing answer.
    """.trimIndent()

    fun extractToolCall(text: String): AgentToolCall? {
        val match = callPattern.find(text) ?: return null
        return runCatching {
            val json = JSONObject(match.groupValues[1])
            val name = json.optString("name").trim()
            require(name.isNotBlank()) { "Tool name is missing." }
            AgentToolCall(name, json.optJSONObject("arguments") ?: JSONObject())
        }.getOrElse { error ->
            AgentToolCall("invalid_request", JSONObject().put("error", error.message ?: "Invalid JSON"))
        }
    }

    suspend fun execute(call: AgentToolCall, networkEnabled: Boolean): String =
        withContext(Dispatchers.IO) {
            runCatching {
                when (call.name) {
                    "calculator" -> {
                        val expression = call.arguments.optString("expression").trim()
                        require(expression.isNotBlank() && expression.length <= 256) { "Invalid or oversized expression." }
                        val value = MathParser(expression).parse()
                        "Result: " + String.format(Locale.US, "%.12g", value)
                    }
                    "current_time" ->
                        "Device local time: " + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    "web_search" -> {
                        if (!networkEnabled) "ERROR: Online skills are disabled. Enable Web Search in Skills."
                        else {
                            val query = call.arguments.optString("query").trim()
                            require(query.isNotBlank() && query.length <= 300) { "Invalid or oversized search query." }
                            search(query)
                        }
                    }
                    "web_open" -> {
                        if (!networkEnabled) "ERROR: Online skills are disabled. Enable Web Search in Skills."
                        else openPage(call.arguments.optString("url").trim())
                    }
                    "invalid_request" -> "ERROR: Invalid tool-call JSON: " + call.arguments.optString("error")
                    else -> "ERROR: Unknown tool '" + call.name + "'."
                }
            }.getOrElse { error -> "ERROR: " + (error.message ?: error.javaClass.simpleName) }
        }

    private fun search(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val html = fetchHttps("https://html.duckduckgo.com/html/?q=" + encoded)
        val linkPattern = Regex(
            """(?is)<a\b(?=[^>]*class=["'][^"']*\bresult__a\b[^"']*["'])(?=[^>]*href=["']([^"']+)["'])[^>]*>(.*?)</a>"""
        )
        val snippetPattern = Regex(
            """(?is)<(?:a|div)[^>]*class=["'][^"']*\bresult__snippet\b[^"']*["'][^>]*>(.*?)</(?:a|div)>"""
        )
        val links = linkPattern.findAll(html).take(6).toList()
        val snippets = snippetPattern.findAll(html).map { clean(it.groupValues[1]) }.toList()
        if (links.isEmpty()) return "No structured search results were found. Try a more specific query."
        return buildString {
            append("Search query: ").append(query).append("\n")
            links.forEachIndexed { index, match ->
                val href = resolveSearchUrl(match.groupValues[1])
                if (safeUrl(href)) {
                    append(index + 1).append(". ").append(clean(match.groupValues[2]).take(240)).append("\n")
                    append("URL: ").append(href).append("\n")
                    snippets.getOrNull(index)?.take(700)?.takeIf { it.isNotBlank() }?.let {
                        append("Summary: ").append(it).append("\n")
                    }
                    append("\n")
                }
            }
        }.ifBlank { "No safe HTTPS results were returned." }.take(8_000)
    }

    private fun openPage(url: String): String {
        require(safeUrl(url)) { "Only public HTTPS pages can be opened." }
        val html = fetchHttps(url)
        val title = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val body = html
            .replace(Regex("""(?is)<(script|style|noscript|svg)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?is)<!--.*?-->"""), " ")
        val pageText = clean(body).replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
        return buildString {
            append("URL: ").append(url).append("\n")
            if (title.isNotBlank()) append("Title: ").append(title).append("\n")
            append("Page excerpt:\n").append(pageText.take(8_000))
            if (pageText.length > 8_000) append("\n[Truncated]")
        }
    }

    private fun fetchHttps(initial: String): String {
        var current = initial
        for (redirects in 0..3) {
            require(safeUrl(current)) { "Blocked non-public or non-HTTPS URL." }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Nexora-Android/2.0")
                setRequestProperty("Accept", "text/html, text/plain;q=0.9")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    require(redirects < 3) { "Too many redirects." }
                    val location = connection.getHeaderField("Location") ?: error("Missing redirect location.")
                    current = URI(current).resolve(location).toString()
                    require(safeUrl(current)) { "Redirect to a blocked URL was refused." }
                    continue
                }
                require(code in 200..299) { "HTTP status " + code }
                val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                require(type.contains("text/html") || type.contains("text/plain") || type.isBlank()) {
                    "Only HTML and plain text pages are supported."
                }
                val bytes = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 1_000_000) { "Response exceeds 1 MB." }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                return String(bytes, StandardCharsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
        error("Redirect limit reached.")
    }

    private fun safeUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching false
        uri.scheme.equals("https", true) && host.isNotBlank() &&
            host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
            !host.matches(Regex("""[0-9.]+""")) && !host.contains(":") && uri.userInfo == null
    }.getOrDefault(false)

    private fun resolveSearchUrl(value: String): String = runCatching {
        val uri = if (value.startsWith("https://", true)) URI(value)
            else URI("https://html.duckduckgo.com" + value)
        val redirect = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("uddg=") }
        if (redirect != null) URLDecoder.decode(redirect.substringAfter('='), "UTF-8") else value
    }.getOrDefault(value)

    private fun clean(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00a0', ' ').replace(Regex("""[ \t\r]+"""), " ").trim()

    private class MathParser(private val source: String) {
        private var index = 0
        fun parse(): Double {
            val value = expression()
            whitespace()
            require(index == source.length) { "Unexpected token at position " + (index + 1) }
            require(value.isFinite()) { "Non-finite result." }
            return value
        }
        private fun expression(): Double {
            var value = term()
            while (true) {
                whitespace()
                value = when {
                    consume('+') -> value + term()
                    consume('-') -> value - term()
                    else -> return value
                }
            }
        }
        private fun term(): Double {
            var value = power()
            while (true) {
                whitespace()
                value = when {
                    consume('*') -> value * power()
                    consume('/') -> {
                        val divisor = power()
                        require(divisor != 0.0) { "Division by zero." }
                        value / divisor
                    }
                    else -> return value
                }
            }
        }
        private fun power(): Double {
            val base = unary()
            whitespace()
            return if (consume('^')) {
                val exponent = power()
                require(kotlin.math.abs(exponent) <= 1_000.0) { "Exponent too large." }
                base.pow(exponent).also { require(it.isFinite()) { "Non-finite result." } }
            } else base
        }
        private fun unary(): Double {
            whitespace()
            return when {
                consume('+') -> unary()
                consume('-') -> -unary()
                else -> primary()
            }
        }
        private fun primary(): Double {
            whitespace()
            if (consume('(')) {
                val value = expression()
                whitespace()
                require(consume(')')) { "Missing closing parenthesis." }
                return value
            }
            val start = index
            var decimalSeen = false
            while (index < source.length) {
                val char = source[index]
                if (char.isDigit()) index++
                else if (char == '.' && !decimalSeen) { decimalSeen = true; index++ }
                else break
            }
            require(index > start) { "Expected a number at position " + (index + 1) }
            return source.substring(start, index).toDouble()
        }
        private fun consume(char: Char): Boolean {
            if (index < source.length && source[index] == char) { index++; return true }
            return false
        }
        private fun whitespace() { while (index < source.length && source[index].isWhitespace()) index++ }
    }
}
