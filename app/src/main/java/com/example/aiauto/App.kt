package com.example.aiauto

import android.app.Application
import android.content.Context

/** 只做一件事：留一个全局 Context（查已装应用要用 PackageManager） */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }

    companion object {
        @Volatile
        var ctx: Context? = null
    }
}
