package com.qi.phoneaudiobridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * mDNS/Bonjour discovery (v2.1): browses the canonical SoundRelay service
 * type (Protocol.MDNS_SERVICE_TYPE, `_airbridge._udp`) via the framework
 * NsdManager — no extra permission and no third-party library needed.
 * Sightings (service name + host IP) are merged by the engine with the
 * LAN-beacon and BLE transports into the one nearby list; audio and
 * pairing still run over the normal UDP channel to that IP.
 *
 * Defensive by design (same contract as BleDiscovery): any failure just
 * degrades statusText, never crashes the engine. Resolves are serialised
 * (pre-API 34 NsdManager resolves one service at a time reliably).
 */
class MdnsDiscovery(
    private val context: Context,
    private val onSighting: (name: String, ip: String) -> Unit
) {
    @Volatile var statusText: String = context.getString(R.string.mdns_status_not_started)
        private set
    @Volatile var browsing: Boolean = false
        private set

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false

    fun start() {
        if (browsing) return
        val mgr = try { context.getSystemService(Context.NSD_SERVICE) as? NsdManager } catch (_: Exception) { null }
        if (mgr == null) { statusText = context.getString(R.string.mdns_unsupported); return }
        nsd = mgr
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                browsing = true
                statusText = context.getString(R.string.mdns_on)
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                resolveQueue.add(service)
                drainResolveQueue()
            }
            override fun onServiceLost(service: NsdServiceInfo) { /* staleness handled by the engine's 5 s expiry */ }
            override fun onDiscoveryStopped(serviceType: String) { browsing = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                browsing = false
                statusText = context.getString(R.string.mdns_start_failed_code, errorCode)
                Log.w("MdnsDiscovery", "start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { browsing = false }
        }
        listener = l
        try {
            mgr.discoverServices(Protocol.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            statusText = context.getString(R.string.mdns_start_failed)
            Log.w("MdnsDiscovery", "discoverServices failed: ${e.message}")
        }
    }

    private fun drainResolveQueue() {
        val mgr = nsd ?: return
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            mgr.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(service: NsdServiceInfo) {
                    resolving = false
                    val ip = service.host?.hostAddress
                    if (ip != null) onSighting(service.serviceName ?: "", ip)
                    drainResolveQueue()
                }
                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    drainResolveQueue()
                }
            })
        } catch (e: Exception) {
            resolving = false
            Log.w("MdnsDiscovery", "resolve failed: ${e.message}")
        }
    }

    fun stop() {
        if (!browsing) return
        try { listener?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        browsing = false
        listener = null
        resolveQueue.clear()
        statusText = context.getString(R.string.mdns_stopped)
    }
}
