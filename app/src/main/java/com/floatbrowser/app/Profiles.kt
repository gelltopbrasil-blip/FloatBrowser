package com.floatbrowser.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Perfil = site inicial + proxy (auto, direto ou ip:porta) + cookies próprios (um por nome). */
data class Profile(
    val name: String, val url: String, val proxy: String,
    /** Monitor de página guardado no perfil (vazio = sem monitor): seletor, segundos e texto a procurar. */
    val monSel: String = "", val monEvery: Int = 60, val monText: String = ""
)

object ProfileStore {
    fun list(): List<Profile> {
        val a = runCatching { JSONArray(Prefs.profilesJson) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Profile(o.getString("name"), o.optString("url"), o.optString("proxy", "auto"),
                o.optString("monSel", ""), o.optInt("monEvery", 60), o.optString("monText", ""))
        }
    }
    fun get(n: String) = list().firstOrNull { it.name == n }
    /** Id estável (só letras, números e _) do baú de cookies do perfil: cada nome tem o seu. */
    fun contextId(name: String): String {
        val n = name.trim()
        return "ctx_p_" + n.replace(Regex("[^A-Za-z0-9]"), "_") + "_" + Integer.toHexString(n.hashCode())
    }
    fun save(p: Profile) = write(list().filter { it.name != p.name } + p)
    fun remove(n: String) = write(list().filter { it.name != n })
    /** Guarda (ou limpa, com sel vazio) o monitor do perfil, mantendo a ordem da lista. */
    fun setMonitor(name: String, sel: String, every: Int, text: String) {
        val p = get(name) ?: return
        if (p.monSel == sel && p.monEvery == every && p.monText == text) return
        write(list().map { if (it.name == name) it.copy(monSel = sel, monEvery = every, monText = text) else it })
    }
    private fun write(l: List<Profile>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("name", it.name).put("url", it.url).put("proxy", it.proxy)
                .put("monSel", it.monSel).put("monEvery", it.monEvery).put("monText", it.monText))
        }
        Prefs.profilesJson = a.toString()
    }
}

data class Schedule(val id: String, val profile: String, val spec: String, val enabled: Boolean, val last: Long)

/** Agendamentos: "08:30" (todo dia) ou "30m" / "2h" (a cada tanto). Só rodam com o serviço do app ativo. */
object Schedules {
    private val DAILY = Regex("""(\d{1,2}):(\d{2})""")
    private val EVERY = Regex("""(\d+)\s*(m|h)""")

    fun valid(spec: String): Boolean {
        val s = spec.trim().lowercase()
        val d = DAILY.matchEntire(s)
        if (d != null) return d.groupValues[1].toInt() in 0..23 && d.groupValues[2].toInt() in 0..59
        val e = EVERY.matchEntire(s)
        return e != null && e.groupValues[1].toLong() > 0
    }

    fun list(): List<Schedule> {
        val a = runCatching { JSONArray(Prefs.schedulesJson) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Schedule(o.getString("id"), o.getString("profile"), o.getString("spec"),
                o.optBoolean("enabled", true), o.optLong("last", 0))
        }
    }
    private fun write(l: List<Schedule>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("profile", it.profile).put("spec", it.spec)
                .put("enabled", it.enabled).put("last", it.last))
        }
        Prefs.schedulesJson = a.toString()
    }
    fun add(profile: String, spec: String) =
        write(list() + Schedule(System.currentTimeMillis().toString(), profile, spec.trim().lowercase(), true, System.currentTimeMillis()))
    fun toggle(id: String) = write(list().map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    fun remove(id: String) = write(list().filter { it.id != id })

    internal fun isDue(s: Schedule, now: Long): Boolean {
        val spec = s.spec.trim().lowercase()
        DAILY.matchEntire(spec)?.let { m ->
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt())
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return now >= c.timeInMillis && s.last < c.timeInMillis
        }
        EVERY.matchEntire(spec)?.let { m ->
            val mins = m.groupValues[1].toLong() * (if (m.groupValues[2] == "h") 60 else 1)
            return now - s.last >= mins * 60_000
        }
        return false
    }

    /** Devolve os agendamentos que venceram e marca como executados. */
    fun due(now: Long): List<Schedule> {
        val out = mutableListOf<Schedule>()
        val upd = list().map { s ->
            if (s.enabled && isDue(s, now)) { out.add(s); s.copy(last = now) } else s
        }
        if (out.isNotEmpty()) write(upd)
        return out
    }
}
