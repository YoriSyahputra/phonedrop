package com.phonedrop.v2.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Discovers PhoneDrop v2 desktop hosts over Wi-Fi LAN using Android NsdManager (_phonedrop._tcp.).
 */
class MdnsDiscoveryManager(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    suspend fun discoverFirst(timeoutMs: Long = 3000): Pair<String, Int>? {
        val mgr = nsdManager ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var completed = false
                lateinit var discoveryListener: NsdManager.DiscoveryListener

                fun finish(res: Pair<String, Int>?) {
                    if (!completed) {
                        completed = true
                        try {
                            mgr.stopServiceDiscovery(discoveryListener)
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) cont.resume(res)
                    }
                }

                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {}
                    override fun onServiceFound(service: NsdServiceInfo) {
                        if (!service.serviceType.contains("_phonedrop")) return
                        @Suppress("DEPRECATION")
                        mgr.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                finish(host to serviceInfo.port)
                            }
                        })
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }

                try {
                    mgr.discoverServices("_phonedrop._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (_: Exception) {
                    finish(null)
                }

                cont.invokeOnCancellation {
                    try {
                        mgr.stopServiceDiscovery(discoveryListener)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
