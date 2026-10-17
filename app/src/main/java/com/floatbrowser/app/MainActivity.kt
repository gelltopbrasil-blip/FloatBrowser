package com.floatbrowser.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pool = ProxyPool()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val status = TextView(this).apply { textSize = 16f; text = "Pronto" }
        val btn = Button(this).apply { text = "Atualizar proxys" }
        val test = Button(this).apply { text = "Simular 3 janelas" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
            addView(btn); addView(test); addView(status)
        })
        btn.setOnClickListener { scope.launch { pool.refresh { status.text = it } } }
        test.setOnClickListener {
            status.text = (1..3).joinToString("\n") { i ->
                "Janela $i → ${pool.assignTo("w$i")?.key() ?: "sem proxy"}"
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
