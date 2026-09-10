package com.rishi.aegis.tools

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.core.Hotspot
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.AegisWarn
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.KeyVal
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.Progress
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.launch

object HotspotScreens {

    @Composable
    fun Monitor(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var ap by remember { mutableStateOf<Hotspot.ApInfo?>(null) }
        var checked by remember { mutableStateOf(false) }
        var scanning by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var clients by remember { mutableStateOf<List<Hotspot.Client>>(emptyList()) }
        var scannedOnce by remember { mutableStateOf(false) }

        fun refresh() {
            ap = Hotspot.detectAp()
            checked = true
        }
        LaunchedEffect(Unit) { refresh() }

        ToolPage("Hotspot Monitor", onBack) {
            Note(
                "See who is connected to your phone's Wi-Fi hotspot and how much data is flowing " +
                    "through it. Works with no root. Note: the content of each device's traffic can't " +
                    "be read without root — that's routed below the app sandbox."
            )

            val current = ap
            if (current == null) {
                Surface(
                    color = AegisWarn.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (checked)
                            "No active hotspot detected. Turn on your mobile hotspot, then tap Refresh."
                        else "Checking…",
                        color = AegisWarn,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RunButton("Refresh", running = false) { refresh() }
                    RunButton("Open hotspot settings", running = false) { openTetherSettings(ctx) }
                }
                return@ToolPage
            }

            // Hotspot is up
            Surface(
                color = AegisGreen.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Hotspot active", color = AegisGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Interface ${current.iface} · subnet ${current.subnetLabel}",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
            }

            SectionLabel("Data through hotspot")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Received", humanBytes(current.rxBytes), Modifier.weight(1f))
                StatTile("Sent", humanBytes(current.txBytes), Modifier.weight(1f))
            }

            SectionLabel("Connected devices")
            RunButton("Scan for devices", running = scanning) {
                scanning = true; progress = 0f; clients = emptyList()
                scope.launch {
                    clients = Hotspot.scanClients(current) { d, t ->
                        progress = if (t > 0) d.toFloat() / t else null
                    }
                    scannedOnce = true
                    scanning = false
                    ap = Hotspot.detectAp() // refresh byte counters after the sweep
                }
            }
            Progress(progress.takeIf { scanning })

            if (clients.isNotEmpty()) {
                Text("${clients.size} device(s) connected", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                for (c in clients) ClientRow(c)
            } else if (scannedOnce && !scanning) {
                Note("No other devices answered the sweep. A connected device that blocks pings can " +
                    "stay invisible; try again while it's actively using data.")
            }
        }
    }

    @Composable
    private fun ClientRow(c: Hotspot.Client) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    c.vendor?.let { Chip(it, if (it.startsWith("Randomized")) AegisWarn else AegisGreen) }
                }
                if (c.mac != null) {
                    Text("MAC ${c.mac}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (c.hostname != null) {
                    Text(c.hostname, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun StatTile(label: String, value: String, modifier: Modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = modifier) {
            Column(Modifier.padding(12.dp)) {
                Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    private fun openTetherSettings(ctx: android.content.Context) {
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
