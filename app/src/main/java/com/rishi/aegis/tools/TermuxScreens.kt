package com.rishi.aegis.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rishi.aegis.core.TermuxBridge
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisField
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Console
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.launch

object TermuxScreens {

    @Composable
    fun Setup(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var installed by remember { mutableStateOf(TermuxBridge.isInstalled(ctx)) }
        var perm by remember { mutableStateOf(TermuxBridge.hasPermission(ctx)) }
        var testing by remember { mutableStateOf(false) }
        var testOut by remember { mutableStateOf("") }
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { perm = it || TermuxBridge.hasPermission(ctx) }

        ToolPage("Termux Setup", onBack) {
            Note(
                "Aegis runs command-line tools (nmap, sqlmap, hydra…) by handing them to Termux, " +
                    "a Linux terminal app. Complete these steps once."
            )
            SectionLabel("Status")
            StatusRow("Termux installed", installed)
            StatusRow("RUN_COMMAND permission", perm)
            RunButton("Re-check", running = false) {
                installed = TermuxBridge.isInstalled(ctx)
                perm = TermuxBridge.hasPermission(ctx)
            }

            if (!installed) {
                SectionLabel("1 · Install Termux")
                Note("Use the F-Droid or GitHub build. The Google Play version is abandoned and won't work.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { TermuxBridge.openUrl(ctx, TermuxBridge.FDROID_URL) }) { Text("F-Droid") }
                    OutlinedButton(onClick = { TermuxBridge.openUrl(ctx, TermuxBridge.GITHUB_URL) }) { Text("GitHub") }
                }
            } else {
                if (!perm) {
                    SectionLabel("2 · Grant permission")
                    Note("Allow Aegis to send commands to Termux.")
                    RunButton("Grant RUN_COMMAND", running = false) {
                        permLauncher.launch(TermuxBridge.PERMISSION)
                    }
                }
                SectionLabel("3 · Enable external apps in Termux")
                Note("Open Termux and run these lines once, then tap Re-check and Test:")
                Console(
                    "mkdir -p ~/.termux\n" +
                        "echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                        "termux-reload-settings"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { TermuxBridge.launchTermux(ctx) }) { Text("Open Termux") }
                }
                SectionLabel("4 · Connection test")
                RunButton("Run test", testing, enabled = perm) {
                    testing = true; testOut = ""
                    scope.launch {
                        val r = TermuxBridge.run(ctx, "echo aegis-ok; uname -m; echo \"PATH ok\"", 30_000)
                        testOut = formatResult(r)
                        testing = false
                    }
                }
                Console(testOut)
            }
        }
    }

    @Composable
    fun RunCommand(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var cmd by remember { mutableStateOf("") }
        var background by remember { mutableStateOf(true) }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("Run Command", onBack) {
            Note("Runs through bash -c in Termux. Background captures output here; foreground opens a Termux session (use it for interactive tools).")
            AegisField(cmd, { cmd = it }, "Command (e.g. whoami && ls -la)", singleLine = false)
            SwitchRow("Capture output (background)", background) { background = it }
            RunButton("Run", running, enabled = cmd.isNotBlank()) {
                val err = preflight(ctx)
                when {
                    err != null -> output = err
                    background -> {
                        running = true; output = ""
                        scope.launch {
                            output = formatResult(TermuxBridge.run(ctx, cmd.trim()))
                            running = false
                        }
                    }
                    else -> {
                        TermuxBridge.runForeground(ctx, cmd.trim())
                        output = "Opened in Termux — switch to the Termux app to see the session."
                    }
                }
            }
            Console(output)
        }
    }

