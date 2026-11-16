package com.novaclean.app

import android.app.Application
import com.novaclean.app.core.CrashLog

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
