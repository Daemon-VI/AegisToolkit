package com.rishi.aegis.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

object NetEngine {

    // ---------- Local network info ----------

    data class LocalInfo(
        val transport: String,
        val iface: String?,
        val ipv4: String?,
        val prefix: Int?,
        val ipv6: List<String>,
        val gateway: String?,
        val dns: List<String>,
    )

    fun localInfo(context: Context): LocalInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        var ipv4: String? = null
        var prefix: Int? = null
        val ipv6 = mutableListOf<String>()
        lp?.linkAddresses?.forEach { la ->
            val a = la.address
            val host = a.hostAddress ?: return@forEach
            if (host.contains(':')) ipv6.add("$host/${la.prefixLength}")
            else { ipv4 = host; prefix = la.prefixLength }
        }
        val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        return LocalInfo(transport, lp?.interfaceName, ipv4, prefix, ipv6, gateway, dns)
    }

    // ---------- Host discovery (ping sweep) ----------

    data class HostResult(val ip: String, val hostname: String?)

    suspend fun pingSweep(
        localIpv4: String,
        prefix: Int,
        timeoutMs: Int = 400,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<HostResult> = coroutineScope {
        val base = ipToInt(localIpv4)
        val effPrefix = prefix.coerceIn(24, 30) // cap sweep to at most a /24
        val mask = if (effPrefix == 0) 0 else (-1 shl (32 - effPrefix))
        val network = base and mask
        val hostBits = 32 - effPrefix
        val count = (1 shl hostBits)
        val first = network + 1
        val last = network + count - 2
        val total = (last - first + 1).coerceAtLeast(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val sem = Semaphore(64)
        val jobs = (first..last).map { ipInt ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ip = intToIp(ipInt)
                    val addr = InetAddress.getByName(ip)
                    val reachable = try { addr.isReachable(timeoutMs) } catch (_: Exception) { false }
                    onProgress(done.incrementAndGet(), total)
                    if (reachable) {
                        val name = try {
                            addr.canonicalHostName.takeIf { it != ip }
                        } catch (_: Exception) { null }
                        HostResult(ip, name)
                    } else null
                }
            }
        }
        jobs.awaitAll().filterNotNull().sortedBy { ipToInt(it.ip) }
    }

    // ---------- Port scanner ----------

    data class PortResult(val port: Int, val service: String)

    suspend fun portScan(
        host: String,
        ports: List<Int>,
        timeoutMs: Int = 600,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<PortResult> = coroutineScope {
        val target = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
        val total = ports.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val sem = Semaphore(128)
        val jobs = ports.map { port ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val open = try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(target, port), timeoutMs)
                            true
                        }
                    } catch (_: Exception) { false }
                    onProgress(done.incrementAndGet(), total)
                    if (open) PortResult(port, serviceName(port)) else null
                }
            }
        }
        jobs.awaitAll().filterNotNull().sortedBy { it.port }
    }

    // ---------- Ping (real ICMP via system binary) ----------

    suspend fun ping(host: String, count: Int = 4): String = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "2", host)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.ifBlank { "No output (host may be unreachable or ping is blocked)." }
        } catch (e: Exception) {
            "ping failed: ${e.message}"
        }
    }

    // ---------- WHOIS ----------

    suspend fun whois(server: String, query: String): String = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(server, 43), 8000)
                s.soTimeout = 8000
                s.getOutputStream().write((query.trim() + "\r\n").toByteArray())
                s.getOutputStream().flush()
                BufferedReader(InputStreamReader(s.getInputStream())).readText()
            }
        } catch (e: Exception) {
            "WHOIS failed: ${e.message}"
        }
    }

    // ---------- HTTP ----------

    data class HttpResult(
        val status: String,
        val headers: List<Pair<String, String>>,
        val body: String,
        val millis: Long,
        val error: String? = null,
    )

    suspend fun httpRequest(
        method: String,
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        followRedirects: Boolean = false,
        maxBody: Int = 200_000,
    ): HttpResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            val url = URL(if (urlStr.contains("://")) urlStr else "http://$urlStr")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = followRedirects
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null && method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = conn.responseCode
            val status = "$code ${conn.responseMessage ?: ""}".trim()
            val hdrs = conn.headerFields
                .filterKeys { it != null }
                .flatMap { (k, vs) -> vs.map { k!! to it } }
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.let {
                val r = it.readBytes()
                String(r.copyOf(minOf(r.size, maxBody)), Charsets.UTF_8)
            } ?: ""
            HttpResult(status, hdrs, text, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            HttpResult("—", emptyList(), "", System.currentTimeMillis() - start, e.message)
        } finally {
            conn?.disconnect()
        }
    }

    // ---------- Directory brute force ----------

    data class DirResult(val path: String, val code: Int, val length: Long, val location: String?)

    suspend fun dirBrute(
        baseUrl: String,
        paths: List<String>,
        timeoutMs: Int = 8000,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<DirResult> = coroutineScope {
        val root = baseUrl.trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
        val total = paths.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val sem = Semaphore(24)
        val jobs = paths.map { raw ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val path = raw.trim().removePrefix("/")
                    var conn: HttpURLConnection? = null
                    val res = try {
                        val url = URL("$root/$path")
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = timeoutMs
                            readTimeout = timeoutMs
                            instanceFollowRedirects = false
                        }
                        val code = conn!!.responseCode
                        val len = conn!!.contentLengthLong
                        val loc = conn!!.getHeaderField("Location")
                        DirResult("/$path", code, len, loc)
                    } catch (_: Exception) {
                        null
                    } finally {
                        conn?.disconnect()
                    }
                    onProgress(done.incrementAndGet(), total)
                    res
                }
            }
        }
        jobs.awaitAll().filterNotNull()
            .filter { it.code != 404 }
            .sortedBy { it.path }
    }

    // ---------- Fingerprint ----------

    fun fingerprint(result: HttpResult): List<String> {
        val findings = mutableListOf<String>()
        val hmap = result.headers.associate { it.first.lowercase() to it.second }
        hmap["server"]?.let { findings += "Server: $it" }
        hmap["x-powered-by"]?.let { findings += "X-Powered-By: $it" }
        hmap["x-aspnet-version"]?.let { findings += "ASP.NET: $it" }
        hmap["x-generator"]?.let { findings += "Generator: $it" }
        hmap["via"]?.let { findings += "Via: $it" }
        hmap["cf-ray"]?.let { findings += "Cloudflare (cf-ray present)" }
        hmap["x-drupal-cache"]?.let { findings += "Drupal" }
        val body = result.body.lowercase()
        if ("wp-content" in body || "wp-includes" in body) findings += "WordPress (wp-content)"
        if ("/sites/all/" in body || "drupal.settings" in body) findings += "Drupal"
        if ("cdn.shopify.com" in body) findings += "Shopify"
        if ("__next" in body || "/_next/" in body) findings += "Next.js"
        if ("ng-version" in body) findings += "Angular"
        if ("react" in body && "root" in body) findings += "Possibly React"
        Regex("<meta[^>]+generator[^>]+content=\"([^\"]+)\"").find(result.body)?.let {
            findings += "Meta generator: ${it.groupValues[1]}"
        }
        if (findings.isEmpty()) findings += "No obvious fingerprints in headers/body."
        return findings
    }

    // ---------- helpers ----------

    fun ipToInt(ip: String): Int {
        val p = ip.split(".")
        return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
    }

    fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    val commonPorts = listOf(
        21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 161, 389, 443, 445, 465,
        587, 631, 993, 995, 1025, 1433, 1521, 1723, 2049, 3000, 3128, 3306, 3389,
        5060, 5432, 5900, 6379, 8000, 8008, 8080, 8443, 8888, 9000, 9200, 27017,
    )

    private val services = mapOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
        80 to "http", 110 to "pop3", 135 to "msrpc", 139 to "netbios", 143 to "imap",
        161 to "snmp", 389 to "ldap", 443 to "https", 445 to "smb", 465 to "smtps",
        587 to "submission", 631 to "ipp", 993 to "imaps", 995 to "pop3s",
        1433 to "mssql", 1521 to "oracle", 1723 to "pptp", 2049 to "nfs",
        3306 to "mysql", 3389 to "rdp", 5432 to "postgres", 5900 to "vnc",
        6379 to "redis", 8080 to "http-alt", 8443 to "https-alt", 9200 to "elastic",
        27017 to "mongodb", 3000 to "dev", 8000 to "http-alt", 8888 to "http-alt",
    )

    fun serviceName(port: Int): String = services[port] ?: "unknown"

    fun parsePorts(spec: String): List<Int> {
        if (spec.isBlank()) return commonPorts
        val out = sortedSetOf<Int>()
        for (part in spec.split(",")) {
            val t = part.trim()
            if (t.isEmpty()) continue
            if ("-" in t) {
                val (a, b) = t.split("-").map { it.trim().toIntOrNull() ?: 0 }
                for (p in a..b) if (p in 1..65535) out.add(p)
            } else t.toIntOrNull()?.let { if (it in 1..65535) out.add(it) }
        }
        return out.toList()
    }

    fun frequencyToChannel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2412) / 5 + 1
        freq in 5170..5825 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5   // 6 GHz
        else -> -1
    }

    fun band(freq: Int): String = when {
        freq < 2500 -> "2.4 GHz"
        freq in 4900..5900 -> "5 GHz"
        freq >= 5925 -> "6 GHz"
        else -> "?"
    }
}
