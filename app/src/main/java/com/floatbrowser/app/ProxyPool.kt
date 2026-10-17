package com.floatbrowser.app

import kotlinx.coroutines.*
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
    val user: String? = null, val pass: String? = null, val pingMs: Long = Long.MAX_VALUE
) {
    fun key() = "$type://$host:$port"
    fun toJavaProxy() = Proxy(
        if (type.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(host, port)
    )
}

class ProxyPool(
    private val sources: () -> List<String> = { DEFAULT_SOURCES },
    private val maxPool: Int = 30,
    private val maxCandidates: Int = 600,
    private val parallel: Int = 80,
    private val timeoutMs: Long = 4000
) {
    companion object {
        val DEFAULT_SOURCES = listOf(
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt"
        )
        private val LINE = Regex("""^(?:(\w+)://)?(?:([^:@]+):([^@]+)@)?([\w.\-]+):(\d{2,5})$""")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    private val healthy = CopyOnWriteArrayList<ProxyEntry>()
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

    suspend fun refresh(onProgress: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { onProgress("Baixando listas…") }
        val raw = coroutineScope {
            sources().map { url -> async { runCatching { fetch(url) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }
        val candidates = raw.mapNotNull(::parse).distinctBy { it.key() }
            .filter { !isQuarantined(it) }.shuffled().take(maxCandidates)
        withContext(Dispatchers.Main) { onProgress("Testando ${candidates.size} proxys…") }

        val sem = Semaphore(parallel)
        val ok = coroutineScope {
            candidates.map { p -> async { sem.withPermit { test(p) } } }.awaitAll()
        }.filterNotNull().sortedBy { it.pingMs }.take(maxPool)

        healthy.clear(); healthy.addAll(ok)
        withContext(Dispatchers.Main) { onProgress("${ok.size} proxys funcionando") }
        ok.size
    }

    private fun fetch(url: String): List<String> =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.lineSequence()?.toList().orEmpty() else emptyList()
        }

    suspend fun test(p: ProxyEntry): ProxyEntry? = withTimeoutOrNull(timeoutMs + 500) {
        runCatching {
            val client = http.newBuilder().proxy(p.toJavaProxy()).apply {
                if (p.user != null) proxyAuthenticator { _, resp ->
                    resp.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(p.user, p.pass ?: "")).build()
                }
            }.build()
            val t = System.currentTimeMillis()
            client.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use {
                if (it.isSuccessful) p.copy(pingMs = System.currentTimeMillis() - t) else null
            }
        }.getOrNull()
    }

    private fun isQuarantined(p: ProxyEntry) =
        (quarantined[p.key()] ?: 0L) > System.currentTimeMillis()

    @Synchronized
    fun assignTo(windowId: String): ProxyEntry? {
        inUse.remove(windowId)
        val taken = inUse.values.map { it.key() }.toSet()
        val p = (manual + healthy).firstOrNull { it.key() !in taken && !isQuarantined(it) }
        if (p != null) inUse[windowId] = p
        return p
    }

    @Synchronized fun release(windowId: String) { inUse.remove(windowId) }

    @Synchronized
    fun markBad(windowId: String, minutes: Long = 10): ProxyEntry? {
        inUse[windowId]?.let {
            quarantined[it.key()] = System.currentTimeMillis() + minutes * 60_000
            healthy.removeAll { h -> h.key() == it.key() }
        }
        return assignTo(windowId)
    }

    fun current(windowId: String) = inUse[windowId]
    fun needsRefresh() = healthy.size < 5
}
