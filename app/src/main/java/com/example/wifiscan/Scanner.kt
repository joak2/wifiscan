package com.example.wifiscan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Device(val ip: String) {
    @Volatile var hostname: String = ""
    val friendly: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val services: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val ports: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile var isSelf = false
    @Volatile var isGateway = false
    @Volatile var type: String = "Inconnu"
}

class ScanResult(val devices: List<Device>, val subnet: String, val error: String?)

class Scanner(private val ctx: Context) {

    private val quickPorts = intArrayOf(80, 443, 445, 22, 53, 8009, 139, 8080)
    private val morePorts = intArrayOf(21, 23, 554, 631, 1883, 3389, 5000, 7000, 8008, 8060, 8443, 9100, 62078)
    private val mdnsTypes = listOf(
        "_http._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_companion-link._tcp",
        "_ipp._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_hap._tcp", "_smb._tcp",
        "_workstation._tcp", "_device-info._tcp", "_ssh._tcp", "_spotify-connect._tcp",
        "_androidtvremote2._tcp"
    )

    private fun fail(msg: String) = ScanResult(emptyList(), "", msg)

    fun scan(onProgress: (done: Int, total: Int) -> Unit): ScanResult {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return fail("Pas de réseau actif.")
        val caps = cm.getNetworkCapabilities(net)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return fail("Connecte le téléphone à un réseau Wi-Fi.")
        }
        val lp = cm.getLinkProperties(net) ?: return fail("Infos réseau indisponibles.")
        val myAddr = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address } as? Inet4Address
            ?: return fail("Pas d'adresse IPv4 sur ce Wi-Fi.")
        val myIp = myAddr.hostAddress ?: return fail("Adresse IP illisible.")
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val b = myAddr.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}"

        val devices = ConcurrentHashMap<String, Device>()
        devices[myIp] = Device(myIp).also {
            it.isSelf = true
            it.hostname = Build.MODEL
        }

        val mdns = Thread { discoverMdns(devices, 12000) }
        mdns.start()

        val pool = Executors.newFixedThreadPool(48)
        val done = AtomicInteger(0)
        val tasks = (1..254).map { i ->
            Callable {
                probeHost("$prefix.$i", devices, myIp)
                onProgress(done.incrementAndGet(), 254)
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        mdns.join()

        if (gw != null) devices.computeIfAbsent(gw) { Device(it) }.isGateway = true

        val list = devices.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        list.forEach { it.type = classify(it) }
        return ScanResult(list, prefix, null)
    }

    // 0 = rien, 1 = port ouvert, 2 = connexion refusée (l'hôte est vivant)
    private fun tryPort(ip: String, port: Int, timeout: Int): Int {
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
            1
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", ignoreCase = true) == true) 2 else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun probeHost(ip: String, devices: ConcurrentHashMap<String, Device>, myIp: String) {
        if (ip == myIp) return
        val open = HashSet<Int>()
        var alive = false
        for (p in quickPorts) {
            when (tryPort(ip, p, 300)) {
                1 -> { alive = true; open.add(p) }
                2 -> alive = true
            }
        }
        if (!alive) {
            alive = try { InetAddress.getByName(ip).isReachable(300) } catch (e: Exception) { false }
        }
        if (!alive) return
        for (p in morePorts) {
            if (tryPort(ip, p, 200) == 1) open.add(p)
        }
        val h = try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { ip }
        val d = devices.computeIfAbsent(ip) { Device(it) }
        d.ports.addAll(open)
        if (h != ip) d.hostname = h
    }

    @Suppress("DEPRECATION")
    private fun discoverMdns(devices: ConcurrentHashMap<String, Device>, durationMs: Long) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val queue = LinkedBlockingQueue<NsdServiceInfo>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        for (t in mdnsTypes) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) { queue.add(serviceInfo) }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
            try {
                nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l)
                listeners.add(l)
            } catch (e: Exception) {
            }
        }
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            val s = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val latch = CountDownLatch(1)
            try {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        latch.countDown()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val h = serviceInfo.host
                        if (h is Inet4Address) {
                            val ip = h.hostAddress ?: ""
                            if (ip.isNotEmpty()) {
                                val d = devices.computeIfAbsent(ip) { Device(it) }
                                d.services.add(serviceInfo.serviceType.trim('.').lowercase())
                                d.friendly.add(serviceInfo.serviceName)
                            }
                        }
                        latch.countDown()
                    }
                })
                latch.await(1500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
            }
        }
        for (l in listeners) {
            try { nsd.stopServiceDiscovery(l) } catch (e: Exception) {}
        }
    }

    private fun classify(d: Device): String {
        if (d.isSelf) return "Ce téléphone"
        if (d.isGateway) return "Routeur / box internet"
        val s = d.services.joinToString(" ")
        val n = (d.hostname + " " + d.friendly.joinToString(" ")).lowercase()
        val p = d.ports
        return when {
            "ipp" in s || "printer" in s || "pdl-datastream" in s || 9100 in p || 631 in p -> "Imprimante"
            "googlecast" in s || "androidtvremote" in s -> "Chromecast / TV Android / enceinte Google"
            "airplay" in s || "raop" in s || "companion-link" in s || 62078 in p ||
                    listOf("iphone", "ipad", "macbook", "apple").any { it in n } ->
                "Appareil Apple (iPhone, iPad, Mac, Apple TV)"
            "hap" in s -> "Domotique HomeKit"
            8060 in p -> "Roku"
            554 in p -> "Caméra IP (RTSP)"
            listOf("android", "galaxy", "pixel", "redmi", "xiaomi", "oneplus", "huawei", "oppo").any { it in n } ->
                "Smartphone / tablette Android"
            listOf("esp", "tasmota", "shelly").any { it in n } -> "Objet connecté (ESP)"
            listOf("raspberry", "raspi").any { it in n } -> "Raspberry Pi"
            listOf("sonos", "echo", "alexa").any { it in n } -> "Enceinte connectée"
            listOf("tv", "bravia", "samsung", "lg").any { it in n } -> "TV"
            listOf("print", "epson", "brother", "canon", "hp").any { it in n } -> "Imprimante"
            listOf("desktop", "laptop", "pc").any { it in n } -> "Ordinateur"
            "smb" in s || 445 in p || 139 in p -> "PC ou NAS (partage SMB)"
            22 in p || "ssh" in s -> "Serveur Linux / SSH (PC, NAS, Raspberry)"
            80 in p || 443 in p || 8080 in p || 8443 in p || "http" in s -> "Appareil avec interface web"
            else -> "Inconnu"
        }
    }
}
