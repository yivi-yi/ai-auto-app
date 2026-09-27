package com.example.aiauto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.widget.Button

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("server", Context.MODE_PRIVATE)

        // Android 13+ 不请求通知权限的话，前台服务那条「无障碍没开」的提醒根本不显示
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }

        val web = findViewById<WebView>(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        // file:// 页面要能 fetch 127.0.0.1:8080，不开这个页面上的按钮全是死的
        web.settings.allowUniversalAccessFromFileURLs = true
        web.loadUrl("file:///android_asset/index.html")

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        }
        findViewById<Button>(R.id.btnServer).setOnClickListener {
            if (prefs.getBoolean("on", false)) {
                stopService(Intent(this, ServerService::class.java))
                prefs.edit().putBoolean("on", false).apply()
            } else {
                startForegroundService(Intent(this, ServerService::class.java))
                prefs.edit().putBoolean("on", true).apply()
            }
            refresh()
        }

        if (prefs.getBoolean("on", false)) {
            startForegroundService(Intent(this, ServerService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 按钮顺便当状态灯：无障碍/截图到底开没开，一眼能看到 */
    private fun refresh() {
        findViewById<Button>(R.id.btnAccess).text =
            if (AutoAccessibilityService.instance != null) "无障碍已开" else "开无障碍"
        findViewById<Button>(R.id.btnCapture).text =
            if (CaptureService.active) "截图已授权" else "开截图（可选）"
        findViewById<Button>(R.id.btnServer).text =
            if (prefs.getBoolean("on", false)) "停止服务" else "启动服务"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
            i.putExtra("resultCode", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
        }
    }
}
