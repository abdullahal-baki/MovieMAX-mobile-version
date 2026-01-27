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
                return@withContext Result.failure(IllegalStateException("Missing GROQ_API_KEY"))
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
                put("model", "openai/gpt-oss-120b")
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put(
                                    "content",
                                    "You are a recommender. Return ONLY a JSON array of movie title strings, no extra text."
                                )
                            }
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", prompt)
                            }
                        )
                    }
                )
                put("temperature", 0.6)
                put("max_tokens", 800)
            }

            val url = "https://api.groq.com/openai/v1/chat/completions"
            val requestBody =
                bodyJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val snippet = body.take(200).replace("\n", " ").trim()
                        throw IOException("HTTP ${response.code}: $snippet")
                    }
                    val root = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
                    val choices = root.getJsonArray("choices")
                    val first = choices?.firstOrNull() as? JsonObject
                    val message = first?.getJsonObject("message")
                    val text = message?.getJsonPrimitive("content")?.content ?: ""

                    val cleaned = text
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val parsed = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
                    val list = extractStringArray(parsed ?: JsonPrimitive(cleaned))
                    if (list.isEmpty() && cleaned.isNotBlank()) {
                        val snippet = cleaned.take(160).replace("\n", " ").trim()
                        return@use Result.failure(IllegalStateException("Invalid AI response: $snippet"))
                    }
                    Result.success(list)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun extractStringArray(element: JsonElement): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { extractTitleFromElement(it) }
            is JsonObject -> extractFromObject(element)
            is JsonPrimitive -> extractFromText(element.content)
            else -> emptyList()
        }
    }

    private fun JsonObject.getJsonArray(key: String): JsonArray? =
        this[key] as? JsonArray

    private fun JsonObject.getJsonObject(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.getJsonPrimitive(key: String): JsonPrimitive? =
        this[key] as? JsonPrimitive

    private fun extractFromObject(obj: JsonObject): List<String> {
        val keys = listOf("movies", "titles", "recommendations", "results", "list")
        for (key in keys) {
            val value = obj[key]
            if (value is JsonArray) {
                return value.mapNotNull { extractTitleFromElement(it) }
            }
        }
        val single = extractTitleFromElement(obj)
        return if (single != null) listOf(single) else emptyList()
    }

    private fun extractTitleFromElement(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
            is JsonObject -> {
                val title = element.getJsonPrimitive("title")?.content
                val name = element.getJsonPrimitive("name")?.content
                val movie = element.getJsonPrimitive("movie")?.content
                val value = element.getJsonPrimitive("value")?.content
                listOf(title, name, movie, value).firstOrNull { !it.isNullOrBlank() }
            }
            else -> null
        }
    }

    private fun extractFromText(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        if ((trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            (trimmed.startsWith("{") && trimmed.endsWith("}"))
        ) {
            val reparsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            if (reparsed != null && reparsed != JsonPrimitive(trimmed)) {
                val parsedList = extractStringArray(reparsed)
                if (parsedList.isNotEmpty()) return parsedList
            }
        }
        val lines = trimmed.split("\n")
        val items = mutableListOf<String>()
        for (line in lines) {
            var item = line.trim()
            if (item.startsWith("-") || item.startsWith("*")) {
                item = item.drop(1).trim()
            }
            item = item.replace(Regex("^\\d+\\.?\\s*"), "").trim()
            item = item.trim().trim(',').trim('"')
            if (item.isNotBlank()) items.add(item)
        }
        if (items.isNotEmpty()) return items
        return trimmed.split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
    }
}
