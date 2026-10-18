package com.floatbrowser.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recebe as ações das ferramentas 🛠 (dentro das páginas) e faz o trabalho no app. */
object ToolsNative {
    private fun ctx() = AppState.app
    private fun toast(t: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx(), t, Toast.LENGTH_LONG).show() }
    private fun clean(n: String) = n.replace(Regex("""[^\w.\-]"""), "_").take(80)

    fun saveCsv(name: String, text: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = clean(name.removeSuffix(".csv")).ifEmpty { "dados" }
        // BOM para o Excel/Planilhas reconhecerem acentos
        Downloader.saveBytes(ctx(), "$base-$stamp.csv", ("\uFEFF" + text).toByteArray(Charsets.UTF_8), "text/csv")
    }

    fun saveScript(name: String, code: String) {
        val n = clean(name).ifEmpty { "gravacao.js" }
        val c = runCatching { ScriptStore.importStream(n, code.byteInputStream()) }.getOrDefault(0)
        ProxyBridge.pushScripts()
        toast(if (c > 0) "Script salvo: $n (vale nas próximas páginas)" else "Falha ao salvar o script")
    }

    fun copy(text: String) {
        val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("floatbrowser", text))
        toast("Copiado")
    }
}
