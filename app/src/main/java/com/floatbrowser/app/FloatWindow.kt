package com.floatbrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

@SuppressLint("ClickableViewAccessibility")
class FloatWindow(
    service: Context,
    private val wm: WindowManager,
    val id: String,
    slot: Int,
    private val onClosed: (FloatWindow) -> Unit
) {
    private val ctx = ContextThemeWrapper(service, android.R.style.Theme_Material_Light)
    private val d = service.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private val pool = AppState.pool
    private var direct = false

    private val params = WindowManager.LayoutParams(
        px(300), px(420),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = px(16 + slot * 24); y = px(72 + slot * 24)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    // modo PC + cookies isolados por janela (contextId)
    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .contextId("ctx_$id")
            .build()
    )
    private val gv = GeckoView(ctx)
    private val modeIcon = TextView(ctx)
    private val label = TextView(ctx)
    private val root = LinearLayout(ctx)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF121212.toInt())

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(px(8), px(4), px(8), px(4))
        }
        modeIcon.apply {
            textSize = 16f; setPadding(px(4), px(6), px(8), px(6))
            setOnClickListener { toggleDirect() }
        }
        label.apply {
            setTextColor(Color.WHITE); textSize = 11f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val alpha = SeekBar(ctx).apply {
            max = 70; progress = 70
            layoutParams = LinearLayout.LayoutParams(px(70), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    params.alpha = 0.3f + p / 100f
                    if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val close = TextView(ctx).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(px(10), px(6), px(4), px(6)); setOnClickListener { close() }
        }
        bar.addView(modeIcon)
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(alpha); bar.addView(close)

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        bar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ox + (e.rawX - sx).toInt(); params.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }

        val url = EditText(ctx).apply {
            setSingleLine(); hint = "Site ou pesquisa"; textSize = 13f
            setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { v, _, _ -> load(v.text.toString()); true }
        }

        val handle = TextView(ctx).apply {
            text = "◢"; setTextColor(Color.LTGRAY); gravity = Gravity.END
            setPadding(px(8), 0, px(12), px(2))
        }
        var rx = 0f; var ry = 0f; var w0 = 0; var h0 = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { rx = e.rawX; ry = e.rawY; w0 = params.width; h0 = params.height; true }
                MotionEvent.ACTION_MOVE -> {
                    params.width = maxOf(px(200), w0 + (e.rawX - rx).toInt())
                    params.height = maxOf(px(240), h0 + (e.rawY - ry).toInt())
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }

        root.addView(bar)
        root.addView(url)
        root.addView(gv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        session.open(GeckoHolder.get(service))
        gv.setSession(session)
    }

    fun show() {
        wm.addView(root, params)
        refreshLabel()
        load("https://www.google.com")
    }

    fun load(input: String) {
        val t = input.trim(); if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        session.loadUri(u)
    }

    private fun toggleDirect() {
        direct = !direct
        if (direct) pool.release(id)
        refreshLabel()
    }

    private fun refreshLabel() {
        val n = id.uppercase()
        if (direct) { modeIcon.text = "🌐"; label.text = "$n · direto (sem proxy)" }
        else {
            val p = pool.current(id) ?: pool.assignTo(id)
            modeIcon.text = "🔒"
            label.text = if (p != null) "$n · prévia: ${p.host}:${p.port}" else "$n · pool vazio"
        }
    }

    fun clearData() {
        GeckoHolder.get(root.context).storageController
            .clearDataForSessionContext("ctx_$id")
    }

    fun close() {
        pool.release(id)
        runCatching { gv.releaseSession() }
        session.close()
        runCatching { wm.removeView(root) }
        onClosed(this)
    }
}
