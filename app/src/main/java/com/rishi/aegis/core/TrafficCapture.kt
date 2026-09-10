package com.rishi.aegis.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Process-wide state for the Traffic Monitor, shared between [CaptureVpnService] (writer, on its
 * read thread) and the Compose screen (reader). Kept deliberately tiny: a bounded ring of the most
 * recent flows plus running totals. No packet payloads are ever retained.
 *
 * The service pushes flows in one at a time and calls [publish] on a throttle; the UI collects the
 * two StateFlows. All buffer mutation is guarded by [lock].
 */
object TrafficCapture {

    private const val RING = 400          // most-recent flows kept for the live list
    private const val PUBLISH_MS = 400L   // UI refresh throttle

    data class Stats(val packets: Long, val bytes: Long, val flows: List<PacketParser.Flow>)

    private val lock = Any()
    private val ring = ArrayDeque<PacketParser.Flow>(RING)
    private var packets = 0L
    private var bytes = 0L
    private var lastPublish = 0L

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _stats = MutableStateFlow(Stats(0, 0, emptyList()))
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun onServiceState(active: Boolean) {
        _running.value = active
    }

    fun reset() {
        synchronized(lock) {
            ring.clear()
            packets = 0
            bytes = 0
            lastPublish = 0
        }
        _stats.value = Stats(0, 0, emptyList())
    }

    /** Record one parsed flow. Cheap and non-blocking; publishes a UI snapshot at most every 400ms. */
    fun record(flow: PacketParser.Flow, nowMs: Long) {
        val snapshot: Stats?
        synchronized(lock) {
            if (ring.size >= RING) ring.pollLast()
            ring.addFirst(flow)
            packets++
            bytes += flow.length
            snapshot = if (nowMs - lastPublish >= PUBLISH_MS) {
                lastPublish = nowMs
                Stats(packets, bytes, ring.toList())
            } else null
        }
        if (snapshot != null) _stats.value = snapshot
    }

    /** Force a final snapshot (e.g. when the service stops) so the UI reflects the last packets. */
    fun publish() {
        synchronized(lock) {
            _stats.value = Stats(packets, bytes, ring.toList())
        }
    }
}
