package com.rishi.aegis.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rishi.aegis.core.DnsEngine
import com.rishi.aegis.core.NetEngine
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.AegisField
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Console
import com.rishi.aegis.ui.ErrorText
import com.rishi.aegis.ui.KeyVal
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.Progress
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.launch

object NetworkScreens {

    @Composable
    fun NetInfo(onBack: () -> Unit) {
        val ctx = LocalContext.current
        var info by remember { mutableStateOf(NetEngine.localInfo(ctx)) }
        ToolPage("Network Info", onBack) {
            Note("Details of your device's active network connection.")
            KeyVal("Transport", info.transport)
            KeyVal("Interface", info.iface ?: "—")
            KeyVal("IPv4", info.ipv4?.let { "$it/${info.prefix}" } ?: "—")
            if (info.ipv6.isNotEmpty()) info.ipv6.forEach { KeyVal("IPv6", it) }
            KeyVal("Gateway", info.gateway ?: "—")
            info.dns.forEachIndexed { i, d -> KeyVal(if (i == 0) "DNS" else "", d) }
            RunButton("Refresh", running = false) { info = NetEngine.localInfo(ctx) }
        }
    }

    @Composable
    fun HostDiscovery(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        ToolPage("Host Discovery", onBack) {
            Note(
                "Sweeps your local subnet (up to a /24) for devices that answer a reachability " +
                    "probe. Some devices stay silent behind a firewall, so results are a floor, not a census."
            )
            RunButton("Scan my network", running) {
                running = true; error = null; output = ""; progress = 0f
                scope.launch {
                    try {
                        val li = NetEngine.localInfo(ctx)
                        val ip = li.ipv4
                        val prefix = li.prefix
                        if (ip == null || prefix == null) {
                            error = "No IPv4 address on the active network."
                        } else {
                            val hosts = NetEngine.pingSweep(ip, prefix) { d, t ->
                                progress = if (t > 0) d.toFloat() / t else null
                            }
                            output = buildString {
                                appendLine("Live hosts (${hosts.size}) on ${ip}/$prefix:")
                                appendLine()
                                if (hosts.isEmpty()) appendLine("(none answered)")
                                hosts.forEach { h ->
                                    appendLine(h.ip.padEnd(16) + (h.hostname ?: ""))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        running = false; progress = null
                    }
                }
            }
            Progress(progress)
            ErrorText(error)
            Console(output)
        }
    }

    @Composable
    fun PortScan(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("") }
        var spec by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        ToolPage("Port Scanner", onBack) {
            Note("TCP connect scan. Blank port list scans a set of common ports.")
            AegisField(host, { host = it }, "Host or IP")
            AegisField(spec, { spec = it }, "Ports — e.g. 1-1024 or 22,80,443")
            RunButton("Scan", running, enabled = host.isNotBlank()) {
                running = true; error = null; output = ""; progress = 0f
                scope.launch {
                    try {
                        val ports = NetEngine.parsePorts(spec)
                        val open = NetEngine.portScan(host.trim(), ports) { d, t ->
                            progress = if (t > 0) d.toFloat() / t else null
                        }
                        output = buildString {
                            appendLine("Scanned ${ports.size} ports on ${host.trim()}")
                            appendLine("Open ports: ${open.size}")
                            appendLine()
                            open.forEach { appendLine("${it.port.toString().padEnd(7)} open   ${it.service}") }
                            if (open.isEmpty()) appendLine("(no open ports found)")
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        running = false; progress = null
                    }
                }
            }
            Progress(progress)
            ErrorText(error)
            Console(output)
        }
    }

    @Composable
    fun Ping(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("Ping", onBack) {
            Note("Sends 4 ICMP echo requests via the system ping binary.")
            AegisField(host, { host = it }, "Host or IP")
            RunButton("Ping", running, enabled = host.isNotBlank()) {
                running = true; output = ""
                scope.launch {
                    output = NetEngine.ping(host.trim())
                    running = false
                }
            }
            Console(output)
        }
    }

    @Composable
    fun Dns(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var name by remember { mutableStateOf("") }
        var server by remember { mutableStateOf("8.8.8.8") }
        var type by remember { mutableStateOf("A") }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        ToolPage("DNS Lookup", onBack) {
            Note("Queries a resolver over UDP and parses the raw records.")
            AegisField(name, { name = it }, "Domain (e.g. example.com)")
            AegisField(server, { server = it }, "Resolver")
            SectionLabel("Record type")
            for (chunk in DnsEngine.types.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in chunk) {
                        val sel = t == type
                        Box(Modifier.clickable { type = t }) {
                            Chip(t, if (sel) AegisGreen else AegisCyan)
                        }
                    }
                }
            }
            RunButton("Resolve", running, enabled = name.isNotBlank()) {
                running = true; error = null; output = ""
                scope.launch {
                    try {
                        val recs = DnsEngine.resolve(name.trim(), type, server.trim())
                        output = buildString {
                            appendLine("$type records for ${name.trim()} via ${server.trim()}")
                            appendLine()
                            if (recs.isEmpty()) appendLine("(no answer records)")
                            recs.forEach { appendLine("${it.type.padEnd(6)} ttl=${it.ttl}  ${it.value}") }
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        running = false
                    }
                }
            }
            ErrorText(error)
            Console(output)
        }
    }

    @Composable
    fun Whois(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var server by remember { mutableStateOf("whois.iana.org") }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("WHOIS", onBack) {
            Note(
                "Queries a WHOIS server on port 43. whois.iana.org returns a referral to the " +
                    "authoritative server — put that server here to get full details."
            )
            AegisField(query, { query = it }, "Domain or IP")
            AegisField(server, { server = it }, "WHOIS server")
            RunButton("Lookup", running, enabled = query.isNotBlank()) {
                running = true; output = ""
                scope.launch {
                    output = NetEngine.whois(server.trim(), query.trim())
                    running = false
                }
            }
            Console(output)
        }
    }

    @Composable
    fun Subnet(onBack: () -> Unit) {
        var cidr by remember { mutableStateOf("192.168.1.0/24") }
        val result = remember(cidr) { computeSubnet(cidr) }
        ToolPage("Subnet Calculator", onBack) {
            Note("Enter an address in CIDR notation.")
            AegisField(cidr, { cidr = it }, "CIDR (e.g. 10.0.0.0/24)")
            if (result != null) {
                KeyVal("Network", result.network)
                KeyVal("Broadcast", result.broadcast)
                KeyVal("Netmask", result.mask)
                KeyVal("Wildcard", result.wildcard)
                KeyVal("First host", result.firstHost)
                KeyVal("Last host", result.lastHost)
                KeyVal("Usable hosts", result.usable)
                KeyVal("Prefix", "/${result.prefix}")
            } else {
                ErrorText("Invalid CIDR")
            }
        }
    }
}

private data class SubnetResult(
    val network: String, val broadcast: String, val mask: String, val wildcard: String,
    val firstHost: String, val lastHost: String, val usable: String, val prefix: Int,
)

private fun computeSubnet(cidr: String): SubnetResult? {
    return try {
        val (ipStr, prefStr) = cidr.trim().split("/")
        val prefix = prefStr.toInt()
        if (prefix !in 0..32) return null
        val ip = NetEngine.ipToInt(ipStr.trim())
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ip and mask
        val broadcast = network or mask.inv()
        val total = 1L shl (32 - prefix)
        val usable = when {
            prefix >= 31 -> total          // /31 and /32 have no broadcast/network reserve
            else -> total - 2
        }
        val first = if (prefix >= 31) network else network + 1
        val last = if (prefix >= 31) broadcast else broadcast - 1
        SubnetResult(
            network = NetEngine.intToIp(network),
            broadcast = NetEngine.intToIp(broadcast),
            mask = NetEngine.intToIp(mask),
            wildcard = NetEngine.intToIp(mask.inv()),
            firstHost = NetEngine.intToIp(first),
            lastHost = NetEngine.intToIp(last),
            usable = usable.toString(),
            prefix = prefix,
        )
    } catch (_: Exception) {
        null
    }
}
