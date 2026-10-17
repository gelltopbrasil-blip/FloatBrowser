package com.floatbrowser.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.*

class FloatService : Service() {
    companion object {
        const val ACTION_NEW = "new"
        const val ACTION_STOP = "stop"
        private const val CH = "float"
        private const val MAX = 4
    }

    private val windows = mutableListOf<FloatWindow>()
    private var counter = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProxyBridge.init(this)
        // atualiza a lista a cada 10 min enquanto houver janelas abertas
        scope.launch {
            while (true) {
                delay(10 * 60_000L)
                if (windows.isNotEmpty()) refreshPool()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_NEW -> newWindow()
            ACTION_STOP -> { closeAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
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
        val w = FloatWindow(this, wm, "w${++counter}", windows.size) { windows.remove(it) }
        windows.add(w); w.show()
        if (AppState.pool.size == 0) {
            Toast.makeText(this, "Buscando proxys…", Toast.LENGTH_SHORT).show()
            refreshPool()
        }
    }

    private fun closeAll() { windows.toList().forEach { it.close() } }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Janelas flutuantes", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle("FloatBrowser ativo")
            .setContentText("Toque em Parar para fechar tudo")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_delete), "Parar", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() { scope.cancel(); closeAll(); super.onDestroy() }
}
