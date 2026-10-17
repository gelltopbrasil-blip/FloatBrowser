package com.floatbrowser.app

import android.content.Context
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/** Ponte entre o app e a extensão interna (proxy por janela, failover, scripts). */
object ProxyBridge {
    private const val EXT_ID = "floatproxy@floatbrowser.app"
    private var port: WebExtension.Port? = null
    private var started = false
    private val configs = HashMap<String, JSONObject>()
    private var scripts: JSONObject? = null
    private val mappedCb = HashMap<String, (Boolean) -> Unit>()
    private val failCb = HashMap<String, () -> Unit>()

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val m = message as? JSONObject ?: return
            val w = m.optString("w")
            when (m.optString("type")) {
                "mapped" -> mappedCb[w]?.invoke(m.optBoolean("ok", true))
                "fail" -> failCb[w]?.invoke()
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

    fun register(w: String, onMapped: (Boolean) -> Unit, onFail: () -> Unit) {
        mappedCb[w] = onMapped; failCb[w] = onFail
    }

    fun forget(w: String) {
        configs.remove(w); mappedCb.remove(w); failCb.remove(w)
        send(JSONObject().put("type", "forget").put("w", w))
    }

    fun pushScripts() {
        scripts = ScriptStore.payload()
        send(scripts!!)
    }
}
