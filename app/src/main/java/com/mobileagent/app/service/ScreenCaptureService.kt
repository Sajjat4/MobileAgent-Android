package com.mobileagent.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobileagent.app.MainActivity
import com.mobileagent.app.MobileAgentApp
import com.mobileagent.app.R
import com.mobileagent.app.controller.ScreenCaptureManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated MediaProjection-based foreground service for continuous screen frame capture
 * and preprocessing tailored for Vision-Language Model (VLM) input and WebSocket streaming.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.mobileagent.action.CAPTURE_START"
        const val ACTION_STOP = "com.mobileagent.action.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STREAM_TO_WS = "stream_to_ws"
        const val EXTRA_FPS = "fps"
        private const val NOTIFICATION_ID = 2002
        private const val TAG = "ScreenCaptureService"

        private val _isCapturing = MutableStateFlow(false)
        val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

        private val _latestFrame = MutableStateFlow<Bitmap?>(null)
        val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

        var captureManagerInstance: ScreenCaptureManager? = null
            private set

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            streamToWs: Boolean = false,
            fps: Int = 2
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_STREAM_TO_WS, streamToWs)
                putExtra(EXTRA_FPS, fps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureJob: Job? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val streamToWs = intent.getBooleanExtra(EXTRA_STREAM_TO_WS, false)
                val fps = intent.getIntExtra(EXTRA_FPS, 2).coerceIn(1, 10)

                if (resultData == null) {
                    Log.e(TAG, "Missing MediaProjection data")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundNotification()
                initMediaProjection(resultCode, resultData, streamToWs, fps)
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, MobileAgentApp.CHANNEL_ID)
            .setContentTitle("Screen Frame Capture Active")
            .setContentText("Capturing and preprocessing frames for Vision-Language Model")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initMediaProjection(
        resultCode: Int,
        resultData: Intent,
        streamToWs: Boolean,
        fps: Int
    ) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

            val manager = ScreenCaptureManager(this)
            manager.initialize(mediaProjection!!)
            screenCaptureManager = manager
            captureManagerInstance = manager

            _isCapturing.value = true

            val intervalMs = (1000L / fps).coerceAtLeast(100L)
            startCaptureLoop(intervalMs, streamToWs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing MediaProjection capture: ${e.message}", e)
            stopSelf()
        }
    }

    private fun startCaptureLoop(intervalMs: Long, streamToWs: Boolean) {
        captureJob?.cancel()
        captureJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive && _isCapturing.value) {
                val manager = screenCaptureManager ?: break
                try {
                    // Optimized VLM frame (max 1080p for fast tokenization & transmission)
                    val frame = manager.captureVlmOptimized(maxDimension = 1080)
                    if (frame != null) {
                        _latestFrame.value = frame

                        if (streamToWs) {
                            WebSocketAgentBridge.sendFrame(frame)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame capture cycle error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopCapture() {
        _isCapturing.value = false
        captureJob?.cancel()
        captureJob = null
        screenCaptureManager?.release()
        screenCaptureManager = null
        captureManagerInstance = null
        mediaProjection?.stop()
        mediaProjection = null
        _latestFrame.value = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
