package com.floatbrowser.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class FloatService : Service() {
    companion object {
        const val ACTION_NEW = "new"
        const val ACTION_STOP = "stop"
        const val ACTION_MIN = "min"
        const val ACTION_SHOW = "show"
        const val ACTION_REAL = "real"
        private const val CH = "float"
        private const val CH_ALERT = "alert"
        private const val MAX = 4
        @Volatile var instance: FloatService? = null
    }

    private val windows = mutableListOf<FloatWindow>()
    private var counter = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.handler)
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netDown = false
    override fun onBind(i: Intent?): IBinder? = null

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { handler.post { netDown = true } }
        override fun onAvailable(network: Network) {
            handler.post {
                if (netDown) {
                    netDown = false
                    // rede voltou: espera estabilizar, recarrega as janelas e repõe proxys se faltar
                    handler.postDelayed({
                        windows.toList().forEach { it.onNetworkBack() }
                        if (AppState.pool.needsRefresh()) refreshPool()
                    }, 1500)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProxyBridge.init(this)
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
        }
        scope.launch {   // lista: normal a cada 10 min; econômico a cada 30 min e só se faltar proxy
            while (true) {
                delay(if (Prefs.isEco()) 30 * 60_000L else 10 * 60_000L)
                if (windows.isNotEmpty() && (!Prefs.isEco() || AppState.pool.needsRefresh())) refreshPool()
            }
        }
        scope.launch {   // saúde dos proxys: 45 s (normal) ou 3 min (econômico), também minimizadas
            while (true) {
                delay(if (Prefs.isEco()) 180_000L else 45_000L)
                windows.toList().forEach { it.checkHealth() }
                saveState()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (runCatching { notifyNow() }.isFailure) { stopSelf(); return START_NOT_STICKY }
        if (intent == null) restoreWindows() // o sistema reiniciou o serviço depois de matar o app
        when (intent?.action) {
            ACTION_NEW -> newWindow()
            ACTION_MIN -> { windows.forEach { it.setMinimized(true) }; saveState() }
            ACTION_SHOW -> { windows.forEach { it.setMinimized(false) }; saveState() }
            ACTION_REAL -> {
                val w = intent.getStringExtra("w")
                windows.firstOrNull { it.id == w }?.useRealIp()
            }
            ACTION_STOP -> {
                closeAll(); Prefs.savedWindows = "[]"
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return START_NOT_STICKY
            }
        }
        notifyNow()
        return START_STICKY
    }

    private fun refreshPool() = scope.launch {
        AppState.pool.refresh {}
        windows.toList().forEach { it.onPoolRefreshed() }
    }

    private fun newWindow(url: String? = null, min: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permita a sobreposição primeiro", Toast.LENGTH_LONG).show(); return
        }
        if (windows.size >= MAX) {
            Toast.makeText(this, "Máximo de $MAX janelas", Toast.LENGTH_SHORT).show(); return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w = FloatWindow(this, wm, "w${++counter}", windows.size,
            onClosed = { windows.remove(it); updateLocks(); notifyNow(); saveState() },
            onDecision = { win, ask -> decision(win, ask) },
            initialUrl = url)
        windows.add(w); w.show()
        if (min) w.setMinimized(true)
        saveState()
        updateLocks()
        if (AppState.pool.size == 0) {
            Toast.makeText(this, "Buscando proxys…", Toast.LENGTH_SHORT).show()
            refreshPool()
        }
    }

    /** Guarda quais janelas existem para recriá-las se o Android matar o app. */
    private fun saveState() {
        val arr = JSONArray()
        windows.forEach {
            arr.put(JSONObject().put("u", it.currentUrl() ?: JSONObject.NULL).put("m", it.isMinimized()))
        }
        Prefs.savedWindows = arr.toString()
    }

    private fun restoreWindows() {
        if (windows.isNotEmpty()) return
        val arr = runCatching { JSONArray(Prefs.savedWindows) }.getOrNull() ?: return
        for (i in 0 until minOf(arr.length(), MAX)) {
            val o = arr.getJSONObject(i)
            newWindow(if (o.isNull("u")) null else o.getString("u"), o.optBoolean("m"))
        }
    }

    private fun closeAll() { windows.toList().forEach { it.close() } }

    fun updateLocks() {
        val want = Prefs.keepAwake && windows.isNotEmpty()
        runCatching {
            if (want) {
                if (wakeLock == null)
                    wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "floatbrowser:keep")
                if (wakeLock?.isHeld == false) wakeLock?.acquire()
                if (wifiLock == null)
                    @Suppress("DEPRECATION")
                    wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "floatbrowser:wifi")
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        }
    }

    private fun act(a: String, rc: Int) = PendingIntent.getService(
        this, rc, Intent(this, FloatService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(title: String, a: String, rc: Int) = Notification.Action.Builder(
        Icon.createWithResource(this, android.R.drawable.ic_menu_view), title, act(a, rc)).build()

    /** Janela sem proxy válido: pergunta (aviso na janela e notificação) se pode usar o IP real. */
    private fun decision(w: FloatWindow, ask: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val nid = 100 + (w.id.removePrefix("w").toIntOrNull() ?: 0)
        if (!ask) { nm.cancel(nid); return }
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertas de proxy", NotificationManager.IMPORTANCE_HIGH))
        val real = PendingIntent.getService(
            this, nid, Intent(this, FloatService::class.java).setAction(ACTION_REAL).putExtra("w", w.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle("${w.id.uppercase()} sem proxy válido")
            .setContentText("Usar seu IP real nesta janela?")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view), "Usar IP real", real).build())
            .addAction(action("Mostrar", ACTION_SHOW, 1))
            .setAutoCancel(true).build()
        nm.notify(nid, n)
    }

    private fun notifyNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Janelas flutuantes", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val state = when {
            windows.isEmpty() -> "nenhuma janela"
            windows.all { it.isMinimized() } -> "${windows.size} janela(s) minimizada(s), ativas"
            else -> "${windows.size} janela(s) abertas"
        }
        val n = Notification.Builder(this, CH)
            .setContentTitle("FloatBrowser ativo")
            .setContentText(state)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(action("Mostrar", ACTION_SHOW, 1))
            .addAction(action("Minimizar", ACTION_MIN, 2))
            .addAction(action("Parar", ACTION_STOP, 3))
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        instance = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) }
        scope.cancel(); closeAll()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        super.onDestroy()
    }
}
