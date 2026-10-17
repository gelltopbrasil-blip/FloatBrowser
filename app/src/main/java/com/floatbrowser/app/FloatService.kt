package com.floatbrowser.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.*

class FloatService : Service() {
    companion object {
        const val ACTION_NEW = "new"
        const val ACTION_STOP = "stop"
        const val ACTION_MIN = "min"
        const val ACTION_SHOW = "show"
        private const val CH = "float"
        private const val MAX = 4
        @Volatile var instance: FloatService? = null
    }

    private val windows = mutableListOf<FloatWindow>()
    private var counter = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProxyBridge.init(this)
        scope.launch {
            while (true) {
                delay(10 * 60_000L)
                if (windows.isNotEmpty()) refreshPool()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEW -> { notifyNow(); newWindow() }
            ACTION_MIN -> { notifyNow(); windows.forEach { it.setMinimized(true) } }
            ACTION_SHOW -> { notifyNow(); windows.forEach { it.setMinimized(false) } }
            ACTION_STOP -> {
                notifyNow(); closeAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return START_NOT_STICKY
            }
            else -> notifyNow()
        }
        notifyNow()
        return START_NOT_STICKY
    }

    private fun refreshPool() = scope.launch {
        AppState.pool.refresh {}
        windows.toList().forEach { it.onPoolRefreshed() }
    }

    private fun newWindow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permita a sobreposição primeiro", Toast.LENGTH_LONG).show(); return
        }
        if (windows.size >= MAX) {
            Toast.makeText(this, "Máximo de $MAX janelas", Toast.LENGTH_SHORT).show(); return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w = FloatWindow(this, wm, "w${++counter}", windows.size) {
            windows.remove(it); updateLocks(); notifyNow()
        }
        windows.add(w); w.show()
        updateLocks()
        if (AppState.pool.size == 0) {
            Toast.makeText(this, "Buscando proxys…", Toast.LENGTH_SHORT).show()
            refreshPool()
        }
    }

    private fun closeAll() { windows.toList().forEach { it.close() } }

    /** Mantém CPU e Wi-Fi acordados com a tela desligada (consome mais bateria). */
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
        scope.cancel(); closeAll()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        super.onDestroy()
    }
}
