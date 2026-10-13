package com.example.nexora.engine.api

import com.example.nexora.engine.model.ModelContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class GeminiApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Streams responses directly from the Gemini API model via SSE (Server-Sent Events).
     * Yields authentic, dynamic reasoning tokens without static templates.
     */
    fun generateStream(
        prompt: String,
        context: ModelContext,
        apiKey: String,
        modelName: String = "gemini-3.5-flash"
    ): Flow<String> = flow {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            throw IllegalStateException("API Key belum terpasang. Masukkan Gemini API Key di Pengaturan untuk inferensi cerdas langsung dari model AI.")
        }

        val effectiveModel = if (modelName.isBlank() || modelName == "gemini-2.5-flash" || modelName == "gemini-2.0-flash" || modelName == "gemini-1.5-flash") {
            "gemini-3.5-flash"
        } else {
            modelName
        }

        val requestJson = JSONObject()

        // 1. System Instruction
        val systemText = buildString {
            append("Anda adalah asisten cerdas AI otonom pada sistem Nexora.\n")
            append("ATURAN KERAS PENTING: DILARANG menggunakan respons template, kata-kata kaku boilerplate, atau jawaban tiruan yang berulang-ulang.\n")
            append("Jawablah secara otentik, cerdas, solutif, langsung pada poin masalah, dan gunakan bahasa alami yang ramah serta akurat.\n")
            if (!context.nonThinkingMode) {
                append("Gunakan tag <think>...</think> di awal respons untuk memaparkan alur penalaran Anda secara transparan sebelum memberikan jawaban akhir.\n")
            }
            if (context.recalledMemories.isNotEmpty()) {
                append("\nMemori relevan dari pengalaman sebelumnya:\n")
                context.recalledMemories.forEach { append("- ").append(it).append("\n") }
            }
            if (context.availableTools.isNotEmpty()) {
                append("\nAlat lokal yang tersedia di perangkat: ").append(context.availableTools.joinToString(", ")).append("\n")
            }
        }

        requestJson.put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", systemText) })
            })
        })

        // 2. Contents with conversation history
        val contentsArray = JSONArray()
        for ((sender, text) in context.conversationHistory.takeLast(6)) {
            val role = if (sender.equals("USER", ignoreCase = true)) "user" else "model"
            contentsArray.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", text) })
                })
            })
        }

        // Current prompt
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        })
        requestJson.put("contents", contentsArray)

        // 3. Generation Config
        val genConfig = JSONObject().apply {
            put("temperature", 0.7)
            put("topP", 0.95)
        }
        requestJson.put("generationConfig", genConfig)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effectiveModel:streamGenerateContent?alt=sse&key=$cleanKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Accept", "text/event-stream")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string().orEmpty()
            val parsedMsg = try {
                val errObj = JSONObject(errBody)
                errObj.optJSONObject("error")?.optString("message") ?: errBody
            } catch (_: Exception) {
                errBody
            }
            throw IllegalStateException("Gemini API (${response.code}): ${parsedMsg.ifBlank { "Permintaan ke model AI gagal." }}")
        }

        val responseBody = response.body ?: throw IllegalStateException("Respons dari model AI kosong.")
        val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim().orEmpty()
            if (l.startsWith("data:")) {
                val jsonPart = l.removePrefix("data:").trim()
                if (jsonPart.isNotBlank() && jsonPart != "[DONE]") {
                    try {
                        val parsed = JSONObject(jsonPart)
                        val candidates = parsed.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)
                                    val text = part.optString("text")
                                    if (text.isNotEmpty()) {
                                        emit(text)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore malformed SSE framing chunks
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
