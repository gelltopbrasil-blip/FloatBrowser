package com.floatbrowser.app

import android.content.Context
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/** Ponte entre o app e a extensão interna (proxy por janela, ferramentas, scripts). */
object ProxyBridge {
    private const val EXT_ID = "floatproxy@floatbrowser.app"
    private var port: WebExtension.Port? = null
    private var started = false
    private val configs = HashMap<String, JSONObject>()
    private var scripts: JSONObject? = null
    private val mappedCb = HashMap<String, (Boolean) -> Unit>()
    private val failCb = HashMap<String, () -> Unit>()
    private val eventCb = HashMap<String, (JSONObject) -> Unit>()

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val m = message as? JSONObject ?: return
            val w = m.optString("w")
            when (m.optString("type")) {
                "mapped" -> mappedCb[w]?.invoke(m.optBoolean("ok", true))
                "fail" -> failCb[w]?.invoke()
                "csv" -> ToolsNative.saveCsv(m.optString("name"), m.optString("text"))
                "savescript" -> ToolsNative.saveScript(m.optString("name"), m.optString("text"))
                "copy" -> ToolsNative.copy(m.optString("text"))
                else -> eventCb[w]?.invoke(m)
            }
        }
        override fun onDisconnect(port: WebExtension.Port) {
            if (this@ProxyBridge.port === port) this@ProxyBridge.port = null
        }
    }

    private val msgDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            this@ProxyBridge.port = port
            port.setDelegate(portDelegate)
            configs.values.forEach { port.postMessage(it) }
            scripts?.let { port.postMessage(it) }
            port.postMessage(JSONObject().put("type", "tools").put("on", Prefs.tools))
        }
    }

    fun init(ctx: Context) {
        if (started) return
        started = true
        scripts = ScriptStore.payload()
        GeckoHolder.get(ctx).webExtensionController
            .ensureBuiltIn("resource://android/assets/proxyext/", EXT_ID)
            .accept(
                { ext -> ext?.setMessageDelegate(msgDelegate, "floatbrowser") },
                { _ -> }
            )
    }

    val connected get() = port != null

    private fun send(o: JSONObject) { port?.postMessage(o) }

    fun setConfig(w: String, p: ProxyEntry?, direct: Boolean) {
        val o = JSONObject().put("type", "cfg").put("w", w).put("direct", direct)
        if (p != null && !direct) o.put("proxy", JSONObject()
            .put("type", p.type).put("host", p.host).put("port", p.port)
            .put("user", p.user ?: JSONObject.NULL).put("pass", p.pass ?: JSONObject.NULL))
        else o.put("proxy", JSONObject.NULL)
        configs[w] = o
        send(o)
    }

    fun register(w: String, onMapped: (Boolean) -> Unit, onFail: () -> Unit, onEvent: (JSONObject) -> Unit = {}) {
        mappedCb[w] = onMapped; failCb[w] = onFail; eventCb[w] = onEvent
    }

    fun forget(w: String) {
        configs.remove(w); mappedCb.remove(w); failCb.remove(w); eventCb.remove(w)
        send(JSONObject().put("type", "forget").put("w", w))
    }

    fun setTools(on: Boolean) { send(JSONObject().put("type", "tools").put("on", on)) }

    fun pushScripts() {
        scripts = ScriptStore.payload()
        send(scripts!!)
    }

    // ---- ferramentas por janela ----
    fun tool(w: String, cmd: String, on: Boolean) =
        send(JSONObject().put("type", "tool").put("w", w).put("cmd", cmd).put("on", on))

    fun monitor(w: String, on: Boolean, sel: String = "", every: Int = 60, text: String = "") =
        send(JSONObject().put("type", "monitor").put("w", w).put("on", on)
            .put("sel", sel).put("every", every).put("text", text))

    fun autostart(w: String) = send(JSONObject().put("type", "autostart").put("w", w))
}
