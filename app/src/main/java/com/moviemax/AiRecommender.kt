package com.moviemax

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiRecommender(private val apiKey: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun recommendTitles(historyTitles: List<String>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Missing GEMINI_API_KEY"))
            }
            if (historyTitles.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val prompt = buildString {
                appendLine("User watched these movies:")
                historyTitles.take(20).forEach { appendLine("- $it") }
                appendLine()
                appendLine("Recommend 60 movie titles based on this history.")
                appendLine("Return ONLY a JSON array of movie name strings, no extra text.")
                appendLine("Example: [\"iron man\", \"the avengers\"]")
            }

            val bodyJson = buildJsonObject {
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", prompt) })
                                    }
                                )
                            }
                        )
                    }
                )
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("temperature", 0.6)
                        put("maxOutputTokens", 800)
                        put("responseMimeType", "application/json")
                    }
                )
            }

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val requestBody =
                bodyJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val snippet = body.take(200).replace("\n", " ").trim()
                        throw IOException("HTTP ${response.code}: $snippet")
                    }
                    val root = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
                    val candidates = root.getJsonArray("candidates")
                    val candidateObj = (candidates?.firstOrNull() as? JsonObject)
                    val contentObj = candidateObj?.getJsonObject("content")
                    val parts = contentObj?.getJsonArray("parts")
                    val partObj = (parts?.firstOrNull() as? JsonObject)
                    val text = partObj?.getJsonPrimitive("text")?.content ?: ""

                    val cleaned = text
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val parsed = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
                    val list = extractStringArray(parsed ?: JsonPrimitive(cleaned))
                    Result.success(list)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun extractStringArray(element: JsonElement): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull() }.map { it.content }
            is JsonPrimitive -> {
                element.content.split("\n", ",")
                    .map { it.trim().trim('"') }
                    .filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
        this as? JsonPrimitive

    private fun JsonObject.getJsonArray(key: String): JsonArray? =
        this[key] as? JsonArray

    private fun JsonObject.getJsonObject(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.getJsonPrimitive(key: String): JsonPrimitive? =
        this[key] as? JsonPrimitive
}
