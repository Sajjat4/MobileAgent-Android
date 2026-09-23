package com.mobileagent.app.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    var screenWidth = 0
        private set
    var screenHeight = 0
        private set
    var screenDensity = 0
        private set

    private val frameListeners = CopyOnWriteArrayList<(Bitmap) -> Unit>()

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }

    fun addFrameListener(listener: (Bitmap) -> Unit) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: (Bitmap) -> Unit) {
        frameListeners.remove(listener)
    }

    fun initialize(projection: MediaProjection) {
        mediaProjection = projection

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Android 14+ requires registering a callback before createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    release()
                }
            }, Handler(Looper.getMainLooper()))
        }

        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 3
        )

        virtualDisplay = projection.createVirtualDisplay(
            "MobileAgentCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        Log.d(TAG, "ScreenCaptureManager initialized ($screenWidth x $screenHeight, density=$screenDensity)")
    }

    /**
     * Captures a single raw bitmap frame from the MediaProjection virtual display.
     */
    suspend fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        delay(100)

        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }

            // Notify registered live listeners (e.g. WebSocket streamer or preview)
            if (frameListeners.isNotEmpty()) {
                for (listener in frameListeners) {
                    try {
                        listener(finalBitmap)
                    } catch (e: Exception) {
                        Log.w(TAG, "Listener error: ${e.message}")
                    }
                }
            }

            finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed capturing frame: ${e.message}", e)
            null
        } finally {
            image.close()
        }
    }

    /**
     * Captures and downsamples a frame specifically optimized for Vision-Language Models (VLM)
     * such as Gemini, Claude, or local VLMs to minimize latency, memory, and upload size.
     */
    suspend fun captureVlmOptimized(maxDimension: Int = 1080): Bitmap? {
        val raw = capture() ?: return null
        val maxSide = maxOf(raw.width, raw.height)
        if (maxSide <= maxDimension) return raw

        val scale = maxDimension.toFloat() / maxSide
        val newWidth = (raw.width * scale).toInt()
        val newHeight = (raw.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(raw, newWidth, newHeight, true)
        raw.recycle()
        return scaled
    }

    /**
     * Continuous frame flow for live vision-language monitoring and streaming.
     */
    fun frameFlow(intervalMs: Long = 500): Flow<Bitmap> = flow {
        while (imageReader != null) {
            val frame = capture()
            if (frame != null) {
                emit(frame)
            }
            delay(intervalMs)
        }
    }

    fun isReady(): Boolean = imageReader != null && virtualDisplay != null

    fun release() {
        try {
            frameListeners.clear()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing capture resources: ${e.message}")
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
        }
    }
}
