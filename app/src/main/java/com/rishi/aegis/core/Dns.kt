package com.rishi.aegis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * A tiny DNS client that builds and parses DNS-over-UDP packets by hand, so we can
 * resolve record types the JDK's InetAddress can't (MX, TXT, NS, SOA, CNAME...).
 */
object DnsEngine {

    val types = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "PTR")

    private val typeCodes = mapOf(
        "A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "PTR" to 12,
        "MX" to 15, "TXT" to 16, "AAAA" to 28,
    )
    private val codeNames = typeCodes.entries.associate { (k, v) -> v to k }

    data class Record(val type: String, val ttl: Long, val value: String)

    suspend fun resolve(
        name: String,
        type: String,
        server: String = "8.8.8.8",
    ): List<Record> = withContext(Dispatchers.IO) {
        val qtype = typeCodes[type] ?: error("Unsupported type $type")
        val query = buildQuery(name.trim().trimEnd('.'), qtype)
        val serverAddr = InetAddress.getByName(server)
        DatagramSocket().use { socket ->
            socket.soTimeout = 5000
            socket.send(DatagramPacket(query, query.size, serverAddr, 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(resp)
            } catch (_: java.net.SocketTimeoutException) {
                error(
                    "No reply from $server:53 within 5s. This network may block external DNS — " +
                        "try your local resolver (your gateway IP)."
                )
            }
            parseResponse(buf, resp.length)
        }
    }

    private fun buildQuery(name: String, qtype: Int): ByteArray {
        val out = ArrayList<Byte>(64)
        // Header: id, flags (RD=1), qd=1, an=0, ns=0, ar=0
        val id = (System.nanoTime() and 0xFFFF).toInt()
        out.add16(id); out.add16(0x0100); out.add16(1); out.add16(0); out.add16(0); out.add16(0)
        // Question: labels (trim each to tolerate stray spaces from keyboards)
        for (label in name.split(".")) {
            val l = label.trim()
            if (l.isEmpty()) continue
            out.add(l.length.toByte())
            for (c in l.toByteArray(Charsets.UTF_8)) out.add(c)
        }
        out.add(0)            // root label
        out.add16(qtype)      // QTYPE
        out.add16(1)          // QCLASS = IN
        return out.toByteArray()
    }

    private fun parseResponse(buf: ByteArray, len: Int): List<Record> {
        fun u16(o: Int) = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
        fun u32(o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (buf[o + i].toLong() and 0xFF)
            return v
        }

        // read a (possibly compressed) name starting at [start]; return name + offset after the field
        fun readName(start: Int): Pair<String, Int> {
            val labels = StringBuilder()
            var o = start
            var jumped = false
            var afterPointer = start
            var guard = 0
            while (guard++ < 128) {
                val b = buf[o].toInt() and 0xFF
                if (b == 0) { o += 1; break }
                if (b and 0xC0 == 0xC0) {
                    val ptr = ((b and 0x3F) shl 8) or (buf[o + 1].toInt() and 0xFF)
                    if (!jumped) afterPointer = o + 2
                    o = ptr
                    jumped = true
                } else {
                    val s = o + 1
                    if (labels.isNotEmpty()) labels.append('.')
                    labels.append(String(buf, s, b, Charsets.UTF_8))
                    o = s + b
                }
            }
            return labels.toString() to if (jumped) afterPointer else o
        }

        val qd = u16(4); val an = u16(6)
        var o = 12
        // skip questions
        repeat(qd) {
            val (_, no) = readName(o)
            o = no + 4 // qtype + qclass
        }
        val records = ArrayList<Record>()
        repeat(an) {
            val (_, afterName) = readName(o)
            var p = afterName
            val type = u16(p); val ttl = u32(p + 4); val rdlen = u16(p + 8)
            val rdStart = p + 10
            val typeName = codeNames[type] ?: "TYPE$type"
            val value = when (type) {
                1 -> (0 until 4).joinToString(".") { (buf[rdStart + it].toInt() and 0xFF).toString() }
                28 -> {
                    val parts = (0 until 8).map { u16(rdStart + it * 2) }
                    parts.joinToString(":") { Integer.toHexString(it) }
                }
                5, 2, 12 -> readName(rdStart).first
                15 -> {
                    val pref = u16(rdStart)
                    val (host, _) = readName(rdStart + 2)
                    "$pref $host"
                }
                16 -> {
                    val sb = StringBuilder()
                    var q = rdStart
                    val end = rdStart + rdlen
                    while (q < end) {
                        val slen = buf[q].toInt() and 0xFF
                        sb.append(String(buf, q + 1, slen, Charsets.UTF_8))
                        q += 1 + slen
                    }
                    sb.toString()
                }
                6 -> {
                    val (mname, o1) = readName(rdStart)
                    val (rname, o2) = readName(o1)
                    val serial = u32(o2)
                    "$mname $rname serial=$serial"
                }
                else -> "(${rdlen} bytes)"
            }
            records.add(Record(typeName, ttl, value))
            p = rdStart + rdlen
            o = p
        }
        return records
    }

    private fun ArrayList<Byte>.add16(v: Int) {
        add(((v ushr 8) and 0xFF).toByte()); add((v and 0xFF).toByte())
    }
}
