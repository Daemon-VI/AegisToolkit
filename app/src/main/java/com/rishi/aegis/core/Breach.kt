package com.rishi.aegis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "Has this password ever appeared in a known breach?" via Have I Been Pwned's Pwned Passwords API,
 * using the **k-anonymity** range model so the password — and even its full hash — never leaves the
 * device:
 *
 *  1. SHA-1 the password locally, uppercase hex (40 chars).
 *  2. Send only the first **5** hex chars (the "prefix") to `api.pwnedpasswords.com/range/{prefix}`.
 *  3. The server returns every breached-hash *suffix* that shares that prefix, with occurrence counts.
 *  4. We match the remaining 35 chars locally. The server never learns which password we asked about.
 *
 * We also send `Add-Padding: true`, so the response is padded with decoy zero-count entries and an
 * observer can't infer the answer from the response size. No API key is required for this endpoint.
 */
object Breach {

    data class Result(
        val pwned: Boolean,
        val count: Long,       // times seen across breaches (0 when not found)
        val prefix: String,    // the 5 chars actually sent — shown so the user can see how little left the phone
        val error: String? = null,
    )

    suspend fun checkPassword(password: String, timeoutMs: Int = 9000): Result =
        withContext(Dispatchers.IO) {
            if (password.isEmpty()) return@withContext Result(false, 0, "", "Enter a password to check.")

            val sha1 = sha1Hex(password)
            val prefix = sha1.substring(0, 5)
            val suffix = sha1.substring(5)   // 35 chars

            try {
                val conn = (URL("https://api.pwnedpasswords.com/range/$prefix").openConnection()
                        as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Add-Padding", "true")
                    // HIBP asks every client to identify itself with a descriptive User-Agent.
                    setRequestProperty("User-Agent", "Aegis-Toolkit-Android")
                }
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    return@withContext Result(false, 0, prefix, "HIBP returned HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                // Each line: "<35-hex-suffix>:<count>". Padding entries have count 0 — ignore those.
                var count = 0L
                for (line in body.lineSequence()) {
                    val sep = line.indexOf(':')
                    if (sep != 35) continue
                    if (line.regionMatches(0, suffix, 0, 35, ignoreCase = true)) {
                        count = line.substring(sep + 1).trim().toLongOrNull() ?: 0L
                        break
                    }
                }
                Result(count > 0, count, prefix)
            } catch (e: Exception) {
                Result(false, 0, prefix, e.message ?: "network error")
            }
        }

    private fun sha1Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(40)
        for (b in digest) sb.append("%02X".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
