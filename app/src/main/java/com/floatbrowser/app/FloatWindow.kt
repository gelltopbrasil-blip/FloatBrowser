package com.floatbrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

/**
 * Cada janela = 2 janelas de sistema:
 *  - barra (sempre opaca e tocável)
 *  - conteúdo (navegador; transparência; modo "fantasma" com a chavinha; minimizar)
 */
@SuppressLint("ClickableViewAccessibility")
class FloatWindow(
    private val svc: Context,
    private val wm: WindowManager,
    val id: String,
    slot: Int,
    private val onClosed: (FloatWindow) -> Unit,
    private val onDecision: (FloatWindow, Boolean) -> Unit,
    private val initialUrl: String? = null,
    val profile: Profile? = null
) {
    private val ctx = ContextThemeWrapper(svc, android.R.style.Theme_Material_Light)
    private val d = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private val pool = AppState.pool
    private val handler = Handler(Looper.getMainLooper())

    // perfil com proxy fixo (ip:porta): usado na 1ª atribuição; se cair, a janela volta ao automático
    private val pinned: ProxyEntry? = profile?.proxy?.let { spec ->
        if (spec.equals("auto", true) || spec.equals("direto", true)) null else pool.parse(spec)
    }
    private var pinTried = false
    val profileName: String? get() = profile?.name

    private var direct = (profile?.proxy ?: "").equals("direto", ignoreCase = true)
    private var started = false
    private var mappedOk = false
    private var lastFail = 0L
    private var failing = false
    private var badCount = 0
    private var asking = false
    private var dismissed = false
    private var dismissedAt = 0L
    private var locking = false
    private var locked = false
    private var minimized = false
    private var contentAlpha = 1f
    private var attached = false
    private var curUrl: String? = initialUrl
    private var recoverCount = 0
    private var monitoring = false
    private var monKey: TextView? = null
    private var toolsKey: TextView? = null
    private var recoverSince = 0L

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
            .contextId(if (profile != null) ProfileStore.contextId(profile.name) else "ctx_$id")
            .build()
    )
    private val gv = GeckoView(ctx)
    private val modeIcon = TextView(ctx)
    private val label = TextView(ctx)
    private val lockSwitch = Switch(ctx)
    private val barView = LinearLayout(ctx)
    private val content = LinearLayout(ctx)
    private val banner = LinearLayout(ctx)

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
            wm.updateViewLayout(content, contentP)
            wm.updateViewLayout(barView, barP)
        }
    }

    init {
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(0xFF121212.toInt())

        barView.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(px(8), px(2), px(8), px(2))
        }
        // toque = informação; TOQUE LONGO = alternar IP real (evita toque acidental)
        modeIcon.apply {
            textSize = 16f; setPadding(px(4), px(6), px(8), px(6))
            setOnClickListener { showInfo() }
            setOnLongClickListener { toggleDirect(); true }
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
                    contentAlpha = 0.15f + p / 100f
                    updateLayout()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        lockSwitch.apply {
            scaleX = 0.7f; scaleY = 0.7f
            layoutParams = LinearLayout.LayoutParams(px(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, c -> if (!locking) setLocked(c) }
        }
        val minBtn = TextView(ctx).apply {
            text = "—"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(px(8), px(6), px(8), px(6)); setOnClickListener { setMinimized(true) }
        }
        // ✕: toque rápido = fechar; SEGURAR = arrastar a janela (útil quando a janela está bem pequena)
        val closeBtn = TextView(ctx).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(px(10), px(6), px(6), px(6))
        }
        var cx = 0f; var cy = 0f; var cox = 0; var coy = 0
        var dragging = false; var moved = false
        val hold = Runnable {
            dragging = true
            closeBtn.setTextColor(0xFFFFC107.toInt())
            closeBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        closeBtn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    cx = e.rawX; cy = e.rawY; cox = posX; coy = posY; dragging = false; moved = false
                    handler.postDelayed(hold, 350); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        posX = cox + (e.rawX - cx).toInt(); posY = coy + (e.rawY - cy).toInt(); updateLayout()
                    } else if (abs(e.rawX - cx) > px(10) || abs(e.rawY - cy) > px(10)) {
                        moved = true; handler.removeCallbacks(hold)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(hold); closeBtn.setTextColor(Color.WHITE)
                    if (!dragging && !moved) this@FloatWindow.close()
                    dragging = false; true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(hold); closeBtn.setTextColor(Color.WHITE); dragging = false; true
                }
                else -> false
            }
        }
        barView.addView(modeIcon)
        barView.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barView.addView(alphaBar); barView.addView(lockSwitch); barView.addView(minBtn); barView.addView(closeBtn)

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

        // aviso: sem proxy válido -> perguntar se usa o IP real
        banner.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFB71C1C.toInt()); visibility = View.GONE
            setPadding(px(8), px(6), px(8), px(6))
        }
        val msg = TextView(ctx).apply {
            text = "Sem proxy válido. Usar seu IP real?"; setTextColor(Color.WHITE); textSize = 11f
        }
        val yes = TextView(ctx).apply {
            text = "USAR IP REAL"; setTextColor(Color.WHITE); textSize = 11f; setTypeface(null, Typeface.BOLD)
            setPadding(px(8), px(6), px(8), px(6)); setOnClickListener { useRealIp() }
        }
        val no = TextView(ctx).apply {
            text = "AGUARDAR"; setTextColor(0xFFFFCDD2.toInt()); textSize = 11f
            setPadding(px(8), px(6), px(4), px(6)); setOnClickListener { dismissed = true; dismissedAt = System.currentTimeMillis(); hideAsk() }
        }
        banner.addView(msg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(yes); banner.addView(no)

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

        // teclas da janela: linha 1 = navegação/proxy, linha 2 = ferramentas do projeto
        val keys = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(px(2), px(2), px(2), px(2))
        }
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(row1); keys.addView(row2)
        fun key(row: LinearLayout, t: String, a: () -> Unit): TextView {
            val v = TextView(ctx).apply {
                text = t; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setBackgroundColor(0xFF37474F.toInt())
                setPadding(0, px(6), 0, px(6))
                setOnClickListener { a() }
            }
            row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(px(1), px(1), px(1), px(1))
            })
            return v
        }
        key(row1, "◀") { session.goBack() }                       // voltar no site
        key(row1, "▶") { session.goForward() }                    // avançar
        key(row1, "⟳") { session.reload() }                       // recarregar
        key(row1, "🔀") { swapProxy() }                            // trocar de proxy (novo IP)
        key(row1, "🌐") { session.loadUri("https://api.ipify.org") } // mostrar o IP de saída desta janela
        key(row2, "🎯") { toolKey("pick") }                       // seletor: toque num elemento e veja o CSS
        key(row2, "⏺") { toolKey("rec") }                        // gravador de passos (toque de novo para parar e salvar)
        key(row2, "📊") { toolKey("extract") }                    // extrair uma lista para CSV
        monKey = key(row2, "👁") { monitorKey() }                 // monitor de página (verde = ligado; toque para parar)
        toolsKey = key(row2, "🛠") { toggleTools() }              // ferramentas nas páginas: ligar/desligar (azul = ligadas)
        paintTools()

        content.addView(banner)
        content.addView(keys)
        content.addView(url)
        content.addView(gv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                Downloader.save(svc, response)
            }
            // processo do navegador morreu (pouca memória, tela desligada...): reabre sozinho
            override fun onCrash(session: GeckoSession) { recover() }
            override fun onKill(session: GeckoSession) { recover() }
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
        wm.addView(barView, barP)
        attached = true
        session.setActive(true)
        ProxyBridge.register(id,
            onMapped = { ok -> mappedOk = ok; startPage(); refresh() },
            onFail = { handleFail() },
            onEvent = { m -> onBridgeEvent(m) })
        applyConfig()
        session.loadUri("http://floatbrowser.invalid/hello?w=$id")
        handler.postDelayed({ if (!started) { startPage(); refresh() } }, 2500)
    }

    private fun startPage() {
        if (started) return
        started = true
        restoreMonitor()
        load(curUrl ?: "https://www.google.com")
    }

    /** Perfil com monitor salvo: religa o monitor antes de carregar a página. */
    private fun restoreMonitor() {
        val p = profile?.let { ProfileStore.get(it.name) } ?: return
        if (p.monSel.isNotEmpty()) ProxyBridge.monitor(id, true, p.monSel, p.monEvery, p.monText)
    }

    fun load(input: String) {
        val t = input.trim(); if (t.isEmpty()) return
        val u = when {
            t.equals("meuip", true) -> "https://api.ipify.org"
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        curUrl = u
        session.loadUri(u)
    }

    // ---------- proxy ----------
    private fun applyConfig() {
        val p = if (direct) null else (pool.current(id) ?: pinnedFirst() ?: pool.assignTo(id))
        ProxyBridge.setConfig(id, p, direct)
        refresh()
    }

    private fun pinnedFirst(): ProxyEntry? {
        val pin = pinned ?: return null
        if (pinTried) return null
        pinTried = true
        return if (pool.assignExact(id, pin)) pin else null
    }

    private fun refresh() {
        val n = profile?.name ?: id.uppercase()
        val p = pool.current(id)
        val warn = if (started && !mappedOk) "⚠ " else ""
        when {
            direct -> { modeIcon.text = "🌐"; label.text = "$n · IP real (direto)"; hideAsk() }
            p != null -> {
                modeIcon.text = "🔒"; label.text = "$warn$n · ${p.exitIp ?: p.host}"
                dismissed = false; hideAsk()
            }
            else -> {
                modeIcon.text = "⛔"; label.text = "$n · sem proxy (bloqueado)"
                // se você escolheu AGUARDAR, pergunta de novo depois de 2 minutos
                if (started && (!dismissed || System.currentTimeMillis() - dismissedAt > 120_000)) {
                    dismissed = false; showAsk()
                }
            }
        }
    }

    private fun showInfo() {
        val p = pool.current(id)
        val t = when {
            direct -> "IP real (direto). Segure o ícone para voltar ao proxy."
            p != null -> "Proxy ${p.host}:${p.port} · saída ${p.exitIp ?: "?"}. Segure o ícone para usar IP real."
            else -> "Sem proxy válido. Segure o ícone para usar IP real."
        }
        Toast.makeText(svc, t, Toast.LENGTH_LONG).show()
    }

    private fun showAsk() {
        if (asking) return
        asking = true; banner.visibility = View.VISIBLE; onDecision(this, true)
    }

    private fun hideAsk() {
        if (!asking) return
        asking = false; banner.visibility = View.GONE; onDecision(this, false)
    }

    private fun toggleDirect() {
        direct = !direct
        if (direct) pool.release(id)
        applyConfig()
        session.reload()
    }

    /** Usado pelo agendador: recarrega a página do perfil (scripts com INICIAR_AUTOMATICO rodam de novo). */
    fun reloadPage() { session.reload() }

    /** Botão 🔀: descarta o proxy atual (quarentena) e testa outros para dar um novo IP. */
    private fun swapProxy() {
        if (direct) {
            Toast.makeText(svc, "Janela em IP real. Segure o ícone da barra para voltar ao proxy.", Toast.LENGTH_SHORT).show()
            return
        }
        lastFail = 0L
        handleFail()
    }

    /** Botão 🛠: liga/desliga as ferramentas dentro das páginas (valem nas próximas páginas). */
    private fun toggleTools() {
        Prefs.tools = !Prefs.tools
        ProxyBridge.setTools(Prefs.tools)
        paintTools()
        Toast.makeText(svc, "Ferramentas 🛠 " + (if (Prefs.tools) "ligadas" else "desligadas") +
            " (recarregando a página)", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ session.reload() }, 400)   // dá tempo da extensão receber o novo estado
    }

    private fun paintTools() {
        toolsKey?.setBackgroundColor(if (Prefs.tools) 0xFF1565C0.toInt() else 0xFF37474F.toInt())
    }

    /** Teclas 🎯 ⏺ 📊 👁: acionam a ferramenta dentro da página desta janela. */
    private fun toolKey(tool: String) {
        if (!Prefs.tools) {
            Toast.makeText(svc, "Ferramentas desligadas: toque em 🛠 para ligar", Toast.LENGTH_SHORT).show()
            return
        }
        ProxyBridge.ui(id, tool)
    }

    /** Tecla 👁: com monitor ligado, desliga; senão cria o monitor do elemento escolhido com 🎯. */
    private fun monitorKey() {
        if (monitoring) ProxyBridge.monitor(id, false) else toolKey("mon")
    }

    /** Eventos da extensão para esta janela: aviso de mudança, mensagens e estado do monitor. */
    private fun onBridgeEvent(m: JSONObject) {
        handler.post {
            when (m.optString("type")) {
                "monitor" -> {
                    val nid = 200 + (id.removePrefix("w").toIntOrNull() ?: 0)
                    Notify.alert(svc, nid, "${id.uppercase()}: mudou",
                        (m.optString("old") + " → " + m.optString("new")).take(180))
                }
                "info" -> Toast.makeText(svc, m.optString("msg").take(200), Toast.LENGTH_LONG).show()
                "monstate" -> {
                    monitoring = m.optBoolean("on")
                    monKey?.setBackgroundColor(if (monitoring) 0xFF00796B.toInt() else 0xFF37474F.toInt())
                    profile?.let { pr ->   // janela de perfil: o monitor fica salvo no perfil
                        if (monitoring) ProfileStore.setMonitor(pr.name, m.optString("sel"), m.optInt("every", 60), m.optString("text"))
                        else ProfileStore.setMonitor(pr.name, "", 60, "")
                    }
                }
                else -> {}
            }
        }
    }

    /** Resposta ao aviso "Sem proxy válido": liberar o IP real nesta janela. */
    fun useRealIp() {
        direct = true; pool.release(id); hideAsk()
        applyConfig(); session.reload()
    }

    /** Troca rápida: descarta o proxy ruim, testa candidatos na hora e só então aplica. */
    private fun handleFail() {
        val now = System.currentTimeMillis()
        if (direct || failing || now - lastFail < 4000) return
        lastFail = now; failing = true
        label.text = "${id.uppercase()} · trocando proxy…"
        AppState.scope.launch {
            pool.burn(id)
            val p = pool.nextVerified(id)
            failing = false
            applyConfig()
            if (p != null) session.reload()
            else if (pool.needsRefresh()) {
                pool.refresh {}
                onPoolRefreshed()
            }
        }
    }

    /** Teste periódico (serviço): detecta proxy morto mesmo com a janela minimizada. */
    fun checkHealth() {
        if (direct || failing) return
        val p = pool.current(id)
        if (p == null) { onPoolRefreshed(); refresh(); return } // tenta de novo e reavisa
        
        AppState.scope.launch {
            if (pool.test(p, 3000) == null) {
                badCount++
                if (badCount >= 2) { badCount = 0; handleFail() }
            } else badCount = 0
        }
    }

    /** Rede caiu e voltou (4G/Wi-Fi): recarrega e revalida. */
    fun onNetworkBack() {
        session.reload()
        checkHealth()
    }

    fun onPoolRefreshed() {
        if (!direct && pool.current(id) == null) {
            applyConfig()
            if (pool.current(id) != null) session.reload()
        }
    }

    // ---------- chavinha / minimizar ----------
    private fun setLocked(on: Boolean) {
        locked = on
        updateLayout()
        if (lockSwitch.isChecked != on) { locking = true; lockSwitch.isChecked = on; locking = false }
    }

    fun setMinimized(m: Boolean) {
        minimized = m
        barView.visibility = if (m) View.GONE else View.VISIBLE
        updateLayout()
    }

    fun isMinimized() = minimized

    fun currentUrl(): String? = curUrl

    /** Mantém a barra sempre por cima das outras janelas. */
    fun raiseBar() {
        if (!attached) return
        runCatching { wm.removeView(barView); wm.addView(barView, barP) }
    }

    /** Depois de ligar a tela, recria a superfície do navegador (evita ficar em branco). */
    fun refreshSurface() {
        runCatching { gv.releaseSession(); gv.setSession(session) }
        runCatching { session.setActive(true) }
    }

    private fun recover() {
        handler.post {
            val now = System.currentTimeMillis()
            if (now - recoverSince > 60_000) { recoverSince = now; recoverCount = 0 }
            if (++recoverCount > 5) { label.text = "${id.uppercase()} · página caiu várias vezes"; return@post }
            started = false; mappedOk = false
            runCatching { session.open(GeckoHolder.get(svc)); session.setActive(true) }
            session.loadUri("http://floatbrowser.invalid/hello?w=$id")
            handler.postDelayed({ if (!started) { startPage(); refresh() } }, 2500)
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        hideAsk()
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
