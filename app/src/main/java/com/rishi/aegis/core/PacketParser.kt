package com.rishi.aegis.core

import java.net.InetAddress

/**
 * Minimal, allocation-light parser for the IP packets that arrive on the TUN file descriptor of a
 * [CaptureVpnService]. We only decode enough of each packet to describe a network *flow* — version,
 * transport protocol, the two endpoints and their ports — which is all the Traffic Monitor UI shows.
 *
 * The payload is never inspected or stored. Nothing here touches the network; it reads bytes Android
 * already handed us.
 */
object PacketParser {

    /** One captured packet, reduced to a human-readable flow record. */
    data class Flow(
        val timeMillis: Long,
        val ipVersion: Int,      // 4 or 6
        val protocol: Int,       // IP protocol number (6 TCP, 17 UDP, 1 ICMP, 58 ICMPv6…)
        val src: String,
        val srcPort: Int,        // 0 when not applicable (ICMP, parse failure)
        val dst: String,
        val dstPort: Int,
        val length: Int,         // total packet length in bytes
    ) {
        val protoName: String get() = protoName(protocol)
        /** "1.2.3.4:443" / "[2001:db8::1]:443" / bare address when no port. */
        val dstLabel: String get() = endpoint(dst, dstPort, ipVersion)
        val srcLabel: String get() = endpoint(src, srcPort, ipVersion)
    }

    /**
     * Parse one IP packet held in [buf] over `[0, len)`. Returns null only if the buffer is too
     * short to be a valid IP header — anything parseable yields a [Flow], even ICMP (port 0).
     */
    fun parse(buf: ByteArray, len: Int, now: Long): Flow? {
        if (len < 1) return null
        return when (buf[0].toInt() ushr 4 and 0xF) {
            4 -> parseV4(buf, len, now)
            6 -> parseV6(buf, len, now)
            else -> null
        }
    }

    private fun parseV4(b: ByteArray, len: Int, now: Long): Flow? {
        if (len < 20) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl) return null
        val proto = b[9].toInt() and 0xFF
        val src = addr(b, 12, 4)
        val dst = addr(b, 16, 4)
        val (sp, dp) = ports(b, ihl, proto, len)
        return Flow(now, 4, proto, src, sp, dst, dp, len)
    }

    private fun parseV6(b: ByteArray, len: Int, now: Long): Flow? {
        if (len < 40) return null
        // Fixed 40-byte header. We read Next Header directly; if it names an extension header rather
        // than a transport, we simply report port 0 instead of walking the extension chain.
        val next = b[6].toInt() and 0xFF
        val src = addr(b, 8, 16)
        val dst = addr(b, 24, 16)
        val (sp, dp) = ports(b, 40, next, len)
        return Flow(now, 6, next, src, sp, dst, dp, len)
    }

    /** Source/destination ports for TCP (6) and UDP (17); (0,0) otherwise or on truncation. */
    private fun ports(b: ByteArray, off: Int, proto: Int, len: Int): Pair<Int, Int> {
        if (proto != 6 && proto != 17) return 0 to 0
        if (len < off + 4) return 0 to 0
        val sp = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        val dp = ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
        return sp to dp
    }

    private fun addr(b: ByteArray, off: Int, n: Int): String = try {
        val raw = ByteArray(n)
        System.arraycopy(b, off, raw, 0, n)
        // getByAddress never does a reverse lookup, so this stays offline and cheap.
        InetAddress.getByAddress(raw).hostAddress ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun endpoint(addr: String, port: Int, version: Int): String = when {
        port == 0 -> addr
        version == 6 -> "[$addr]:$port"
        else -> "$addr:$port"
    }

    fun protoName(p: Int): String = when (p) {
        1 -> "ICMP"
        2 -> "IGMP"
        6 -> "TCP"
        17 -> "UDP"
        58 -> "ICMPv6"
        else -> "IP/$p"
    }
}
