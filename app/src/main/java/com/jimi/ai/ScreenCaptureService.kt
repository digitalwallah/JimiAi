package com.jimi.ai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * PURANE Android (API 30 se neeche, matlab Android 10 aur usse purane) devices ke liye
 * screenshot capture ka fallback. Android 11+ pe JimiAccessibilityService.takeScreenshot()
 * silently kaam karta hai isliye ye service un devices pe kabhi trigger hi nahi hoti.
 *
 * IMPORTANT: is service ko sirf tab use kiya jaata hai jab device API level 30 se kam ho.
 * Un purane OS versions mein Android 14 ka "har-baar-consent-maango" wala naya rule exist
 * hi nahi karta, isliye user se sirf EK BAAR (permission grant hote waqt) poocha jaata hai,
 * uske baad ye service background mein zinda rehkar bina dobara poochhe capture karti rehti hai.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var handlerThread: HandlerThread? = null
    private var serviceHandler: Handler? = null

    companion object {
        const val CHANNEL_ID = "jimi_screencapture_channel"
        const val NOTIF_ID = 91
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile private var instance: ScreenCaptureService? = null

        /** Service ko start karta hai aur MediaProjection permission result usko de deta hai. */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Ek single frame capture karta hai (agar service chal rahi hai aur permission mili hui hai).
         * Callback main thread pe deliver hota hai. */
        fun captureFrame(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null) {
                callback(null)
                return
            }
            service.doCapture(callback)
        }

        fun isReady(): Boolean = instance?.mediaProjection != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        handlerThread = HandlerThread("JimiScreenCapture").also { it.start() }
        serviceHandler = Handler(handlerThread!!.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (mediaProjection == null && resultData != null && resultCode == Activity.RESULT_OK) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                }
            }, serviceHandler)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        if (instance == this) instance = null
    }

    private fun doCapture(callback: (Bitmap?) -> Unit) {
        val projection = mediaProjection
        val handler = serviceHandler
        if (projection == null || handler == null) {
            callback(null)
            return
        }

        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        val mainHandler = Handler(mainLooper)

        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            var bitmap: Bitmap? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                }
            } catch (e: Exception) {
                bitmap = null
            } finally {
                image?.close()
                virtualDisplay?.release()
                imageReader.close()
                val finalBitmap = bitmap
                mainHandler.post { callback(finalBitmap) }
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "JimiScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jimi Screen Reading", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jimi")
            .setContentText("Screen-reading ready hai")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
