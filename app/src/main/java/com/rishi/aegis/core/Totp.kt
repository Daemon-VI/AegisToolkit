package com.rishi.aegis.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 Time-based One-Time Passwords — the algorithm behind Google Authenticator / Authy. Given
 * a shared Base32 secret it derives the same rotating 6-digit code the server expects, entirely
 * on-device. No storage here; the secret is kept encrypted by [Vault].
 */
object Totp {

    data class Params(
        val digits: Int = 6,
        val periodSec: Int = 30,
        val algorithm: String = "HmacSHA1",   // SHA1 is the near-universal default
    )

    /** The current code for [base32Secret] at [nowMillis], zero-padded to [Params.digits]. */
    fun code(base32Secret: String, nowMillis: Long, p: Params = Params()): String {
        val key = base32Decode(base32Secret)
        val counter = nowMillis / 1000L / p.periodSec
        return hotp(key, counter, p)
    }

    /** Seconds remaining in the current window — drives the countdown ring. */
    fun secondsRemaining(nowMillis: Long, periodSec: Int = 30): Int {
        val inWindow = (nowMillis / 1000L) % periodSec
        return (periodSec - inWindow).toInt()
    }

    /** True if [s] decodes to a non-empty key — used to validate input before saving. */
    fun isValidSecret(s: String): Boolean = try {
        base32Decode(s).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun hotp(key: ByteArray, counter: Long, p: Params): String {
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) { msg[i] = (c and 0xFF).toByte(); c = c shr 8 }

        val mac = Mac.getInstance(p.algorithm)
        mac.init(SecretKeySpec(key, "RAW"))
        val hash = mac.doFinal(msg)

        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val mod = Math.pow(10.0, p.digits.toDouble()).toInt()
        return (binary % mod).toString().padStart(p.digits, '0')
    }

    /** RFC 4648 Base32 decode, case-insensitive, ignoring spaces and '=' padding. */
    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trim().uppercase().replace(" ", "").trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)

        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) throw IllegalArgumentException("bad base32 char '$ch'")
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
