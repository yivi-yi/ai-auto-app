package com.example.aiauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** 常驻前台服务：让本地控制服务一直在线；顺便盯着无障碍有没有被系统关掉 */
class ServerService : Service() {

    private lateinit var server: LocalServer
    private val main = Handler(Looper.getMainLooper())
    private var lastText = ""
    private var lastTitle = ""

    private val tick = object : Runnable {
        override fun run() {
            updateNotification()
            main.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        server = LocalServer(8080)
        server.start()
        makeChannel()
        startForegroundWithNotification()
        main.postDelayed(tick, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(tick)
        server.stop()
        super.onDestroy()
    }

    private fun makeChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("srv", "Control", NotificationManager.IMPORTANCE_LOW))
    }

    /** 无障碍被系统关掉是很常见的事（切后台、省电策略、受限设置）——
     *  常驻通知直接写明，点一下就能回到 APP 里重新开 */
    private fun updateNotification() {
        val ok = AutoAccessibilityService.enabled
        val title = if (ok) "AI Auto" else "AI Auto · 无障碍没开"
        val text = if (ok) "控制服务运行中 :8080" else "点这里重新开启无障碍（不然工具都点不动）"
        if (title == lastTitle && text == lastText) return
        lastTitle = title
        lastText = text
        try {
            getSystemService(NotificationManager::class.java).notify(2, build(title, text))
        } catch (e: Exception) {
        }
    }

    private fun build(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "srv")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification() {
        val n = build(if (AutoAccessibilityService.enabled) "AI Auto" else "AI Auto · 无障碍没开", "控制服务运行中 :8080")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, n)
        }
    }
}
