package com.rishi.aegis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Watch what happens on the phone's own Wi-Fi hotspot, without root.
 *
 * When tethering is on the phone is the router: the upstream (cellular) stays the "active network",
 * while the SoftAP lives on a separate interface (ap0 / wlan1 / swlan0 …) holding a private gateway
 * address like 192.168.x.1. We find that interface by enumerating [NetworkInterface] and picking the
 * site-local IPv4 that isn't the upstream. From there:
 *   - **connected devices** are discovered by sweeping the AP subnet (reusing [NetEngine.pingSweep]),
 *     then enriched with the client's MAC + vendor from the ARP table when the OS exposes it;
 *   - **data volume** through the hotspot comes from the interface's byte counters in /proc/net/dev.
 *
 * What is NOT possible without root: reading the *content* of a connected device's traffic. That
 * forwarded traffic is routed below the app sandbox; even our own VpnService only sees this phone.
 */
object Hotspot {

    data class ApInfo(
        val iface: String,
        val gatewayIp: String,     // this phone's address on the AP (the clients' gateway)
        val prefix: Int,
        val rxBytes: Long,         // total received on the AP interface
        val txBytes: Long,         // total transmitted
    ) {
        val subnetLabel: String get() = "$gatewayIp/$prefix"
    }

    data class Client(
        val ip: String,
        val mac: String?,
        val vendor: String?,       // OUI vendor, "Randomized / private MAC", or null when unknown
        val hostname: String?,
    )

    /** Detect an active SoftAP interface, or null if the hotspot appears to be off. */
    fun detectAp(): ApInfo? {
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        // Prefer interfaces whose name looks like a SoftAP; fall back to any site-local IPv4 iface.
        val candidates = ifaces.filter { it.isUp && !it.isLoopback }
        val ranked = candidates.sortedByDescending { looksLikeAp(it.name) }
        for (nif in ranked) {
            for (ia in nif.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    val (rx, tx) = interfaceBytes(nif.name)
                    return ApInfo(nif.name, a.hostAddress ?: "?", ia.networkPrefixLength.toInt().coerceIn(1, 32), rx, tx)
                }
            }
        }
        return null
    }

    private fun looksLikeAp(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("ap") || n.contains("swlan") || n == "wlan1" ||
            n.contains("softap") || n.contains("tether") || n.contains("usb")
    }

    /** Sweep the AP subnet and list connected clients (excluding this phone). */
    suspend fun scanClients(
        ap: ApInfo,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Client> = withContext(Dispatchers.IO) {
        val hosts = NetEngine.pingSweep(ap.gatewayIp, ap.prefix, timeoutMs = 500, onProgress = onProgress)
        val arp = arpTable()
        hosts
            .filter { it.ip != ap.gatewayIp }
            .map { h ->
                val mac = arp[h.ip]
                Client(h.ip, mac, mac?.let { vendorForMac(it) }, h.hostname)
            }
    }

    // ---- /proc/net/dev : per-interface byte counters ----

    fun interfaceBytes(iface: String): Pair<Long, Long> {
        return try {
            File("/proc/net/dev").readLines().forEach { line ->
                val trimmed = line.trim()
                val colon = trimmed.indexOf(':')
                if (colon > 0 && trimmed.substring(0, colon).trim() == iface) {
                    val nums = trimmed.substring(colon + 1).trim().split(Regex("\\s+"))
                    val rx = nums.getOrNull(0)?.toLongOrNull() ?: 0L
                    val tx = nums.getOrNull(8)?.toLongOrNull() ?: 0L
                    return rx to tx
                }
            }
            0L to 0L
        } catch (_: Exception) {
            0L to 0L
        }
    }

    // ---- /proc/net/arp : IP -> MAC (best effort; may be empty on newer Android) ----

    private fun arpTable(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4) {
                    val ip = cols[0]
                    val mac = cols[3]
                    if (mac != "00:00:00:00:00:00" && mac.count { it == ':' } == 5) out[ip] = mac.uppercase()
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---- MAC vendor ----

    /** Vendor from the OUI, or a note that the MAC is randomized (modern phones default to this). */
    fun vendorForMac(mac: String): String {
        val hex = mac.uppercase().replace("-", ":")
        val firstOctet = hex.take(2).toIntOrNull(16)
        if (firstOctet != null && (firstOctet and 0x02) != 0) return "Randomized / private MAC"
        val oui = hex.take(8) // "AA:BB:CC"
        return OUI[oui] ?: "Unknown vendor"
    }

    // A curated slice of the IEEE OUI registry — the vendors most likely to appear on a personal
    // hotspot (phones, laptops, consoles, IoT). Not exhaustive; unknown prefixes say so.
    private val OUI: Map<String, String> = mapOf(
        "3C:5A:B4" to "Google", "F4:F5:E8" to "Google", "94:EB:2C" to "Google",
        "DC:A6:32" to "Raspberry Pi", "B8:27:EB" to "Raspberry Pi", "E4:5F:01" to "Raspberry Pi",
        "F0:18:98" to "Apple", "A4:83:E7" to "Apple", "3C:15:C2" to "Apple", "AC:DE:48" to "Apple",
        "F8:FF:C2" to "Apple", "88:66:5A" to "Apple", "D0:81:7A" to "Apple", "6C:8D:C1" to "Apple",
        "00:1A:11" to "Google", "AC:37:43" to "HTC",
        "C8:3A:35" to "Samsung", "78:1F:DB" to "Samsung", "34:BE:00" to "Samsung", "5C:0A:5B" to "Samsung",
        "F0:25:B7" to "Samsung", "8C:77:12" to "Samsung", "D0:17:C2" to "Samsung", "E8:50:8B" to "Samsung",
        "AC:5F:3E" to "Samsung", "20:64:32" to "Samsung",
        "64:CC:2E" to "Xiaomi", "F8:A4:5F" to "Xiaomi", "28:6C:07" to "Xiaomi", "50:8F:4C" to "Xiaomi",
        "AC:C1:EE" to "Xiaomi", "7C:1D:D9" to "Xiaomi", "FC:64:BA" to "Xiaomi",
        "C0:EE:FB" to "OnePlus", "94:65:2D" to "OnePlus", "64:A2:F9" to "OnePlus",
        "D0:C9:07" to "Oppo/Realme", "F0:E5:B3" to "Oppo/Realme", "6C:5A:B0" to "TCL/Realme",
        "10:5B:AD" to "Vivo", "C0:D3:C0" to "Vivo",
        "48:2C:A0" to "Huawei", "00:E0:FC" to "Huawei", "F4:8E:38" to "Huawei", "20:F3:A3" to "Huawei",
        "5C:F3:70" to "Amazon", "68:37:E9" to "Amazon", "44:65:0D" to "Amazon",
        "00:1A:79" to "Intel", "34:41:5D" to "Intel", "A4:C3:F0" to "Intel", "8C:16:45" to "Intel",
        "3C:97:0E" to "Wistron/Laptop", "00:1B:44" to "SanDisk",
        "B0:BE:76" to "TP-Link", "50:C7:BF" to "TP-Link", "AC:84:C6" to "TP-Link",
        "00:15:5D" to "Microsoft (Hyper-V)", "7C:1E:52" to "Microsoft", "50:1A:C5" to "Microsoft",
        "00:50:56" to "VMware", "08:00:27" to "VirtualBox",
        "70:BB:E9" to "Nintendo", "98:B6:E9" to "Nintendo", "00:D9:D1" to "Sony (PlayStation)",
    )
}
