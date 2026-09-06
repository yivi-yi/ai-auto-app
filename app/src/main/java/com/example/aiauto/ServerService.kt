package com.example.aiauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 常驻前台服务：让本地控制服务一直在线 */
class ServerService : Service() {

    private lateinit var server: LocalServer

    override fun onCreate() {
        super.onCreate()
        server = LocalServer(8080)
        server.start()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("srv", "Control", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val n = Notification.Builder(this, "srv")
            .setContentTitle("AI Auto")
            .setContentText("控制服务运行中")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, n)
        }
    }
}
