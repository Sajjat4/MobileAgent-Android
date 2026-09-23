package com.mobileagent.app.service

import android.graphics.Bitmap
import android.util.Log
import com.mobileagent.app.api.ImageEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Bidirectional WebSocket bridge for remote agent orchestration.
 * Streams screen frames & UI hierarchy, and executes remote commands via AgentAccessibilityService.
 */
object WebSocketAgentBridge {

    private const val TAG = "WebSocketAgentBridge"

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage.asStateFlow()

    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(serverUrl: String, authToken: String = "") {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        var url = serverUrl.trim()
        if (url.isBlank()) {
            url = "ws://10.0.2.2:8765/ws"
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = if (url.startsWith("http://")) url.replaceFirst("http://", "ws://")
            else if (url.startsWith("https://")) url.replaceFirst("https://", "wss://")
            else "ws://$url"
        }

        val requestBuilder = Request.Builder().url(url)
        if (authToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $authToken")
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to WebSocket: $url")
                _connectionState.value = ConnectionState.CONNECTED
                _lastMessage.value = "Connected to $url"
                sendDeviceInfo(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Incoming WS command: $text")
                _lastMessage.value = "Cmd: ${text.take(80)}"
                scope.launch {
                    handleIncomingCommand(text, ws)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR
                _lastMessage.value = "Error: ${t.message}"
                webSocket = null
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                _lastMessage.value = "Disconnected"
                webSocket = null
            }
        }

        webSocket = client.newWebSocket(requestBuilder.build(), listener)
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (_: Exception) {}
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _lastMessage.value = "Disconnected"
    }

    /**
     * Sends a captured screen frame over WebSocket.
     */
    fun sendFrame(bitmap: Bitmap, quality: Int = 75) {
        val ws = webSocket ?: return
        if (_connectionState.value != ConnectionState.CONNECTED) return

        scope.launch {
            try {
                val base64 = ImageEncoder.encode(bitmap)
                val payload = buildJsonObject {
                    put("type", "screen_frame")
                    put("timestamp", System.currentTimeMillis())
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    put("image_data", base64)
                }
                ws.send(payload.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending frame: ${e.message}")
            }
        }
    }

    /**
     * Sends accessibility hierarchy text over WebSocket.
     */
    fun sendScreenContent(content: ScreenContentInfo) {
        val ws = webSocket ?: return
        if (_connectionState.value != ConnectionState.CONNECTED) return

        scope.launch {
            try {
                val payload = buildJsonObject {
                    put("type", "screen_content")
                    put("package_name", content.packageName)
                    put("window_title", content.windowTitle)
                    put("summary", content.summary)
                    putJsonArray("texts") {
                        for (t in content.allTexts) add(t)
                    }
                }
                ws.send(payload.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending screen content: ${e.message}")
            }
        }
    }

    private fun sendDeviceInfo(ws: WebSocket) {
        try {
            val payload = buildJsonObject {
                put("type", "device_info")
                put("brand", android.os.Build.BRAND)
                put("model", android.os.Build.MODEL)
                put("os_version", android.os.Build.VERSION.RELEASE)
                put("accessibility_ready", AgentAccessibilityService.isRunning())
            }
            ws.send(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending device info: ${e.message}")
        }
    }

    private suspend fun handleIncomingCommand(text: String, ws: WebSocket) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            sendResponse(ws, "error", "Accessibility service not running")
            return
        }

        try {
            val root = json.parseToJsonElement(text).jsonObject
            val action = root["action"]?.jsonPrimitive?.content?.lowercase() ?: "unknown"
            val cmdId = root["id"]?.jsonPrimitive?.content ?: ""

            when (action) {
                "tap", "click" -> {
                    val x = root["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y = root["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val ok = service.performTap(x, y)
                    sendResponse(ws, "tap_result", if (ok) "ok" else "failed", cmdId)
                }
                "double_tap" -> {
                    val x = root["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y = root["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val ok = service.performDoubleTap(x, y)
                    sendResponse(ws, "double_tap_result", if (ok) "ok" else "failed", cmdId)
                }
                "long_press" -> {
                    val x = root["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y = root["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val duration = root["duration"]?.jsonPrimitive?.longOrNull ?: 1000L
                    val ok = service.performLongPress(x, y, duration)
                    sendResponse(ws, "long_press_result", if (ok) "ok" else "failed", cmdId)
                }
                "swipe" -> {
                    val x1 = root["x1"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y1 = root["y1"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val x2 = root["x2"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y2 = root["y2"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val duration = root["duration"]?.jsonPrimitive?.longOrNull ?: 500L
                    val ok = service.performSwipe(x1, y1, x2, y2, duration)
                    sendResponse(ws, "swipe_result", if (ok) "ok" else "failed", cmdId)
                }
                "scroll" -> {
                    val direction = root["direction"]?.jsonPrimitive?.content ?: "down"
                    val ok = service.performScroll(direction)
                    sendResponse(ws, "scroll_result", if (ok) "ok" else "failed", cmdId)
                }
                "type" -> {
                    val textToType = root["text"]?.jsonPrimitive?.content ?: ""
                    val ok = service.performTypeText(textToType)
                    sendResponse(ws, "type_result", if (ok) "ok" else "failed", cmdId)
                }
                "click_text" -> {
                    val targetText = root["text"]?.jsonPrimitive?.content ?: ""
                    val ok = service.clickElementByText(targetText)
                    sendResponse(ws, "click_text_result", if (ok) "ok" else "failed", cmdId)
                }
                "back" -> {
                    val ok = service.performBack()
                    sendResponse(ws, "back_result", if (ok) "ok" else "failed", cmdId)
                }
                "home" -> {
                    val ok = service.performHome()
                    sendResponse(ws, "home_result", if (ok) "ok" else "failed", cmdId)
                }
                "recents" -> {
                    val ok = service.performRecents()
                    sendResponse(ws, "recents_result", if (ok) "ok" else "failed", cmdId)
                }
                "enter" -> {
                    val ok = service.performEnter()
                    sendResponse(ws, "enter_result", if (ok) "ok" else "failed", cmdId)
                }
                "read_screen" -> {
                    val screenInfo = service.readScreenContent()
                    sendScreenContent(screenInfo)
                }
                else -> {
                    sendResponse(ws, "unknown_action", "Action $action not recognized", cmdId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WS command: ${e.message}", e)
            sendResponse(ws, "error", e.message ?: "Execution failed")
        }
    }

    private fun sendResponse(ws: WebSocket, type: String, status: String, cmdId: String = "") {
        try {
            val resp = buildJsonObject {
                put("type", type)
                put("status", status)
                if (cmdId.isNotBlank()) put("id", cmdId)
                put("timestamp", System.currentTimeMillis())
            }
            ws.send(resp.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending WS response: ${e.message}")
        }
    }
}
