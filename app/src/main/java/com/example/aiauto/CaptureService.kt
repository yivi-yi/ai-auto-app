package com.example.aiauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

class CaptureService : Service() {

    companion object {
        var instance: CaptureService? = null
        var active: Boolean = false
        var lastShot: File? = null
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundWithNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data = intent.getParcelableExtra<Intent>("data")
        if (resultCode != -1 && data != null) {
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            proj.registerCallback(object : MediaProjection.Callback() {}, main)
        }
        projection = proj
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "aiauto_cap", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, main
        )
        active = true
    }

    fun saveScreenshot(): File? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
            if (cropped != bmp) bmp.recycle()
            val maxW = 720
            val scale = if (cropped.width > maxW) maxW.toFloat() / cropped.width else 1f
            val w = (cropped.width * scale).toInt()
            val h = (cropped.height * scale).toInt()
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(cropped, w, h, true) else cropped
            val f = File(filesDir, "shot_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 60, it) }
            if (scaled != cropped) scaled.recycle()
            cropped.recycle()
            lastShot = f
            return f
        } finally {
            image.close()
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("cap", "Capture", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val n = Notification.Builder(this, "cap")
            .setContentTitle("AI Auto")
            .setContentText("截图服务运行中")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        if (instance == this) {
            instance = null
        }
        active = false
        super.onDestroy()
    }
}
