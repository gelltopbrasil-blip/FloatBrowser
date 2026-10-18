package com.floatbrowser.app

/** Gera um script de automação completo a partir dos passos gravados. */
object ScriptTemplate {
    fun build(match: String, steps: String): String {
        val t = AppState.app.assets.open("automacao.tpl.js").bufferedReader().use { it.readText() }
        return t.replace("__MATCH__", match).replace("__PASSOS__", steps)
    }
}
