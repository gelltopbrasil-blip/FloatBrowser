package com.floatbrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import android.widget.Switch
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import kotlin.math.abs

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
    private var bubble: TextView? = null
    private var savedAlpha = 1f

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
            layoutParams = LinearLayout.LayoutParams(px(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    params.alpha = 0.3f + p / 100f
                    if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        // chavinha: ativada = janela fica "fantasma", o toque passa para o app/tela de baixo
        lockSwitch.apply {
            scaleX = 0.7f; scaleY = 0.7f
            layoutParams = LinearLayout.LayoutParams(px(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, c -> if (!locking) setLocked(c) }
        }
        val close = TextView(ctx).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(px(6), px(6), px(4), px(6)); setOnClickListener { close() }
        }
        bar.addView(modeIcon)
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(alpha); bar.addView(lockSwitch); bar.addView(close)

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

        // downloads -> pasta Download
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                Downloader.save(svc, response)
            }
        }
        // upload -> seletor de arquivos do Android
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
        wm.addView(root, params)
        ProxyBridge.register(id,
            onMapped = { ok -> mappedOk = ok; refresh(); startPage() },
            onFail = { handleFail() })
        applyConfig()
        // handshake: a extensão descobre qual aba é esta janela
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

    /** Chamado pelo serviço depois que a lista de proxys é atualizada. */
    fun onPoolRefreshed() {
        if (!direct && pool.current(id) == null) {
            applyConfig()
            if (pool.current(id) != null) session.reload()
        }
    }

    // ---------- chavinha de bloqueio ----------
    private fun setLocked(on: Boolean) {
        val untouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (on) {
            savedAlpha = params.alpha
            params.alpha = minOf(params.alpha, 0.8f) // Android 12+ só deixa o toque passar se opacidade <= 0.8
            params.flags = params.flags or untouchable
            showBubble()
        } else {
            params.flags = params.flags and untouchable.inv()
            params.alpha = savedAlpha
            hideBubble()
        }
        wm.updateViewLayout(root, params)
        if (lockSwitch.isChecked != on) { locking = true; lockSwitch.isChecked = on; locking = false }
    }

    private fun showBubble() {
        val b = TextView(ctx).apply {
            text = "🔓"; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDD263238.toInt()) }
        }
        val bp = WindowManager.LayoutParams(
            px(44), px(44), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + params.width - px(52); y = params.y + px(4)
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = bp.x; oy = bp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > px(6) || abs(dy) > px(6)) moved = true
                    bp.x = ox + dx.toInt(); bp.y = oy + dy.toInt()
                    wm.updateViewLayout(b, bp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) setLocked(false); true }
                else -> false
            }
        }
        wm.addView(b, bp)
        bubble = b
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        ProxyBridge.forget(id)
        pool.release(id)
        runCatching { gv.releaseSession() }
        session.close()
        runCatching { wm.removeView(root) }
        onClosed(this)
    }
}
