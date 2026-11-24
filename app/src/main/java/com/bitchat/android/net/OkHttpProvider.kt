package com.bitchat.android.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized OkHttp provider. MoshGrid removed Tor, so every route is direct.
 */
object OkHttpProvider {
    enum class Route {
        DIRECT,
        TOR
    }

    data class RoutedClient(
        val client: OkHttpClient,
        val route: Route
    )

    private val httpClientRef = AtomicReference<RoutedClient?>(null)
    private val wsClientRef = AtomicReference<OkHttpClient?>(null)
    private val clientLock = Any()

    fun reset() {
        synchronized(clientLock) {
            httpClientRef.set(null)
            wsClientRef.set(null)
        }
    }

    fun httpClient(): OkHttpClient = routedHttpClient().client

    /**
     * Returns the client and the route it was actually built with as one snapshot.
     *
     * The selected Tor mode can change while an existing client is still cached. Consumers that
     * key cooldowns by network identity must use this value rather than re-reading the preference.
     */
    fun routedHttpClient(): RoutedClient {
        httpClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            httpClientRef.get() ?: run {
                val (builder, route) = baseBuilderForCurrentProxy()
                val client = builder
                    .callTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                RoutedClient(client, route).also(httpClientRef::set)
            }
        }
    }

    fun webSocketClient(): OkHttpClient {
        wsClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            wsClientRef.get() ?: baseBuilderForCurrentProxy().first
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                // MoshGrid: on jammed mobile data a socket can look open long after it died. A ping
                // every 20 s closes it, so the relay shows as down and Help says "waiting for signal".
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
                .also(wsClientRef::set)
        }
    }

    /** MoshGrid has no Tor: every connection is direct. */
    private fun baseBuilderForCurrentProxy(): Pair<OkHttpClient.Builder, Route> = OkHttpClient.Builder() to Route.DIRECT
}
