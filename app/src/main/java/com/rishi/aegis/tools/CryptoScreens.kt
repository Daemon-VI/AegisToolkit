package com.rishi.aegis.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.core.CryptoEngine
import com.rishi.aegis.core.toHex
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisField
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Console
import com.rishi.aegis.ui.ErrorText
import com.rishi.aegis.ui.KeyVal
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.Progress
import com.rishi.aegis.ui.RunButton
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CryptoScreens {

    @Composable
    fun HashGen(onBack: () -> Unit) {
        var input by remember { mutableStateOf("") }
        val hashes = remember(input) { if (input.isEmpty()) emptyList() else CryptoEngine.hashAll(input) }
        ToolPage("Hash Generator", onBack) {
            Note("Computes common digests of the text as you type.")
            AegisField(input, { input = it }, "Text to hash", singleLine = false)
            hashes.forEach { (algo, hex) -> KeyVal(algo, hex) }
            if (input.isNotEmpty()) {
                Console(hashes.joinToString("\n") { "${it.first}: ${it.second}" })
            }
        }
    }

    @Composable
    fun HashId(onBack: () -> Unit) {
        var input by remember { mutableStateOf("") }
        val guesses = remember(input) { CryptoEngine.identify(input) }
        ToolPage("Hash Identifier", onBack) {
            Note("Guesses a hash's algorithm from its length and prefix.")
            AegisField(input, { input = it }, "Hash")
            if (input.isNotBlank()) {
                SectionLabel("Likely types")
                guesses.forEach { Text("• $it", fontSize = 14.sp) }
            }
        }
    }

    @Composable
    fun HashCrack(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var target by remember { mutableStateOf("") }
        var algo by remember { mutableStateOf("MD5") }
        var wordlist by remember { mutableStateOf(COMMON_PASSWORDS.joinToString("\n")) }
        var running by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var output by remember { mutableStateOf("") }
        ToolPage("Dictionary Cracker", onBack) {
            Note("Hashes each word in the list with the chosen algorithm and compares to the target.")
            AegisField(target, { target = it }, "Target hash")
            SectionLabel("Algorithm")
            for (chunk in CryptoEngine.hashAlgorithms.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in chunk) {
                        val sel = a == algo
                        Box(Modifier.clickable { algo = a }) {
                            Chip(a, if (sel) AegisGreen else AegisCyan)
                        }
                    }
                }
            }
            SectionLabel("Wordlist (one per line)")
            AegisField(wordlist, { wordlist = it }, "Words", singleLine = false)
            RunButton("Crack", running, enabled = target.isNotBlank()) {
                running = true; output = ""; progress = 0f
                scope.launch {
                    val words = wordlist.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val hit = withContext(Dispatchers.Default) {
                        CryptoEngine.crack(algo, target, words) { d, t ->
                            progress = if (t > 0) d.toFloat() / t else null
                        }
                    }
                    output = if (hit != null) "FOUND: \"$hit\"" else "Not found in ${words.size} words."
                    running = false; progress = null
                }
            }
            Progress(progress)
            Console(output)
        }
    }

    @Composable
    fun Encoder(onBack: () -> Unit) {
        val ops = listOf(
            "Base64 enc", "Base64 dec", "Hex enc", "Hex dec",
            "URL enc", "URL dec", "ROT13",
        )
        var op by remember { mutableStateOf(ops.first()) }
        var input by remember { mutableStateOf("") }
        val (output, error) = remember(op, input) {
            try {
                val out = when (op) {
                    "Base64 enc" -> CryptoEngine.base64Encode(input)
                    "Base64 dec" -> CryptoEngine.base64Decode(input)
                    "Hex enc" -> CryptoEngine.hexEncode(input)
                    "Hex dec" -> CryptoEngine.hexDecode(input)
                    "URL enc" -> CryptoEngine.urlEncode(input)
                    "URL dec" -> CryptoEngine.urlDecode(input)
                    "ROT13" -> CryptoEngine.rot13(input)
                    else -> ""
                }
                out to null
            } catch (e: Exception) {
                "" to "Decode error: ${e.message}"
            }
        }
        ToolPage("Encoder / Decoder", onBack) {
            SectionLabel("Operation")
            for (chunk in ops.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in chunk) {
                        val sel = o == op
                        Box(Modifier.clickable { op = o }) {
                            Chip(o, if (sel) AegisGreen else AegisCyan)
                        }
                    }
                }
            }
            AegisField(input, { input = it }, "Input", singleLine = false)
            ErrorText(error)
            Console(output)
        }
    }

    @Composable
    fun PasswordTools(onBack: () -> Unit) {
        var pw by remember { mutableStateOf("") }
        val strength = remember(pw) { CryptoEngine.passwordStrength(pw) }

        var length by remember { mutableStateOf(16f) }
        var lower by remember { mutableStateOf(true) }
        var upper by remember { mutableStateOf(true) }
        var digits by remember { mutableStateOf(true) }
        var symbols by remember { mutableStateOf(true) }
        var generated by remember { mutableStateOf("") }

        ToolPage("Password Tools", onBack) {
            SectionLabel("Strength meter")
            AegisField(pw, { pw = it }, "Password to test")
            if (pw.isNotEmpty()) {
                KeyVal("Entropy", "%.1f bits".format(strength.bits))
                KeyVal("Rating", strength.label)
                KeyVal("Char pool", "${strength.charsetSize}")
                LinearProgressIndicator(
                    progress = { (strength.bits / 128.0).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }

            SectionLabel("Generator")
            Text("Length: ${length.toInt()}", color = MaterialTheme.colorScheme.onSurface)
            Slider(value = length, onValueChange = { length = it }, valueRange = 6f..64f)
            SwitchRow("Lowercase", lower) { lower = it }
            SwitchRow("Uppercase", upper) { upper = it }
            SwitchRow("Digits", digits) { digits = it }
            SwitchRow("Symbols", symbols) { symbols = it }
            RunButton("Generate", running = false) {
                generated = CryptoEngine.generatePassword(
                    length.toInt(), lower, upper, digits, symbols
                )
            }
            if (generated.isNotEmpty()) {
                Text(
                    generated,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Console(generated)
            }
        }
    }

    @Composable
    fun FileHash(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var fileName by remember { mutableStateOf<String?>(null) }
        var running by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var expected by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                running = true; result = emptyList(); error = null
                fileName = queryName(ctx, uri)
                scope.launch {
                    try {
                        result = withContext(Dispatchers.IO) { hashUri(ctx, uri) }
                    } catch (e: Exception) {
                        error = "Couldn't read file: ${e.message}"
                    } finally {
                        running = false
                    }
                }
            }
        }

        ToolPage("File Hash", onBack) {
            Note("Pick a file and compute its checksums — then paste the hash a publisher listed to confirm your download wasn't tampered with.")
            RunButton("Pick a file", running) { picker.launch(arrayOf("*/*")) }
            fileName?.let { KeyVal("File", it) }
            result.forEach { (algo, hex) -> KeyVal(algo, hex) }
            ErrorText(error)
            if (result.isNotEmpty()) {
                SectionLabel("Verify against expected")
                AegisField(expected, { expected = it }, "Paste an expected hash")
                if (expected.isNotBlank()) {
                    val exp = expected.trim()
                    val match = result.any { it.second.equals(exp, ignoreCase = true) }
                    Chip(if (match) "MATCH ✓" else "no match", if (match) AegisGreen else AegisError)
                }
                Console(result.joinToString("\n") { "${it.first}: ${it.second}" })
            }
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

private fun hashUri(ctx: Context, uri: Uri): List<Pair<String, String>> {
    val md5 = MessageDigest.getInstance("MD5")
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha256 = MessageDigest.getInstance("SHA-256")
    val stream = ctx.contentResolver.openInputStream(uri)
        ?: throw java.io.IOException("no stream")
    stream.use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
        }
    }
    return listOf(
        "MD5" to md5.digest().toHex(),
        "SHA-1" to sha1.digest().toHex(),
        "SHA-256" to sha256.digest().toHex(),
    )
}

private fun queryName(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIdx >= 0) c.getString(nameIdx) else "file"
        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
        if (size >= 0) "$name  (${humanSize(size)})" else name
    }
} catch (_: Exception) {
    null
}

private fun humanSize(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

private val COMMON_PASSWORDS = listOf(
    "123456", "password", "123456789", "12345678", "12345", "qwerty", "abc123",
    "111111", "123123", "admin", "letmein", "welcome", "monkey", "dragon",
    "master", "login", "princess", "solo", "passw0rd", "starwars", "hello",
    "whatever", "trustno1", "iloveyou", "sunshine", "football", "baseball",
    "superman", "batman", "shadow", "michael", "jennifer", "hunter", "2000",
    "test", "root", "toor", "changeme", "secret", "qwerty123", "1q2w3e4r",
    "zaq12wsx", "password1", "P@ssw0rd", "Welcome1", "aegis", "ninja", "access",
)
