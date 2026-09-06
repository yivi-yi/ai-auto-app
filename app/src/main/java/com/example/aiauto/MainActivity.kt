package com.example.aiauto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.widget.Button

class MainActivity : Activity() {

    private lateinit var localServer: LocalServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnAccess = findViewById<Button>(R.id.btnAccess)
        val btnCapture = findViewById<Button>(R.id.btnCapture)
        val web = findViewById<WebView>(R.id.web)

        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.loadUrl("file:///android_asset/index.html")

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnCapture.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        }

        localServer = LocalServer(8080)
        localServer.start()
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

    override fun onDestroy() {
        super.onDestroy()
        localServer.stop()
    }
}
