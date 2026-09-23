package com.mobileagent.app.api

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that enables offloading vision-language processing and agent loop
 * queries over a persistent, low-latency WebSocket connection.
 */
class WebSocketVlmClient(private val config: ApiConfig) : VlmApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive for WebSocket
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private var webSocket: WebSocket? = null
    private val mutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    companion object {
        private const val TAG = "WebSocketVlmClient"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }

    private fun ensureConnected() {
        if (webSocket != null) return

        var url = config.endpoint.trim()
        if (url.isBlank()) {
            url = "ws://10.0.2.2:8765/ws"
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = if (url.startsWith("http://")) url.replaceFirst("http://", "ws://")
            else if (url.startsWith("https://")) url.replaceFirst("https://", "wss://")
            else "ws://$url"
        }

        val requestBuilder = Request.Builder().url(url)
        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $url")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "WS message received: ${text.take(150)}")
                try {
                    val root = json.parseToJsonElement(text).jsonObject
                    val reqId = root["id"]?.jsonPrimitive?.content
                        ?: root["request_id"]?.jsonPrimitive?.content
                    val responseText = root["response"]?.jsonPrimitive?.content
                        ?: root["text"]?.jsonPrimitive?.content
                        ?: root["content"]?.jsonPrimitive?.content
                        ?: root["answer"]?.jsonPrimitive?.content

                    if (reqId != null && pendingRequests.containsKey(reqId)) {
                        pendingRequests.remove(reqId)?.complete(responseText ?: text)
                    } else if (pendingRequests.size == 1) {
                        // Single inflight fallback
                        val onlyKey = pendingRequests.keys().nextElement()
                        pendingRequests.remove(onlyKey)?.complete(responseText ?: text)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message: ${e.message}", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                webSocket = null
                val iterator = pendingRequests.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    entry.value.completeExceptionally(t)
                    iterator.remove()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                webSocket = null
            }
        }

        webSocket = client.newWebSocket(requestBuilder.build(), listener)
    }

    override suspend fun predictWithImages(
        systemPrompt: String?,
        textPrompt: String,
        images: List<Bitmap>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                ensureConnected()
            }

            val ws = webSocket ?: return@withContext Result.failure(Exception("WebSocket not connected"))
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            pendingRequests[requestId] = deferred

            val encodedImages = images.map { ImageEncoder.encode(it) }

            val payload = buildJsonObject {
                put("id", requestId)
                put("type", "vlm_predict")
                put("model", config.model)
                if (systemPrompt != null) {
                    put("system_prompt", systemPrompt)
                }
                put("prompt", textPrompt)
                putJsonArray("images") {
                    for (img in encodedImages) {
                        add(img)
                    }
                }
            }

            val sent = ws.send(payload.toString())
            if (!sent) {
                pendingRequests.remove(requestId)
                return@withContext Result.failure(Exception("Failed to send message over WebSocket"))
            }

            val response = withTimeout(DEFAULT_TIMEOUT_MS) {
                deferred.await()
            }
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "predictWithImages error: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun close() {
        try {
            webSocket?.close(1000, "Client closed")
            webSocket = null
            pendingRequests.clear()
        } catch (_: Exception) {}
    }
}
