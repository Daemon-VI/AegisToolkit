package com.rishi.aegis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rishi.aegis.core.NetEngine
import com.rishi.aegis.ui.AegisField
import com.rishi.aegis.ui.KeyVal
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage

object WifiScreens {

    @Composable
    fun Connection(onBack: () -> Unit) {
        val ctx = LocalContext.current
        ToolPage("Wi-Fi Connection", onBack) {
            WifiGate(ctx) {
                var info by remember { mutableStateOf(readConnection(ctx)) }
                Note("Details of the Wi-Fi network you're currently connected to.")
                if (info == null) {
                    Note("Not connected to Wi-Fi (or SSID hidden by the OS).")
                } else {
                    val c = info!!
                    KeyVal("SSID", c.ssid)
                    KeyVal("BSSID", c.bssid)
                    KeyVal("IP address", c.ip)
                    KeyVal("Signal", "${c.rssi} dBm (${c.quality}%)")
                    KeyVal("Link speed", "${c.linkSpeed} Mbps")
                    KeyVal("Frequency", "${c.frequency} MHz")
                    KeyVal("Channel", if (c.channel > 0) c.channel.toString() else "—")
                    KeyVal("Band", c.band)
                }
                RunButton("Refresh", running = false) { info = readConnection(ctx) }
            }
        }
    }

    @Composable
    fun Scan(onBack: () -> Unit) {
        val ctx = LocalContext.current
        ToolPage("Nearby Networks", onBack) {
            WifiGate(ctx) {
                var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
                var note by remember { mutableStateOf("") }
                Note(
                    "Android 9+ throttles Wi-Fi scans and requires location to be ON. " +
                        "If the list is empty or stale, toggle location and try again."
                )
                RunButton("Scan", running = false) {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val started = wm.startScan()
                    @Suppress("DEPRECATION")
                    results = try {
                        wm.scanResults.sortedByDescending { it.level }
                    } catch (_: SecurityException) {
                        emptyList()
                    }
                    note = if (results.isEmpty()) {
                        "No results yet (scan start=${started}). Ensure location is enabled, wait, and retry."
                    } else "${results.size} networks found."
                }
                if (note.isNotEmpty()) Note(note)
                results.forEach { NetworkRow(it) }
            }
        }
    }

    @Composable
    private fun NetworkRow(r: ScanResult) {
        @Suppress("DEPRECATION")
        val ssid = r.SSID?.ifBlank { "<hidden>" } ?: "<hidden>"
        val quality = ((r.level + 100) * 2).coerceIn(0, 100)
        val ch = NetEngine.frequencyToChannel(r.frequency)
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(ssid, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "${r.BSSID}   ${r.level} dBm   ch ${if (ch > 0) ch else "?"} · ${NetEngine.band(r.frequency)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            r.capabilities?.let {
                Text(security(it), color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
            }
            LinearProgressIndicator(
                progress = { quality / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    // ---- permission gate ----

    @Composable
    private fun WifiGate(ctx: Context, content: @Composable () -> Unit) {
        var granted by remember { mutableStateOf(hasLocation(ctx)) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted = hasLocation(ctx) }
        if (granted) {
            content()
        } else {
            SectionLabel("Permission needed")
            Note("Android requires location permission to read Wi-Fi SSID and scan for networks.")
            RunButton("Grant permission", running = false) {
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                launcher.launch(perms.toTypedArray())
            }
        }
    }

    private fun hasLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private data class Conn(
        val ssid: String, val bssid: String, val ip: String, val rssi: Int, val quality: Int,
        val linkSpeed: Int, val frequency: Int, val channel: Int, val band: String,
    )

    private fun readConnection(ctx: Context): Conn? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ci = wm.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val raw = ci.ipAddress
        if (ci.networkId == -1 && raw == 0) return null
        val ip = "%d.%d.%d.%d".format(
            raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff
        )
        @Suppress("DEPRECATION")
        val ssid = ci.ssid?.trim('"')?.ifBlank { "<unknown>" } ?: "<unknown>"
        val quality = ((ci.rssi + 100) * 2).coerceIn(0, 100)
        return Conn(
            ssid = ssid,
            bssid = ci.bssid ?: "—",
            ip = ip,
            rssi = ci.rssi,
            quality = quality,
            linkSpeed = ci.linkSpeed,
            frequency = ci.frequency,
            channel = NetEngine.frequencyToChannel(ci.frequency),
            band = NetEngine.band(ci.frequency),
        )
    }

    private fun security(caps: String): String = when {
        "WPA3" in caps -> "WPA3"
        "WPA2" in caps -> "WPA2"
        "WPA" in caps -> "WPA"
        "WEP" in caps -> "WEP (weak)"
        else -> "Open"
    }
}