    @Composable
    fun Nmap(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val presets = listOf(
            "Quick" to "-T4 -F",
            "Top 1000" to "-T4",
            "All TCP" to "-p- -T4",
            "Version" to "-sV -T4",
            "Ping sweep" to "-sn",
        )
        var target by remember { mutableStateOf("") }
        var preset by remember { mutableStateOf(presets.first().first) }
        var ports by remember { mutableStateOf("") }
        var extra by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("Nmap", onBack) {
            Note("Runs nmap inside Termux. Without root it uses a TCP connect scan; -sS/-O need root. Install it first from the Toolbox if missing.")
            AegisField(target, { target = it }, "Target (host, IP, or CIDR)")
            SectionLabel("Scan preset")
            for (chunk in presets.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, _) in chunk) {
                        val sel = label == preset
                        Box(Modifier.clickable { preset = label }) {
                            Chip(label, if (sel) AegisGreen else AegisCyan)
                        }
                    }
                }
            }
            AegisField(ports, { ports = it }, "Ports (optional, e.g. 22,80,443 or 1-1024)")
            AegisField(extra, { extra = it }, "Extra flags (optional)")
            RunButton("Scan", running, enabled = target.isNotBlank()) {
                val err = preflight(ctx)
                if (err != null) {
                    output = err
                } else {
                    val flags = buildString {
                        append(presets.first { it.first == preset }.second)
                        if (ports.isNotBlank()) append(" -p ").append(ports.trim())
                        if (extra.isNotBlank()) append(' ').append(extra.trim())
                    }
                    val cmd = "nmap $flags ${target.trim()}"
                    running = true; output = ""
                    scope.launch {
                        output = "\$ $cmd\n\n" + formatResult(TermuxBridge.run(ctx, cmd, 300_000))
                        running = false
                    }
                }
            }
            Console(output)
        }
    }

    @Composable
    fun Toolbox(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val tools = listOf(
            "nmap", "hydra", "sqlmap", "curl", "wget",
            "git", "python", "openssh", "dnsutils", "net-tools",
        )
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("Toolbox", onBack) {
            Note("Check which CLI tools exist in Termux, or install them. Installs open a Termux session so you can watch the download.")
            RunButton("Check installed", running) {
                val err = preflight(ctx)
                if (err != null) {
                    output = err
                } else {
                    running = true; output = ""
                    val script = tools.joinToString("; ") { t ->
                        "if command -v ${t.substringBefore('-')} >/dev/null 2>&1 || dpkg -s $t >/dev/null 2>&1; " +
                            "then echo \"[+] $t\"; else echo \"[-] $t (missing)\"; fi"
                    }
                    scope.launch {
                        output = formatResult(TermuxBridge.run(ctx, script, 60_000))
                        running = false
                    }
                }
            }
            SectionLabel("Install")
            for (chunk in tools.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    for (t in chunk) {
                        OutlinedButton(
                            onClick = {
                                if (TermuxBridge.isInstalled(ctx)) {
                                    TermuxBridge.runForeground(ctx, "pkg install -y $t; echo DONE; sleep 3")
                                    output = "Installing $t in Termux — switch to the Termux app."
                                } else {
                                    output = "Termux isn't installed. Open 'Termux Setup'."
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("+ $t") }
                    }
                    if (chunk.size == 1) Box(Modifier.weight(1f)) {}
                }
            }
            Console(output)
        }
    }

    // ---- helpers ----

    @Composable
    private fun StatusRow(label: String, ok: Boolean) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Chip(if (ok) "yes" else "no", if (ok) AegisGreen else AegisError)
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

private fun preflight(ctx: android.content.Context): String? = when {
    !TermuxBridge.isInstalled(ctx) -> "Termux isn't installed. Open 'Termux Setup' first."
    !TermuxBridge.hasPermission(ctx) -> "RUN_COMMAND permission not granted. Open 'Termux Setup'."
    else -> null
}

private fun formatResult(r: TermuxBridge.Result?): String {
    if (r == null) {
        return "No response from Termux within the timeout.\n\n" +
            "Usually this means:\n" +
            " • allow-external-apps=true is not set in ~/.termux/termux.properties\n" +
            " • or Termux hasn't finished its first-time bootstrap\n\n" +
            "Fix it under 'Termux Setup', step 3."
    }
    return buildString {
        if (r.stdout.isNotEmpty()) append(r.stdout.trimEnd())
        if (r.stderr.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("── stderr ──")
            append(r.stderr.trimEnd())
        }
        if (isNotEmpty()) appendLine()
        append("── exit ${r.exitCode} ──")
        if (r.err != -1 && r.err != 0) {
            appendLine(); append("plugin err=${r.err} ${r.errmsg ?: ""}")
        }
    }
}
