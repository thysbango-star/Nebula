package com.nebula.assistant.beam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Finds other Nebula devices on the same Wi-Fi with Android's network service discovery (mDNS, `_nebulabeam._tcp`),
 * and makes this device findable while Beam is open or the user chose to be visible. Only devices that actually
 * answer appear — nothing is invented.
 */
class BeamDiscovery(private val ctx: Context) {
    companion object { const val TYPE = "_nebulabeam._tcp." }
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val _devices = MutableStateFlow<List<BeamDevice>>(emptyList())
    val devices: StateFlow<List<BeamDevice>> = _devices
    private var discovering: NsdManager.DiscoveryListener? = null
    private var registered: NsdManager.RegistrationListener? = null
    private var myName = ""
    private var lock: WifiManager.MulticastLock? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun deviceName(): String = runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { Settings.Secure.getString(ctx.contentResolver, "bluetooth_name") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private fun deviceType() = if (Build.MANUFACTURER.equals("samsung", true)) "samsung" else "android"

    fun wifiOn(): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    @Synchronized fun startScan() {
        if (discovering != null) return
        lock = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("nebula-beam").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        _devices.value = emptyList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { discovering = null }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName != myName) enqueue(s) }
            override fun onServiceLost(s: NsdServiceInfo) { _devices.value = _devices.value.filter { it.id != s.serviceName } }
        }
        discovering = l
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { discovering = null }
    }

    @Synchronized fun stopScan() {
        discovering?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovering = null; resolveQueue.clear(); resolving = false
        if (registered == null) runCatching { lock?.release() }
    }

    private fun enqueue(s: NsdServiceInfo) = synchronized(this) { resolveQueue.addLast(s); if (!resolving) next() }

    @Suppress("DEPRECATION")
    private fun next() {
        val s = resolveQueue.removeFirstOrNull() ?: run { resolving = false; return }
        resolving = true
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, e: Int) { synchronized(this@BeamDiscovery) { next() } }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    if (host != null) {
                        val attr = si.attributes
                        val name = attr["name"]?.let { String(it) } ?: si.serviceName.substringBefore(" #")
                        val type = attr["type"]?.let { String(it) } ?: "android"
                        val d = BeamDevice(si.serviceName, name, type, host, si.port)
                        _devices.value = (_devices.value.filter { it.id != d.id } + d).sortedBy { it.name }
                    }
                    synchronized(this@BeamDiscovery) { next() }
                }
            })
        }.onFailure { synchronized(this) { next() } }
    }

    /** Advertise this device (while visible). The receiver must already be listening on [port]. */
    @Synchronized fun advertise(port: Int) {
        if (registered != null) return
        lock = lock ?: runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("nebula-beam").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val name = deviceName()
        myName = "$name #${(1000..9999).random()}"
        val info = NsdServiceInfo().apply {
            serviceName = myName; serviceType = TYPE; this.port = port
            setAttribute("name", name); setAttribute("type", deviceType()); setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { myName = si.serviceName }
            override fun onRegistrationFailed(si: NsdServiceInfo, e: Int) { registered = null }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, e: Int) {}
        }
        registered = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { registered = null }
    }

    @Synchronized fun hide() {
        registered?.let { runCatching { nsd.unregisterService(it) } }
        registered = null
        if (discovering == null) runCatching { lock?.release() }
    }
}
