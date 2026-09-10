package com.rishi.aegis.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.core.ApkAnalyzer
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.AegisWarn
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.KeyVal
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.launch

object ApkScreens {

    @Composable
    fun Analyzer(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var apps by remember { mutableStateOf<List<ApkAnalyzer.AppEntry>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<ApkAnalyzer.Report?>(null) }
        var analyzing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            apps = ApkAnalyzer.installedApps(ctx)
        }

        ToolPage("App Analyzer", onBack) {
            val current = report
            if (current != null) {
                Text(
                    "‹ back to apps",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { report = null },
                )
                ReportView(current)
                return@ToolPage
            }

            Note(
                "Static, offline analysis of any installed app: the dangerous permissions it can " +
                    "request, its exported components (what other apps can reach), who signed it, " +
                    "and which third-party trackers its SDKs embed."
            )

            com.rishi.aegis.ui.AegisField(query, { query = it }, "Filter by name or package")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (showSystem) "hiding nothing" else "showing user apps only",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showSystem) "hide system" else "show system",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { showSystem = !showSystem }.padding(4.dp),
                )
            }

            if (analyzing) Note("Analyzing…")

            val q = query.trim().lowercase()
            val filtered = apps
                .filter { showSystem || !it.system }
                .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                .take(200)

            SectionLabel("${filtered.size} apps")
            for (app in filtered) {
                AppRow(app) {
                    analyzing = true
                    scope.launch {
                        report = ApkAnalyzer.analyze(ctx, app.pkg)
                        analyzing = false
                    }
                }
            }
        }
    }

    @Composable
    private fun AppRow(app: ApkAnalyzer.AppEntry, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp)) {
            Text(app.label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(app.pkg, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }

    @Composable
    private fun ReportView(r: ApkAnalyzer.Report) {
        Text(r.label, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(r.pkg, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

        // Risk banner
        val riskColor = when {
            r.riskScore >= 50 -> AegisError
            r.riskScore >= 20 -> AegisWarn
            else -> AegisGreen
        }
        Surface(
            color = riskColor.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Risk surface", color = riskColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "${r.dangerousPermissions.size} dangerous perms · " +
                            "${r.exported.count { !it.permissionGuarded }} open exports · " +
                            "${r.trackers.size} trackers",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                }
                Text("${r.riskScore}", color = riskColor, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
        }

        SectionLabel("Identity")
        KeyVal("Version", "${r.versionName} (${r.versionCode})")
        KeyVal("minSdk / target", "${r.minSdk} / ${r.targetSdk}")
        KeyVal("Type", if (r.system) "system app" else "user-installed")
        if (r.debugSigned) {
            Surface(color = AegisWarn.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                Text(
                    "Signed with the Android DEBUG key — not a production signature.",
                    color = AegisWarn, fontSize = 12.sp, modifier = Modifier.padding(10.dp),
                )
            }
        }
        SectionLabel("Signer SHA-256")
        Text(
            r.signerSha256,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        SectionLabel("Trackers (${r.trackers.size})")
        if (r.trackers.isEmpty()) {
            Note("No known tracker SDKs detected in the app's declared components.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in r.trackers) Chip(t, AegisError)
            }
        }

        SectionLabel("Dangerous permissions (${r.dangerousPermissions.size} of ${r.totalPermissions})")
        if (r.dangerousPermissions.isEmpty()) {
            Note("None requested.")
        } else {
            for (p in r.dangerousPermissions) {
                Text("• ${p.removePrefix("android.permission.")}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        val openExports = r.exported.count { !it.permissionGuarded }
        SectionLabel("Exported components (${r.exported.size}, $openExports unguarded)")
        if (r.exported.isEmpty()) {
            Note("No exported components.")
        } else {
            for (c in r.exported.take(40)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Chip(c.type, if (c.permissionGuarded) AegisCyan else AegisWarn)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  ${c.name.removePrefix(r.pkg)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (r.exported.size > 40) Note("… and ${r.exported.size - 40} more")
        }
    }
}
