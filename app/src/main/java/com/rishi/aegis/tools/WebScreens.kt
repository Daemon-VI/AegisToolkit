package com.rishi.aegis.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.rishi.aegis.core.DeviceSec
import com.rishi.aegis.core.NetEngine
import com.rishi.aegis.core.WebSec
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisField
import com.rishi.aegis.ui.AegisWarn
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Console
import com.rishi.aegis.ui.ErrorText
import com.rishi.aegis.ui.KeyVal
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.Progress
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.ToolPage
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

object WebScreens {

    @Composable
    fun Headers(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("HTTP Headers", onBack) {
            Note("Sends a GET request and shows the response status and headers (no redirect follow).")
            AegisField(url, { url = it }, "URL")
            RunButton("Fetch", running, enabled = url.isNotBlank()) {
                running = true; output = ""
                scope.launch {
                    val r = NetEngine.httpRequest("GET", url.trim(), followRedirects = false)
                    output = buildString {
                        appendLine("Status: ${r.status}   (${r.millis} ms)")
                        r.error?.let { appendLine("Error: $it") }
                        appendLine()
                        r.headers.forEach { (k, v) -> appendLine("$k: $v") }
                    }
                    running = false
                }
            }
            Console(output)
        }
    }

    @Composable
    fun Fingerprint(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("Tech Fingerprint", onBack) {
            Note("Guesses server software and frameworks from response headers and body markers.")
            AegisField(url, { url = it }, "URL")
            RunButton("Fingerprint", running, enabled = url.isNotBlank()) {
                running = true; output = ""
                scope.launch {
                    val r = NetEngine.httpRequest("GET", url.trim(), followRedirects = true)
                    val fp = NetEngine.fingerprint(r)
                    output = buildString {
                        appendLine("Status: ${r.status}")
                        appendLine()
                        appendLine("Detected:")
                        fp.forEach { appendLine("  • $it") }
                    }
                    running = false
                }
            }
            Console(output)
        }
    }

    @Composable
    fun DirBrute(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf("") }
        var wordlist by remember { mutableStateOf(DEFAULT_PATHS.joinToString("\n")) }
        var running by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        ToolPage("Directory Brute-Force", onBack) {
            Note("Requests each path under the base URL and reports anything that isn't a 404.")
            AegisField(url, { url = it }, "Base URL (e.g. http://host)")
            SectionLabel("Wordlist (one path per line)")
            AegisField(wordlist, { wordlist = it }, "Paths", singleLine = false)
            RunButton("Run", running, enabled = url.isNotBlank()) {
                running = true; error = null; output = ""; progress = 0f
                scope.launch {
                    try {
                        val paths = wordlist.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val found = NetEngine.dirBrute(url.trim(), paths) { d, t ->
                            progress = if (t > 0) d.toFloat() / t else null
                        }
                        output = buildString {
                            appendLine("Tested ${paths.size} paths — ${found.size} hits")
                            appendLine()
                            found.forEach { r ->
                                val loc = r.location?.let { " -> $it" } ?: ""
                                appendLine("${r.code}  ${r.path}$loc")
                            }
                            if (found.isEmpty()) appendLine("(nothing but 404s)")
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
    fun RequestBuilder(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val methods = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
        var method by remember { mutableStateOf("GET") }
        var url by remember { mutableStateOf("") }
        var headersText by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var follow by remember { mutableStateOf(false) }
        var running by remember { mutableStateOf(false) }
        var output by remember { mutableStateOf("") }
        ToolPage("Request Builder", onBack) {
            Note("Craft a raw HTTP request. Headers: one 'Key: Value' per line.")
            SectionLabel("Method")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in methods) {
                    val sel = m == method
                    Box(Modifier.clickable { method = m }) {
                        Chip(m, if (sel) AegisGreen else AegisCyan)
                    }
                }
            }
            AegisField(url, { url = it }, "URL")
            AegisField(headersText, { headersText = it }, "Headers", singleLine = false)
            AegisField(body, { body = it }, "Body", singleLine = false)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Follow redirects", color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = follow, onCheckedChange = { follow = it })
            }
            RunButton("Send", running, enabled = url.isNotBlank()) {
                running = true; output = ""
                scope.launch {
                    val headers = headersText.lines()
                        .mapNotNull { line ->
                            val idx = line.indexOf(':')
                            if (idx <= 0) null
                            else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        }.toMap()
                    val r = NetEngine.httpRequest(
                        method, url.trim(), headers,
                        body.ifBlank { null }, followRedirects = follow
                    )
                    output = buildString {
                        appendLine("Status: ${r.status}   (${r.millis} ms)")
                        r.error?.let { appendLine("Error: $it") }
                        appendLine()
                        appendLine("── Response headers ──")
                        r.headers.forEach { (k, v) -> appendLine("$k: $v") }
                        appendLine()
                        appendLine("── Body ──")
                        append(r.body.take(20_000))
                    }
                    running = false
                }
            }
            Console(output)
        }
    }

    @Composable
    fun TlsInspector(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<WebSec.TlsResult?>(null) }
        ToolPage("TLS / Cert Inspector", onBack) {
            Note("Handshakes with an HTTPS host and shows its certificate chain, key, expiry and the negotiated cipher — even if the cert is untrusted or expired.")
            AegisField(host, { host = it }, "Host (e.g. example.com)")
            AegisField(port, { port = it }, "Port (default 443)", numeric = true)
            RunButton("Inspect", running, enabled = host.isNotBlank()) {
                running = true; result = null
                scope.launch {
                    // Strip whitespace — soft keyboards love inserting a space after the dot.
                    val cleanHost = host.filterNot { it.isWhitespace() }
                    result = WebSec.inspectTls(cleanHost, port.trim().toIntOrNull() ?: 443)
                    running = false
                }
            }
            result?.let { r ->
                if (r.error != null) {
                    ErrorText("Handshake failed: ${r.error}")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(if (r.validated) "trusted ✓" else "NOT trusted", if (r.validated) AegisGreen else AegisError)
                        r.protocol?.let { Chip(it, AegisCyan) }
                    }
                    if (!r.validated && r.validationError != null) {
                        Text(r.validationError, color = AegisWarn, fontSize = 12.sp)
                    }
                    Console(buildString {
                        appendLine("Protocol : ${r.protocol ?: "?"}")
                        appendLine("Cipher   : ${r.cipher ?: "?"}")
                        appendLine()
                        r.certs.forEachIndexed { i, c ->
                            appendLine("── Certificate #${i + 1}${if (c.isCa) "  [CA]" else ""} ──")
                            appendLine("Subject : ${cn(c.subject)}")
                            appendLine("Issuer  : ${cn(c.issuer)}")
                            appendLine("Valid   : ${c.notBefore} -> ${c.notAfter}")
                            appendLine("Key     : ${c.keyAlg}${c.keyBits?.let { " $it-bit" } ?: ""}")
                            appendLine("SigAlg  : ${c.sigAlg}")
                            if (c.selfSigned) appendLine("          (self-signed)")
                            if (c.sans.isNotEmpty()) {
                                val shown = c.sans.take(12).joinToString(", ")
                                appendLine("SAN     : $shown${if (c.sans.size > 12) " …(+${c.sans.size - 12})" else ""}")
                            }
                            WebSec.certWarnings(c).forEach { appendLine("  ! $it") }
                            appendLine()
                        }
                    })
                }
            }
        }
    }

