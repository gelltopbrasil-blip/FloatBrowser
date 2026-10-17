package com.floatbrowser.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Opcional: faz SÓ este app usar a rede do operador/Wi-Fi direto, sem passar pelo VPN do celular. */
object NetBind {
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var bound: Network? = null

    fun apply(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        stop(cm)
        if (!Prefs.bypassVpn) return
        // o pedido padrão já exclui redes de VPN
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bound = network
                runCatching { cm.bindProcessToNetwork(network) }
            }
            override fun onLost(network: Network) {
                if (network == bound) { bound = null; runCatching { cm.bindProcessToNetwork(null) } }
            }
        }
        cb = c
        runCatching { cm.requestNetwork(req, c) }
    }

    private fun stop(cm: ConnectivityManager) {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null; bound = null
        runCatching { cm.bindProcessToNetwork(null) }
    }
}
