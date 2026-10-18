package com.floatbrowser.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class ProxyEntry(
    val host: String, val port: Int, val type: String = "http",
    val user: String? = null, val pass: String? = null,
    val pingMs: Long = Long.MAX_VALUE, val exitIp: String? = null
) {
    fun key() = "$type://$host:$port"
    /** Identidade real do proxy: o IP de saída medido (ou o host, se ainda não medido). */
    fun ipKey() = exitIp ?: host
    fun toJavaProxy() = Proxy(
        if (type.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(host, port)
    )
}

class ProxyPool(
    private val sources: () -> List<String> = { DEFAULT_SOURCES },
    private val maxPool: Int = 30,
    private val maxCandidates: Int = 300,
    private val parallel: Int = 30,
    private val timeoutMs: Long = 3000
) {
    companion object {
        val DEFAULT_SOURCES = listOf(
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt",
            "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt",
            "https://raw.githubusercontent.com/MuRongPIG/Proxy-Master/main/http.txt"
        )
        private val LINE = Regex("""^(?:(\w+)://)?(?:([^:@]+):([^@]+)@)?([\w.\-]+):(\d{2,5})$""")
        private val IP = Regex("""^[0-9a-fA-F:.]{7,45}$""")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    // download das listas precisa de mais tempo que o teste de cada proxy
    private val fetcher = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    // troca de referência atômica: leitores nunca veem a lista vazia durante o refresh
    @Volatile private var healthy = CopyOnWriteArrayList<ProxyEntry>()
    val manual = CopyOnWriteArrayList<ProxyEntry>()
    private val inUse = ConcurrentHashMap<String, ProxyEntry>()
    private val quarantined = ConcurrentHashMap<String, Long>()

    val size get() = healthy.size + manual.size

    fun parse(line: String): ProxyEntry? {
        val m = LINE.matchEntire(line.trim()) ?: return null
        val port = m.groupValues[5].toInt()
        if (port !in 1..65535) return null
        return ProxyEntry(m.groupValues[4], port, m.groupValues[1].ifEmpty { "http" }.lowercase(),
            m.groupValues[2].ifEmpty { null }, m.groupValues[3].ifEmpty { null })
    }

    private val refreshMutex = Mutex()
    @Volatile private var lastRefresh = 0L

    /** Atualização única e espaçada: evita a "tempestade" de testes que deixava a internet lenta. */
    suspend fun refresh(force: Boolean = false, onProgress: (String) -> Unit = {}): Int {
        val gap = if (size == 0) 45_000L else 180_000L
        if (!force && System.currentTimeMillis() - lastRefresh < gap) return size
        if (!refreshMutex.tryLock()) return size
        try {
            lastRefresh = System.currentTimeMillis()
            return doRefresh(onProgress)
        } finally { refreshMutex.unlock() }
    }

    private suspend fun doRefresh(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { onProgress("Baixando listas…") }
        val raw = coroutineScope {
            sources().map { url -> async { runCatching { fetch(url) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }
        val candidates = raw.mapNotNull(::parse).distinctBy { it.key() }
            .filter { !isQuarantined(it) }.shuffled().take(if (Prefs.isEco()) 100 else maxCandidates)
        withContext(Dispatchers.Main) { onProgress("Testando ${candidates.size} proxys…") }

        val sem = Semaphore(parallel)
        val ok = coroutineScope {
            candidates.map { p -> async { sem.withPermit { test(p) } } }.awaitAll()
        }.filterNotNull().sortedBy { it.pingMs }
            .distinctBy { it.ipKey() }          // nunca dois proxys com o mesmo IP de saída
            .take(maxPool)

        healthy = CopyOnWriteArrayList(ok)
        withContext(Dispatchers.Main) { onProgress("${ok.size} proxys funcionando") }
        ok.size
    }

    private fun fetch(url: String): List<String> =
        fetcher.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.lineSequence()?.toList().orEmpty() else emptyList()
        }

    /** Faz uma requisição HTTPS real pelo proxy e mede o IP de saída. Resposta estranha = descartado. */
    suspend fun test(p: ProxyEntry, ms: Long = timeoutMs): ProxyEntry? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(ms + 1000) {
            runCatching {
                val client = http.newBuilder().proxy(p.toJavaProxy())
                    .connectTimeout(ms, TimeUnit.MILLISECONDS)
                    .readTimeout(ms, TimeUnit.MILLISECONDS)
                    .callTimeout(ms + 500, TimeUnit.MILLISECONDS)
                    .apply {
                        if (p.user != null) proxyAuthenticator { _, resp ->
                            resp.request.newBuilder()
                                .header("Proxy-Authorization", Credentials.basic(p.user, p.pass ?: "")).build()
                        }
                    }.build()
                val t = System.currentTimeMillis()
                client.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use { r ->
                    val ip = if (r.isSuccessful) r.body?.string()?.trim() else null
                    if (ip != null && IP.matches(ip))
                        p.copy(pingMs = System.currentTimeMillis() - t, exitIp = ip) else null
                }
            }.getOrNull()
        }
    }

    private fun isQuarantined(p: ProxyEntry) =
        (quarantined[p.key()] ?: 0L) > System.currentTimeMillis()

    private fun quarantine(p: ProxyEntry, minutes: Long) {
        quarantined[p.key()] = System.currentTimeMillis() + minutes * 60_000
        healthy.removeAll { it.key() == p.key() }
    }

    private fun takenExcept(windowId: String) =
        inUse.filterKeys { it != windowId }.values.map { it.ipKey() }.toSet()

    /** Escolha rápida (sem teste) para abrir a janela. */
    @Synchronized
    fun assignTo(windowId: String): ProxyEntry? {
        inUse.remove(windowId)
        val taken = takenExcept(windowId)
        val p = (manual + healthy).firstOrNull { it.ipKey() !in taken && !isQuarantined(it) }
        if (p != null) inUse[windowId] = p
        return p
    }

    /** Perfil com proxy fixo: usa exatamente este proxy, salvo se outra janela já usa o mesmo IP. */
    @Synchronized
    fun assignExact(windowId: String, p: ProxyEntry): Boolean {
        inUse.remove(windowId)
        if (p.ipKey() in takenExcept(windowId) || isQuarantined(p)) return false
        inUse[windowId] = p
        return true
    }

    @Synchronized
    private fun claim(windowId: String, p: ProxyEntry): Boolean {
        if (inUse.filterKeys { it != windowId }.values.any { it.ipKey() == p.ipKey() }) return false
        inUse[windowId] = p
        return true
    }

    /** Descarta o proxy atual da janela (quarentena). */
    @Synchronized
    fun burn(windowId: String, minutes: Long = 10) {
        inUse.remove(windowId)?.let { quarantine(it, minutes) }
    }

    /** Troca rápida: testa até 6 candidatos em paralelo e entrega o mais rápido que respondeu. */
    suspend fun nextVerified(windowId: String): ProxyEntry? = withContext(Dispatchers.IO) {
        repeat(2) {
            val taken = takenExcept(windowId)
            val cands = (manual + healthy)
                .filter { it.ipKey() !in taken && !isQuarantined(it) }.take(if (Prefs.isEco()) 3 else 6)
            if (cands.isEmpty()) return@withContext null
            val res = coroutineScope { cands.map { c -> async { c to test(c, 2500) } }.awaitAll() }
            res.filter { it.second == null }.forEach { quarantine(it.first, 10) }
            for (b in res.mapNotNull { it.second }.sortedBy { it.pingMs })
                if (claim(windowId, b)) return@withContext b
        }
        null
    }

    @Synchronized fun release(windowId: String) { inUse.remove(windowId) }
    fun current(windowId: String) = inUse[windowId]
    fun needsRefresh() = healthy.size < 5
}
