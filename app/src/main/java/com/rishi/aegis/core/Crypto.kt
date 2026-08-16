package com.rishi.aegis.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import kotlin.math.ln

/**
 * Hashing, encoding and password utilities. Pure JVM, no Android deps, all synchronous
 * (cheap enough to run on the main thread for typical inputs).
 */
object CryptoEngine {

    val hashAlgorithms = listOf("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512", "CRC32")

    fun hash(algorithm: String, input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        return when (algorithm) {
            "CRC32" -> {
                val crc = CRC32().apply { update(bytes) }
                java.lang.Long.toHexString(crc.value).padStart(8, '0')
            }
            else -> {
                val md = MessageDigest.getInstance(algorithm)
                md.digest(bytes).toHex()
            }
        }
    }

    /** All standard digests of the input at once. */
    fun hashAll(input: String): List<Pair<String, String>> =
        hashAlgorithms.map { it to hash(it, input) }

    /** Best-effort identification of a hash string based on shape. */
    fun identify(raw: String): List<String> {
        val h = raw.trim()
        if (h.isEmpty()) return emptyList()
        val guesses = mutableListOf<String>()
        val isHex = h.matches(Regex("^[0-9a-fA-F]+$"))

        when {
            h.startsWith("\$2a\$") || h.startsWith("\$2b\$") || h.startsWith("\$2y\$") ->
                guesses += "bcrypt"
            h.startsWith("\$6\$") -> guesses += "sha512crypt (Unix)"
            h.startsWith("\$5\$") -> guesses += "sha256crypt (Unix)"
            h.startsWith("\$1\$") -> guesses += "md5crypt (Unix)"
            h.startsWith("\$apr1\$") -> guesses += "Apache apr1 (md5)"
            h.startsWith("\$y\$") || h.startsWith("\$7\$") -> guesses += "yescrypt / scrypt"
            h.startsWith("{SHA}") -> guesses += "LDAP SHA-1 (base64)"
            h.startsWith("{SSHA}") -> guesses += "LDAP salted SHA-1"
        }

        if (isHex) {
            when (h.length) {
                16 -> guesses += listOf("MySQL <4.1", "CRC64 / DES(hex)")
                32 -> guesses += listOf("MD5", "NTLM", "MD4", "LM half")
                40 -> guesses += listOf("SHA-1", "RIPEMD-160", "MySQL 4.1+ (SHA1)")
                56 -> guesses += "SHA-224"
                64 -> guesses += listOf("SHA-256", "SHA3-256", "BLAKE2s")
                96 -> guesses += "SHA-384"
                128 -> guesses += listOf("SHA-512", "SHA3-512", "BLAKE2b")
                8 -> guesses += "CRC32 / Adler-32"
            }
        }

        if (guesses.isEmpty()) {
            val b64 = h.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
            if (b64) guesses += "Base64-encoded digest (decode to inspect length)"
            else guesses += "Unknown format"
        }
        return guesses
    }

    /**
     * Dictionary attack: hash each candidate with [algorithm] and compare to [target].
     * Returns the matching word or null. [onProgress] receives (tried, total).
     */
    fun crack(
        algorithm: String,
        target: String,
        words: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): String? {
        val goal = target.trim().lowercase()
        val total = words.size
        for ((i, w) in words.withIndex()) {
            if (hash(algorithm, w).lowercase() == goal) return w
            if (i % 500 == 0) onProgress(i, total)
        }
        onProgress(total, total)
        return null
    }

    // ---- Encoders / decoders ----

    fun base64Encode(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    fun base64Decode(s: String): String =
        String(java.util.Base64.getDecoder().decode(s.trim()), Charsets.UTF_8)

    fun hexEncode(s: String): String = s.toByteArray(Charsets.UTF_8).toHex()

    fun hexDecode(s: String): String {
        val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
        require(clean.length % 2 == 0) { "Hex length must be even" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")

    fun rot13(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        )
    }

    // ---- Password strength & generation ----

    data class Strength(val bits: Double, val label: String, val charsetSize: Int)

    fun passwordStrength(pw: String): Strength {
        if (pw.isEmpty()) return Strength(0.0, "empty", 0)
        var pool = 0
        if (pw.any { it in 'a'..'z' }) pool += 26
        if (pw.any { it in 'A'..'Z' }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 33
        val bits = pw.length * (ln(pool.toDouble().coerceAtLeast(1.0)) / ln(2.0))
        val label = when {
            bits < 28 -> "very weak"
            bits < 36 -> "weak"
            bits < 60 -> "reasonable"
            bits < 128 -> "strong"
            else -> "very strong"
        }
        return Strength(bits, label, pool)
    }

    private val LOWER = ('a'..'z').joinToString("")
    private val UPPER = ('A'..'Z').joinToString("")
    private val DIGITS = ('0'..'9').joinToString("")
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.<>?/"

    fun generatePassword(
        length: Int,
        lower: Boolean = true,
        upper: Boolean = true,
        digits: Boolean = true,
        symbols: Boolean = true,
    ): String {
        val sb = StringBuilder()
        if (lower) sb.append(LOWER)
        if (upper) sb.append(UPPER)
        if (digits) sb.append(DIGITS)
        if (symbols) sb.append(SYMBOLS)
        val pool = sb.toString().ifEmpty { LOWER + DIGITS }
        val rnd = SecureRandom()
        return (0 until length.coerceIn(1, 256))
            .map { pool[rnd.nextInt(pool.length)] }
            .joinToString("")
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
