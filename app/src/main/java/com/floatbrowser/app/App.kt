package com.floatbrowser.app

import android.app.Application

class App : Application() {
    override fun onCreate() { super.onCreate(); AppState.app = this; CrashLog.install(); NetBind.apply(this) }
}
