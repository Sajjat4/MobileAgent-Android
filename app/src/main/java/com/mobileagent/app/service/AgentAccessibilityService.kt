package com.mobileagent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.mobileagent.app.MainActivity
import com.mobileagent.app.MobileAgentApp
import com.mobileagent.app.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ScreenElementInfo(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isFocused: Boolean
)

data class ScreenContentInfo(
    val packageName: String,
    val windowTitle: String,
    val allTexts: List<String>,
    val elements: List<ScreenElementInfo>,
    val summary: String
)

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        private const val DISCONNECT_NOTIFICATION_ID = 1001
        private const val TAG = "AgentA11yService"

        private val _eventsFlow = MutableSharedFlow<AccessibilityEvent>(extraBufferCapacity = 50)
        val eventsFlow: SharedFlow<AccessibilityEvent> = _eventsFlow.asSharedFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AgentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            _eventsFlow.tryEmit(AccessibilityEvent.obtain(event))
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
    }

    override fun onDestroy() {
        val wasRunning = instance != null
        instance = null
        if (wasRunning) {
            postDisconnectNotification()
        }
        super.onDestroy()
    }

    private fun postDisconnectNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "permissions")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MobileAgentApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_accessibility_lost_title))
            .setContentText(getString(R.string.notif_accessibility_lost_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DISCONNECT_NOTIFICATION_ID, notification)
    }

    // ==========================================
    // Screen Content Reading Capabilities
    // ==========================================

    /**
     * Reads the entire visible screen content, structured for vision-language models
     * and agent decision loops.
     */
    fun readScreenContent(): ScreenContentInfo {
        val root = rootInActiveWindow ?: return ScreenContentInfo(
            packageName = "",
            windowTitle = "",
            allTexts = emptyList(),
            elements = emptyList(),
            summary = "No active window content accessible"
        )

        val pkgName = root.packageName?.toString() ?: ""
        val windowTitle = root.window?.title?.toString() ?: ""
        val texts = mutableListOf<String>()
        val elements = mutableListOf<ScreenElementInfo>()
        var counter = 0

        fun parseNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val nodeText = node.text?.toString()?.trim() ?: ""
                val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
                val viewId = node.viewIdResourceName?.toString() ?: ""
                val className = node.className?.toString() ?: ""
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (nodeText.isNotEmpty() && !texts.contains(nodeText)) {
                    texts.add(nodeText)
                }

                val isInteractive = node.isClickable || node.isScrollable || node.isEditable ||
                        nodeText.isNotEmpty() || nodeDesc.isNotEmpty()

                if (isInteractive && node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
                    elements.add(
                        ScreenElementInfo(
                            index = counter++,
                            text = nodeText,
                            contentDescription = nodeDesc,
                            className = className,
                            viewId = viewId,
                            bounds = bounds,
                            isClickable = node.isClickable,
                            isEditable = node.isEditable,
                            isScrollable = node.isScrollable,
                            isFocused = node.isFocused
                        )
                    )
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    parseNode(child)
                    child?.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing accessibility node: ${e.message}")
            }
        }

        parseNode(root)
        root.recycle()

        val summary = buildString {
            appendLine("App: $pkgName ${if (windowTitle.isNotEmpty()) "($windowTitle)" else ""}")
            appendLine("Elements (${elements.size}):")
            for (el in elements) {
                val label = when {
                    el.text.isNotEmpty() -> "\"${el.text}\""
                    el.contentDescription.isNotEmpty() -> "desc:\"${el.contentDescription}\""
                    else -> el.viewId.substringAfterLast("/")
                }
                val type = el.className.substringAfterLast(".")
                val center = "${el.bounds.centerX()},${el.bounds.centerY()}"
                val flags = buildList {
                    if (el.isClickable) add("click")
                    if (el.isEditable) add("edit")
                    if (el.isScrollable) add("scroll")
                }.joinToString(",")
                appendLine("[${el.index}] $type $label at ($center) [$flags]")
            }
        }

        return ScreenContentInfo(
            packageName = pkgName,
            windowTitle = windowTitle,
            allTexts = texts,
            elements = elements,
            summary = summary
        )
    }

    /**
     * Finds nodes matching specific text.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text)
    }

    /**
     * Finds nodes by their View ID resource name.
     */
    fun findNodesByViewId(viewId: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId(viewId)
    }

    /**
     * Clicks on a node identified by its visible text or content description.
     */
    fun clickElementByText(text: String): Boolean {
        val nodes = findNodesByText(text)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    val performed = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (performed) return true
                }
                current = current.parent
            }
        }
        return false
    }

    // ==========================================
    // Touch Interaction Capabilities
    // ==========================================

    suspend fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun performDoubleTap(x: Float, y: Float): Boolean {
        val tap1 = performTap(x, y)
        kotlinx.coroutines.delay(100)
        val tap2 = performTap(x, y)
        return tap1 && tap2
    }

    suspend fun performLongPress(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun performSwipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 500
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Performs a directional scroll (up, down, left, right).
     */
    suspend fun performScroll(direction: String): Boolean {
        val root = rootInActiveWindow
        val scrollAction = when (direction.lowercase()) {
            "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        // Try standard accessibility scroll on active scrollable container
        var targetNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null || !targetNode.isScrollable) {
            targetNode = findScrollableNode(root)
        }

        if (targetNode != null) {
            val handled = targetNode.performAction(scrollAction)
            if (handled) return true
        }

        // Fallback to gesture drag
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val deltaY = metrics.heightPixels * 0.35f

        return when (direction.lowercase()) {
            "down", "forward" -> performSwipe(centerX, centerY + deltaY, centerX, centerY - deltaY, 400)
            "up", "backward" -> performSwipe(centerX, centerY - deltaY, centerX, centerY + deltaY, 400)
            "left" -> performSwipe(centerX + deltaY, centerY, centerX - deltaY, centerY, 400)
            "right" -> performSwipe(centerX - deltaY, centerY, centerX + deltaY, centerY, 400)
            else -> performSwipe(centerX, centerY + deltaY, centerX, centerY - deltaY, 400)
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val scrollable = findScrollableNode(node.getChild(i))
            if (scrollable != null) return scrollable
        }
        return null
    }

    fun performTypeText(text: String): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.let { findEditableNode(it) }

        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (success) return true
        }

        // Fallback: paste via clipboard
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AgentType", text))
            focusedNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed typing text: ${e.message}")
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun performEnter(): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return focusedNode.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        }
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            dispatchGesture(gesture, callback, null)
        }
    }
}
