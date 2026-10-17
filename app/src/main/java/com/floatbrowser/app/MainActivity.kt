package com.floatbrowser.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import org.mozilla.geckoview.StorageController

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        status = TextView(this).apply { textSize = 15f; text = "Pronto" }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 90, 40, 40)
        }
        fun btn(t: String, a: () -> Unit) = col.addView(Button(this).apply {
            text = t; setOnClickListener { a() }
        })

        btn("1) Permitir janelas flutuantes") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2) Nova janela flutuante") {
            if (!Settings.canDrawOverlays(this)) status.text = "Faça o passo 1 primeiro"
            else startForegroundService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_NEW))
        }
        btn("Atualizar proxys") { scope.launch { AppState.pool.refresh { status.text = it } } }
        btn("Limpar cache") {
            GeckoHolder.get(this).storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
            status.text = "Cache limpo"
        }
        btn("Limpar cookies e dados de sites") {
            GeckoHolder.get(this).storageController.clearData(
                StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA)
            status.text = "Cookies e dados limpos"
        }
        btn("Fechar todas as janelas") {
            startForegroundService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_STOP))
        }
        col.addView(status)
        setContentView(col)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
