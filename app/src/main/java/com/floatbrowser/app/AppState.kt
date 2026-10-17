package com.floatbrowser.app

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime

object AppState { val pool = ProxyPool() }

object GeckoHolder {
    @Volatile private var rt: GeckoRuntime? = null
    fun get(c: Context): GeckoRuntime = rt ?: synchronized(this) {
        rt ?: GeckoRuntime.create(c.applicationContext).also { rt = it }
    }
}
