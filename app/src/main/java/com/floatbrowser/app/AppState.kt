package com.floatbrowser.app

import android.app.Application
import android.content.Context
import org.mozilla.geckoview.GeckoRuntime

object Prefs {
    private val sp get() = AppState.app.getSharedPreferences("fb", Context.MODE_PRIVATE)
    var manualText: String
        get() = sp.getString("manual", "") ?: ""
        set(v) { sp.edit().putString("manual", v).apply() }
    var sourcesText: String
        get() = sp.getString("sources", ProxyPool.DEFAULT_SOURCES.joinToString("\n")) ?: ""
        set(v) { sp.edit().putString("sources", v).apply() }
}

object AppState {
    lateinit var app: Application
    val pool: ProxyPool by lazy {
        ProxyPool(sources = {
            Prefs.sourcesText.lines().map { it.trim() }.filter { it.startsWith("http") }
        }).also { reloadManual(it) }
    }
    fun reloadManual(p: ProxyPool = pool) {
        p.manual.clear()
        p.manual.addAll(Prefs.manualText.lines().mapNotNull { p.parse(it) })
    }
}

object GeckoHolder {
    @Volatile private var rt: GeckoRuntime? = null
    fun get(c: Context): GeckoRuntime = rt ?: synchronized(this) {
        rt ?: GeckoRuntime.create(c.applicationContext).also { rt = it }
    }
}
