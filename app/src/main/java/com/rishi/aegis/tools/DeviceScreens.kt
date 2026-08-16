package com.rishi.aegis.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.rishi.aegis.core.DeviceSec
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.AegisWarn
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeviceScreens {

    @Composable
    fun SecurityCheckup(onBack: () -> Unit) {
        val ctx = LocalContext.current
        var reload by remember { mutableStateOf(0) }
        val checks by produceState<List<DeviceSec.Check>?>(initialValue = null, reload) {
            value = withContext(Dispatchers.IO) {
                runCatching { DeviceSec.checkup(ctx) }.getOrElse {
                    listOf(DeviceSec.Check("Checkup error", it.message ?: "failed", DeviceSec.Level.BAD))
                }
            }
        }
        ToolPage("Security Checkup", onBack) {
            Note("A read-only look at this phone's security posture. Nothing here changes settings.")
            val data = checks
            if (data == null) {
                Loading()
            } else {
                val bad = data.count { it.level == DeviceSec.Level.BAD }
                val warn = data.count { it.level == DeviceSec.Level.WARN }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (bad == 0) "0 critical" else "$bad critical", if (bad == 0) AegisGreen else AegisError)
                    Chip(if (warn == 0) "0 warnings" else "$warn warnings", if (warn == 0) AegisGreen else AegisWarn)
                    Text(
                        "refresh",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { reload++ }.padding(4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                for (c in data) CheckCard(c)
            }
        }
    }

    @Composable
    private fun CheckCard(c: DeviceSec.Check) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Chip(c.value, levelColor(c.level))
                }
                if (c.note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        c.note,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    @Composable
    fun PermissionAuditor(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val data by produceState<Pair<List<DeviceSec.SpecialAccess>, List<DeviceSec.PermGroup>>?>(
            initialValue = null,
        ) {
            value = withContext(Dispatchers.IO) {
                runCatching { DeviceSec.specialAccess(ctx) to DeviceSec.permissionAudit(ctx) }
                    .getOrDefault(emptyList<DeviceSec.SpecialAccess>() to emptyList())
            }
        }
        ToolPage("Permission Auditor", onBack) {
            Note("Which installed apps hold sensitive permissions right now. Tap a row to see the apps.")
            val d = data
            if (d == null) {
                Loading()
            } else {
                val (special, groups) = d
                SectionLabel("Special access (high power)")
                for (s in special) {
                    ExpandRow(
                        title = s.label,
                        count = s.apps.size,
                        countColor = if (s.apps.isEmpty()) AegisGreen else if (s.danger) AegisError else AegisCyan,
                        note = s.note,
                        apps = s.apps,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SectionLabel("Sensitive permissions")
                for (g in groups) {
                    ExpandRow(
                        title = g.label,
                        count = g.apps.size,
                        countColor = if (g.apps.isEmpty()) AegisGreen else AegisCyan,
                        note = null,
                        apps = g.apps.map { it.label },
                    )
                }
            }
        }
    }

    @Composable
    private fun ExpandRow(
        title: String,
        count: Int,
        countColor: androidx.compose.ui.graphics.Color,
        note: String?,
        apps: List<String>,
    ) {
        var open by remember { mutableStateOf(false) }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable(enabled = apps.isNotEmpty()) { open = !open },
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (apps.isNotEmpty()) (if (open) "▾ " else "▸ ") else "  ") + title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Chip("$count", countColor)
                }
                if (note != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (open) {
                    Spacer(Modifier.height(6.dp))
                    for (a in apps) {
                        Text(
                            "• $a",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    @Composable
    private fun Loading() {
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.height(28.dp))
        }
    }

    private fun levelColor(level: DeviceSec.Level) = when (level) {
        DeviceSec.Level.OK -> AegisGreen
        DeviceSec.Level.WARN -> AegisWarn
        DeviceSec.Level.BAD -> AegisError
        DeviceSec.Level.INFO -> AegisCyan
    }
}