    @Composable
    fun HeaderAudit(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<WebSec.HeaderReport?>(null) }
        var err by remember { mutableStateOf<String?>(null) }
        ToolPage("Security Headers", onBack) {
            Note("Fetches a page and grades its HTTP security headers (HSTS, CSP, X-Frame-Options and more). Defaults to HTTPS.")
            AegisField(url, { url = it }, "URL or host")
            RunButton("Audit", running, enabled = url.isNotBlank()) {
                running = true; report = null; err = null
                scope.launch {
                    val cleaned = url.filterNot { it.isWhitespace() }
                    val u = cleaned.let { if (it.contains("://")) it else "https://$it" }
                    val r = NetEngine.httpRequest("GET", u, followRedirects = true)
                    if (r.error != null && r.headers.isEmpty()) err = r.error
                    else report = WebSec.auditHeaders(r, https = u.startsWith("https"))
                    running = false
                }
            }
            ErrorText(err)
            report?.let { rep ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Grade ${rep.grade}",
                        color = gradeColor(rep.grade),
                        fontSize = 22.sp,
                    )
                    if (!rep.https) Chip("plain HTTP", AegisWarn)
                }
                Spacer(Modifier.height(4.dp))
                for (row in rep.rows) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.header, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        Chip(
                            if (row.value != null) "present" else "missing",
                            when (row.level) {
                                DeviceSec.Level.OK -> AegisGreen
                                DeviceSec.Level.WARN -> AegisWarn
                                else -> AegisCyan
                            },
                        )
                    }
                    Text(row.advice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
                    row.value?.let { KeyVal("", it.take(160)) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Pull the CN out of an RFC-2253 distinguished name for readability. */
private fun cn(dn: String): String =
    Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1)?.trim() ?: dn

private fun gradeColor(grade: String) = when (grade) {
    "A", "B" -> AegisGreen
    "C", "D" -> AegisWarn
    else -> AegisError
}

private val DEFAULT_PATHS = listOf(
    "admin", "administrator", "login", "wp-admin", "wp-login.php", "dashboard",
    "config", "config.php", ".env", ".git/HEAD", ".git/config", "backup", "backup.zip",
    "db", "database.sql", "phpinfo.php", "info.php", "test", "robots.txt", "sitemap.xml",
    "api", "api/v1", "graphql", "server-status", "status", "health", "metrics",
    "uploads", "images", "static", "assets", "js", "css", "old", "tmp", "temp",
    "console", "actuator", "swagger", "swagger-ui.html", "docs", "readme.md",
    ".htaccess", "web.config", "crossdomain.xml", "user", "users", "account",
)
