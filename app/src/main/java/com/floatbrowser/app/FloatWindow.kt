package com.floatbrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

/**
 * Cada janela = 2 janelas de sistema:
 *  - barra (sempre opaca e tocável: mover, transparência, chavinha, fechar)
 *  - conteúdo (navegador; recebe transparência e pode ficar "fantasma" com a chavinha)
 */
@SuppressLint("ClickableViewAccessibility")
class FloatWindow(
    private val svc: Context,
    private val wm: WindowManager,
    val id: String,
    slot: Int,
    private val onClosed: (FloatWindow) -> Unit
) {
    private val ctx = ContextThemeWrapper(svc, android.R.style.Theme_Material_Light)
    private val d = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private val pool = AppState.pool
    private val handler = Handler(Looper.getMainLooper())

    private var direct = false
    private var started = false
    private var mappedOk = false
    private var lastFail = 0L
    private var locking = false
    private var locked = false
    private var minimized = false
    private var contentAlpha = 1f
    private var attached = false

    private val barH = px(40)
    private var posX = px(16 + slot * 24)
    private var posY = px(72 + slot * 24)
    private var winW = px(300)
    private var winH = px(380)

    private val barP = WindowManager.LayoutParams(
        winW, barH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val contentP = WindowManager.LayoutParams(
        winW, winH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

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
    private val lockSwitch = Switch(ctx)
    private val barView = LinearLayout(ctx)
    private val content = LinearLayout(ctx)

    private fun updateLayout() {
        val untouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val keep = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        barP.x = posX; barP.y = posY; barP.width = winW
        barP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or keep or
            (if (minimized) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        contentP.x = posX; contentP.y = posY + barH; contentP.width = winW; contentP.height = winH
        contentP.flags = if (locked || minimized) keep or untouchable else keep
        contentP.alpha = when {
            minimized -> 0f
            locked -> minOf(contentAlpha, 0.8f) // Android 12+: toque só atravessa com opacidade <= 80%
            else -> contentAlpha
        }
        if (attached) {
            wm.updateViewLayout(contentWindowView(), contentP)
            wm.updateViewLayout(barView, barP)
        }
    }
    private fun contentWindowView() = content

    init {
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(0xFF121212.toInt())

        barView.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(px(8), px(2), px(8), px(2))
        }
        modeIcon.apply {
            textSize = 16f; setPadding(px(4), px(6), px(8), px(6))
            setOnClickListener { toggleDirect() }
        }
        label.apply {
            setTextColor(Color.WHITE); textSize = 11f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val alphaBar = SeekBar(ctx).apply {
            max = 85; progress = 85
            layoutParams = LinearLayout.LayoutParams(px(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    contentAlpha = 0.15f + p / 100f   // de 15% a 100%; a barra nunca some
                    updateLayout()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        // chavinha: ativada = navegador vira "fantasma" (toque passa para o app de baixo)
        lockSwitch.apply {
            scaleX = 0.7f; scaleY = 0.7f
            layoutParams = LinearLayout.LayoutParams(px(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, c -> if (!locking) setLocked(c) }
        }
        val close = TextView(ctx).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(px(8), px(6), px(4), px(6)); setOnClickListener { close() }
        }
        barView.addView(modeIcon)
        barView.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val minBtn = TextView(ctx).apply {
            text = "—"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(px(8), px(6), px(8), px(6)); setOnClickListener { setMinimized(true) }
        }
        barView.addView(alphaBar); barView.addView(lockSwitch); barView.addView(minBtn); barView.addView(close)

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        barView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = posX; oy = posY; true }
                MotionEvent.ACTION_MOVE -> {
                    posX = ox + (e.rawX - sx).toInt(); posY = oy + (e.rawY - sy).toInt()
                    updateLayout(); true
                }
                else -> false
            }
        }

        val url = EditText(ctx).apply {
            setSingleLine(); hint = "Site ou pesquisa (digite meuip para testar)"; textSize = 13f
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
                MotionEvent.ACTION_DOWN -> { rx = e.rawX; ry = e.rawY; w0 = winW; h0 = winH; true }
                MotionEvent.ACTION_MOVE -> {
                    winW = maxOf(px(220), w0 + (e.rawX - rx).toInt())
                    winH = maxOf(px(220), h0 + (e.rawY - ry).toInt())
                    updateLayout(); true
                }
                else -> false
            }
        }

        content.addView(url)
        content.addView(gv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                Downloader.save(svc, response)
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                PickerActivity.pick(svc) { uri ->
                    res.complete(if (uri != null) prompt.confirm(svc, uri) else prompt.dismiss())
                }
                return res
            }
        }

        session.open(GeckoHolder.get(svc))
        gv.setSession(session)
    }

    fun show() {
        updateLayout()
        wm.addView(content, contentP)
        wm.addView(barView, barP)   // a barra por último = fica por cima
        attached = true
        session.setActive(true) // evita que o Gecko "adormeça" a página
        ProxyBridge.register(id,
            onMapped = { ok -> mappedOk = ok; refresh(); startPage() },
            onFail = { handleFail() })
        applyConfig()
        session.loadUri("http://floatbrowser.invalid/hello?w=$id")
        handler.postDelayed({ if (!started) { startPage(); refresh() } }, 2500)
    }

    private fun startPage() {
        if (started) return
        started = true
        load("https://www.google.com")
    }

    fun load(input: String) {
        val t = input.trim(); if (t.isEmpty()) return
        val u = when {
            t.equals("meuip", true) -> "https://api.ipify.org"
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        session.loadUri(u)
    }

    // ---------- proxy ----------
    private fun applyConfig() {
        val p = if (direct) null else (pool.current(id) ?: pool.assignTo(id))
        ProxyBridge.setConfig(id, p, direct)
        refresh()
    }

    private fun refresh() {
        val n = id.uppercase()
        val p = pool.current(id)
        val warn = if (started && !mappedOk) "⚠ " else ""
        when {
            direct -> { modeIcon.text = "🌐"; label.text = "$n · direto (sem proxy)" }
            p != null -> { modeIcon.text = "🔒"; label.text = "$warn$n · ${p.host}:${p.port}" }
            else -> { modeIcon.text = "⛔"; label.text = "$n · sem proxy (bloqueado)" }
        }
    }

    private fun toggleDirect() {
        direct = !direct
        if (direct) pool.release(id)
        applyConfig()
        session.reload()
    }

    private fun handleFail() {
        val now = System.currentTimeMillis()
        if (direct || now - lastFail < 6000) return
        lastFail = now
        pool.markBad(id)
        applyConfig()
        session.reload()
    }

    fun onPoolRefreshed() {
        if (!direct && pool.current(id) == null) {
            applyConfig()
            if (pool.current(id) != null) session.reload()
        }
    }

    // ---------- chavinha de bloqueio ----------
    private fun setLocked(on: Boolean) {
        locked = on
        updateLayout()
        if (lockSwitch.isChecked != on) { locking = true; lockSwitch.isChecked = on; locking = false }
    }

    /** Minimizar: some da tela por completo, mas a página continua ativa e online. */
    fun setMinimized(m: Boolean) {
        minimized = m
        barView.visibility = if (m) View.GONE else View.VISIBLE
        updateLayout()
    }

    fun isMinimized() = minimized

    fun close() {
        handler.removeCallbacksAndMessages(null)
        ProxyBridge.forget(id)
        pool.release(id)
        attached = false
        runCatching { gv.releaseSession() }
        session.close()
        runCatching { wm.removeView(barView) }
        runCatching { wm.removeView(content) }
        onClosed(this)
    }
}
