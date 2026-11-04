package com.floatbrowser.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mozilla.geckoview.GeckoRuntime

object Prefs {
    private val sp get() = AppState.app.getSharedPreferences("fb", Context.MODE_PRIVATE)
    var manualText: String
        get() = sp.getString("manual", "") ?: ""
        set(v) { sp.edit().putString("manual", v).apply() }
    /** 0 = normal, 1 = econômico, 2 = automático (econômico em rede limitada/4G, normal no Wi-Fi) */
    var dataMode: Int
        get() = sp.getInt("dataMode", 0)
        set(v) { sp.edit().putInt("dataMode", v).apply() }

    fun isEco(): Boolean = when (dataMode) {
        1 -> true
        2 -> runCatching {
            val cm = AppState.app.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)
        else -> false
    }

    var savedWindows: String
        get() = sp.getString("saved", "[]") ?: "[]"
        set(v) { sp.edit().putString("saved", v).apply() }
    /** Última página de cada vaga de janela (1..4): "{"1":"https://...","2":"..."}" */
    var slotUrlsJson: String
        get() = sp.getString("slotUrls", "{}") ?: "{}"
        set(v) { sp.edit().putString("slotUrls", v).apply() }
    fun slotUrl(n: Int): String? =
        runCatching { org.json.JSONObject(slotUrlsJson).optString(n.toString(), "") }.getOrNull()?.ifEmpty { null }
    fun setSlotUrl(n: Int, url: String) {
        if (n <= 0 || url.startsWith("http://floatbrowser.invalid")) return
        runCatching { slotUrlsJson = org.json.JSONObject(slotUrlsJson).put(n.toString(), url).toString() }
    }
    /** Máximo de janelas abertas ao mesmo tempo (1..4). Menos janelas = menos memória = menos travamentos. */
    var maxWindows: Int
        get() = sp.getInt("maxWin", 2)
        set(v) { sp.edit().putInt("maxWin", v).apply() }
    var bypassVpn: Boolean
        get() = sp.getBoolean("bypassVpn", false)
        set(v) { sp.edit().putBoolean("bypassVpn", v).apply() }
    var profilesJson: String
        get() = sp.getString("profiles", "[]") ?: "[]"
        set(v) { sp.edit().putString("profiles", v).apply() }
    var schedulesJson: String
        get() = sp.getString("schedules", "[]") ?: "[]"
        set(v) { sp.edit().putString("schedules", v).apply() }
    var tools: Boolean
        get() = sp.getBoolean("tools", true)
        set(v) { sp.edit().putBoolean("tools", v).apply() }
    var keepAwake: Boolean
        get() = sp.getBoolean("awake", true)
        set(v) { sp.edit().putBoolean("awake", v).apply() }
    var sourcesText: String
        get() = sp.getString("sources", ProxyPool.DEFAULT_SOURCES.joinToString("\n")) ?: ""
        set(v) { sp.edit().putString("sources", v).apply() }
}

object AppState {
    lateinit var app: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.handler)
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
