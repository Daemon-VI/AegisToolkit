package com.rishi.aegis.tools

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.core.CaptureVpnService
import com.rishi.aegis.core.PacketParser
import com.rishi.aegis.core.TrafficCapture
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage

object TrafficScreens {

    @Composable
    fun Monitor(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val running by TrafficCapture.running.collectAsState()
        val stats by TrafficCapture.stats.collectAsState()

        // The VPN consent dialog. Android shows it the first time; RESULT_OK means the user allowed
        // Aegis to create a tunnel, after which we can start the capture service.
        val consent = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) CaptureVpnService.start(ctx)
        }

        fun toggle() {
            if (running) {
                CaptureVpnService.stop(ctx)
            } else {
                TrafficCapture.reset()
                val prepare = VpnService.prepare(ctx)
                if (prepare != null) consent.launch(prepare) else CaptureVpnService.start(ctx)
            }
        }

        ToolPage("Traffic Monitor", onBack) {
            Note(
                "Captures every IP packet the phone sends, with no root, by routing traffic through " +
                    "a local VPN tunnel Aegis owns. Only packet headers are read — protocol and the " +
                    "two endpoints — never the contents."
            )

            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Prototype: while capturing, internet is paused — packets are observed, then " +
                        "dropped (no forwarding yet). Stop the monitor to restore connectivity.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Button(
                onClick = { toggle() },
                modifier = Modifier.fillMaxWidth(),
                colors = if (running) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ) else ButtonDefaults.buttonColors(),
            ) {
                Text(if (running) "Stop capture" else "Start capture")
            }

            // Live counters
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Packets", stats.packets.toString(), Modifier.weight(1f))
                Stat("Data", humanBytes(stats.bytes), Modifier.weight(1f))
                Stat(
                    "Status",
                    if (running) "capturing" else "idle",
                    Modifier.weight(1f),
                )
            }

            if (stats.flows.isNotEmpty()) {
                SectionLabel("Live flows")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in stats.flows.take(80)) FlowRow(f)
                }
            } else if (running) {
                Note("Waiting for packets… open another app or load a page to generate traffic.")
            }
        }
    }

    @Composable
    private fun Stat(label: String, value: String, modifier: Modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    label.uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    private fun FlowRow(f: PacketParser.Flow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Chip(f.protoName, protoColor(f.protocol))
            Spacer(Modifier.width(8.dp))
            Text(
                "${f.srcLabel}  →  ${f.dstLabel}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${f.length}B",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }

    @Composable
    private fun protoColor(proto: Int) = when (proto) {
        6 -> MaterialTheme.colorScheme.primary        // TCP
        17 -> MaterialTheme.colorScheme.tertiary       // UDP
        else -> MaterialTheme.colorScheme.secondary
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
