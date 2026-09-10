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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.core.Totp
import com.rishi.aegis.core.Vault
import com.rishi.aegis.ui.AegisField
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.ErrorText
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.delay

object VaultScreens {

    // ---------------- TOTP Authenticator ----------------

    @Composable
    fun Authenticator(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val clip = LocalClipboardManager.current
        var entries by remember { mutableStateOf(Vault.totpEntries(ctx)) }
        var issuer by remember { mutableStateOf("") }
        var account by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

        // Tick every second so codes and the countdown stay live.
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }

        ToolPage("Authenticator", onBack) {
            Note(
                "RFC 6238 TOTP codes — the same 2FA codes as Google Authenticator. Each secret is " +
                    "encrypted with an AES-256 key held in this phone's hardware Keystore; the raw " +
                    "seed never leaves secure storage."
            )
            KeystoreBadge()

            if (entries.isEmpty()) {
                Note("No accounts yet. Add one below with its Base32 secret.")
            } else {
                for (e in entries) {
                    val code = remember(e.id, now / 1000L / 30L) {
                        runCatching { Totp.code(e.secret, now) }.getOrDefault("------")
                    }
                    val remaining = Totp.secondsRemaining(now)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.issuer.ifBlank { "Account" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    if (e.account.isNotBlank()) {
                                        Text(e.account, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                }
                                Text(
                                    "delete",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { Vault.deleteTotp(ctx, e.id); entries = Vault.totpEntries(ctx) }
                                        .padding(4.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                code.chunked(3).joinToString(" "),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 34.sp,
                                color = AegisGreen,
                                modifier = Modifier.clickable { clip.setText(AnnotatedString(code)) },
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { remaining / 30f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${remaining}s  ·  tap code to copy",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            SectionLabel("Add account")
            AegisField(issuer, { issuer = it }, "Issuer (e.g. GitHub)")
            AegisField(account, { account = it }, "Account (e.g. you@example.com)")
            AegisField(secret, { secret = it; error = null }, "Base32 secret")
            ErrorText(error)
            RunButton("Add", running = false, enabled = secret.isNotBlank()) {
                if (!Totp.isValidSecret(secret)) {
                    error = "That doesn't look like a valid Base32 secret."
                } else {
                    Vault.addTotp(ctx, issuer.trim(), account.trim(), secret.trim())
                    entries = Vault.totpEntries(ctx)
                    issuer = ""; account = ""; secret = ""
                }
            }
        }
    }

    // ---------------- Secure Notes Vault ----------------

    @Composable
    fun SecureNotes(onBack: () -> Unit) {
        val ctx = LocalContext.current
        var notes by remember { mutableStateOf(Vault.notes(ctx)) }
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var revealed by remember { mutableStateOf<Set<String>>(emptySet()) }

        ToolPage("Secure Vault", onBack) {
            Note(
                "Store passwords, recovery codes or private notes encrypted with AES-256-GCM under a " +
                    "hardware-backed Keystore key. Nothing here is readable without this phone."
            )
            KeystoreBadge()

            if (notes.isEmpty()) {
                Note("Vault is empty. Add a secret below.")
            } else {
                for (n in notes) {
                    val open = n.id in revealed
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (open) "hide" else "reveal",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { revealed = if (open) revealed - n.id else revealed + n.id }
                                        .padding(horizontal = 8.dp),
                                )
                                Text(
                                    "delete",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { Vault.deleteNote(ctx, n.id); notes = Vault.notes(ctx) }
                                        .padding(4.dp),
                                )
                            }
                            if (open) {
                                Spacer(Modifier.height(8.dp))
                                Text(n.body, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            SectionLabel("Add secret")
            AegisField(title, { title = it }, "Label")
            AegisField(body, { body = it }, "Secret contents", singleLine = false)
            RunButton("Save encrypted", running = false, enabled = body.isNotBlank()) {
                Vault.addNote(ctx, title.trim(), body)
                notes = Vault.notes(ctx)
                title = ""; body = ""
            }
        }
    }

    @Composable
    private fun KeystoreBadge() {
        val backed = remember { Vault.isBackedByKeystore() }
        Chip(
            if (backed) "🔒 Hardware Keystore active" else "Keystore unavailable",
            if (backed) AegisGreen else MaterialTheme.colorScheme.error,
        )
    }
}
