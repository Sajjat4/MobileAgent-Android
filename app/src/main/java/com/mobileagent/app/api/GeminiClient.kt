package com.mobileagent.app.api

import android.graphics.Bitmap
import android.util.Log
import com.mobileagent.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiClient(private val config: ApiConfig) : VlmApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val TAG = "GeminiClient"
    }

    override suspend fun predictWithImages(
        systemPrompt: String?,
        textPrompt: String,
        images: List<Bitmap>
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES for model: ${resolveModel()}")
                val result = doRequest(systemPrompt, textPrompt, images)
                return@withContext Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Attempt ${attempt + 1} failed: ${e.message}", e)
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        Result.failure(lastError ?: Exception("Gemini request failed"))
    }

    private fun resolveModel(): String {
        val configured = config.model.trim()
        return when {
            configured.isBlank() -> DEFAULT_MODEL
            configured.equals("gemini flash", ignoreCase = true) -> "gemini-flash-latest"
            configured.equals("gemini pro", ignoreCase = true) -> "gemini-3.1-pro-preview"
            else -> configured
        }
    }

    private fun resolveApiKey(): String {
        return if (config.apiKey.isNotBlank()) {
            config.apiKey.trim()
        } else {
            BuildConfig.GEMINI_API_KEY.trim()
        }
    }

    private fun doRequest(
        systemPrompt: String?,
        textPrompt: String,
        images: List<Bitmap>
    ): String {
        val effectiveApiKey = resolveApiKey()
        if (effectiveApiKey.isBlank()) {
            throw IllegalStateException("Gemini API key is not configured. Please add it in Settings.")
        }

        val targetModel = resolveModel()
        val url = "$BASE_URL/$targetModel:generateContent?key=$effectiveApiKey"

        val requestBody = buildJsonObject {
            // Optional system instructions
            if (!systemPrompt.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemPrompt)
                        }
                    }
                }
            }

            // User content with multimodal images and instruction text
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        for (image in images) {
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", ImageEncoder.encode(image))
                                }
                            }
                        }
                        addJsonObject {
                            put("text", textPrompt)
                        }
                    }
                }
            }

            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("maxOutputTokens", 4096)
            }
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val bodyString = response.body?.string() ?: throw Exception("Empty response from Gemini API")

        if (!response.isSuccessful) {
            val errorMsg = try {
                val errorJson = json.parseToJsonElement(bodyString).jsonObject
                errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: bodyString
            } catch (_: Exception) {
                bodyString
            }
            throw Exception("Gemini API error (${response.code}): $errorMsg")
        }

        val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
        val candidates = jsonResponse["candidates"]?.jsonArray
            ?: throw Exception("No candidates returned from Gemini: $bodyString")

        if (candidates.isEmpty()) {
            throw Exception("Empty candidates list from Gemini: $bodyString")
        }

        val firstCandidate = candidates[0].jsonObject
        val content = firstCandidate["content"]?.jsonObject
            ?: throw Exception("No content in candidate: $firstCandidate")

        val parts = content["parts"]?.jsonArray
            ?: throw Exception("No parts in candidate content: $content")

        val textParts = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
        val outputText = textParts.joinToString("\n")

        if (outputText.isBlank()) {
            throw Exception("Gemini returned empty text response")
        }

        return outputText
    }
}
