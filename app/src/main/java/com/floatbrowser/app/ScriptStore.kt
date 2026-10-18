package com.floatbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Scripts de usuário (.js ou .zip com .js) guardados dentro do app. */
object ScriptStore {
    private val dir get() = File(AppState.app.filesDir, "scripts").apply { mkdirs() }
    private val sp get() = AppState.app.getSharedPreferences("fb", Context.MODE_PRIVATE)

    fun list(): List<String> =
        dir.listFiles { f -> f.extension == "js" }?.map { it.name }?.sorted().orEmpty()

    fun enabled(n: String) = n !in (sp.getStringSet("off", emptySet()) ?: emptySet())

    fun toggle(n: String) {
        val s = (sp.getStringSet("off", emptySet()) ?: emptySet()).toMutableSet()
        if (!s.add(n)) s.remove(n)
        sp.edit().putStringSet("off", s).apply()
    }

    fun remove(n: String) { File(dir, n).delete() }

    private fun clean(n: String) = n.replace(Regex("""[^\w.\-]"""), "_")

    fun saveGenerated(name: String, text: String) {
        File(dir, clean(if (name.endsWith(".js", true)) name else "$name.js")).writeText(text)
    }

    fun importStream(name: String, input: InputStream): Int {
        if (name.endsWith(".zip", true) || name.endsWith(".xpi", true)) {
            var c = 0
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".js", true)) {
                        val b = z.readBytes()
                        if (b.size < 2_000_000) { File(dir, clean(File(e.name).name)).writeBytes(b); c++ }
                    }
                    e = z.nextEntry
                }
            }
            return c
        }
        val n = clean(if (name.endsWith(".js", true)) name else "$name.js")
        File(dir, n).writeBytes(input.readBytes())
        return 1
    }

    fun payload(): JSONObject {
        val arr = JSONArray()
        val rx = Regex("""(?m)^\s*//\s*@match\s+(\S+)""")
        list().filter { enabled(it) }.forEach { n ->
            val code = File(dir, n).readText()
            val m = rx.findAll(code).map { it.groupValues[1] }.toList().ifEmpty { listOf("<all_urls>") }
            arr.put(JSONObject().put("name", n).put("matches", JSONArray(m)).put("code", code))
        }
        return JSONObject().put("type", "scripts").put("list", arr)
    }
}
