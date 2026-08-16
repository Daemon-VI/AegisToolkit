package com.rishi.aegis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** TLS certificate inspection and HTTP security-header grading. */
object WebSec {

    // ---------------- TLS / certificate inspector ----------------

    data class CertInfo(
        val subject: String,
        val issuer: String,
        val serial: String,
        val notBefore: java.util.Date,
        val notAfter: java.util.Date,
        val sigAlg: String,
        val keyAlg: String,
        val keyBits: Int?,
        val sans: List<String>,
        val isCa: Boolean,
        val selfSigned: Boolean,
    )

    data class TlsResult(
        val host: String,
        val port: Int,
        val protocol: String?,
        val cipher: String?,
        val validated: Boolean,
        val validationError: String?,
        val certs: List<CertInfo>,
        val error: String? = null,
    )

    suspend fun inspectTls(host: String, port: Int = 443, timeoutMs: Int = 9000): TlsResult =
        withContext(Dispatchers.IO) {
            val chain: List<X509Certificate>
            var protocol: String? = null
            var cipher: String? = null
            try {
                // Phase 1: grab the chain even if untrusted/expired, using a non-throwing trust manager.
                val capture = object : X509TrustManager {
                    var seen: Array<out X509Certificate>? = null
                    override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                    override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) { seen = c }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sc = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<javax.net.ssl.TrustManager>(capture), java.security.SecureRandom())
                }
                (sc.socketFactory.createSocket() as SSLSocket).use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    s.soTimeout = timeoutMs
                    s.sslParameters = s.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                    }
                    s.startHandshake()
                    protocol = s.session.protocol
                    cipher = s.session.cipherSuite
                }
                chain = capture.seen?.toList() ?: emptyList()
            } catch (e: Exception) {
                return@withContext TlsResult(host, port, null, null, false, null, emptyList(), e.message)
            }

            // Phase 2: does the system trust store + hostname verifier accept it?
            var validated = false
            var validationError: String? = null
            try {
                (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    s.soTimeout = timeoutMs
                    s.sslParameters = s.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                    }
                    s.startHandshake()
                    val hostOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)
                    validated = hostOk
                    if (!hostOk) validationError = "certificate valid but hostname doesn't match"
                }
            } catch (e: Exception) {
                validationError = e.message
            }

            val infos = chain.map { c ->
                val subject = c.subjectX500Principal.name
                val issuer = c.issuerX500Principal.name
                CertInfo(
                    subject = subject,
                    issuer = issuer,
                    serial = c.serialNumber.toString(16),
                    notBefore = c.notBefore,
                    notAfter = c.notAfter,
                    sigAlg = c.sigAlgName,
                    keyAlg = c.publicKey.algorithm,
                    keyBits = when (val k = c.publicKey) {
                        is RSAPublicKey -> k.modulus.bitLength()
                        is ECPublicKey -> k.params.curve.field.fieldSize
                        else -> null
                    },
                    sans = runCatching {
                        c.subjectAlternativeNames?.mapNotNull { entry ->
                            val type = entry.getOrNull(0) as? Int
                            val value = entry.getOrNull(1)?.toString()
                            when (type) {
                                2 -> "DNS:$value"
                                7 -> "IP:$value"
                                else -> value
                            }
                        } ?: emptyList()
                    }.getOrDefault(emptyList()),
                    isCa = c.basicConstraints >= 0,
                    selfSigned = subject == issuer,
                )
            }
            TlsResult(host, port, protocol, cipher, validated, validationError, infos)
        }

    /** Weak-signature / small-key / expiry warnings for a cert, computed against the device clock. */
    fun certWarnings(c: CertInfo): List<String> {
        val warns = mutableListOf<String>()
        val now = System.currentTimeMillis()
        when {
            c.notAfter.time < now -> warns += "EXPIRED on ${c.notAfter}"
            c.notAfter.time - now < 30L * 86_400_000L -> warns += "expires soon (${c.notAfter})"
        }
        if (c.notBefore.time > now) warns += "not yet valid (starts ${c.notBefore})"
        val sig = c.sigAlg.lowercase()
        if ("md5" in sig || "sha1" in sig) warns += "weak signature (${c.sigAlg})"
        if (c.keyAlg == "RSA" && (c.keyBits ?: 0) in 1..2047) warns += "small RSA key (${c.keyBits} bit)"
        return warns
    }

    // ---------------- Security header auditor ----------------

    data class HeaderGrade(val header: String, val value: String?, val level: DeviceSec.Level, val advice: String)
    data class HeaderReport(val grade: String, val https: Boolean, val rows: List<HeaderGrade>)

    fun auditHeaders(result: NetEngine.HttpResult, https: Boolean): HeaderReport {
        val h = result.headers.associate { it.first.lowercase() to it.second }
        val rows = mutableListOf<HeaderGrade>()

        fun row(name: String, key: String, okAdvice: String, missAdvice: String, needed: Boolean = true) {
            val v = h[key]
            val level = when {
                v != null -> DeviceSec.Level.OK
                needed -> DeviceSec.Level.WARN
                else -> DeviceSec.Level.INFO
            }
            rows += HeaderGrade(name, v, level, if (v != null) okAdvice else missAdvice)
        }

        row(
            "Strict-Transport-Security", "strict-transport-security",
            "HSTS set — good.", if (https) "Missing — add HSTS to force HTTPS." else "N/A on plain HTTP.",
            needed = https,
        )
        row(
            "Content-Security-Policy", "content-security-policy",
            "CSP present.", "Missing — a strong CSP is the best defense against XSS.",
        )
        row(
            "X-Frame-Options", "x-frame-options",
            "Set — clickjacking mitigated.", "Missing — add DENY/SAMEORIGIN (or CSP frame-ancestors).",
        )
        row(
            "X-Content-Type-Options", "x-content-type-options",
            "nosniff set.", "Missing — add 'nosniff'.",
        )
        row(
            "Referrer-Policy", "referrer-policy",
            "Referrer-Policy set.", "Missing — consider 'strict-origin-when-cross-origin'.",
        )
        row(
            "Permissions-Policy", "permissions-policy",
            "Permissions-Policy set.", "Missing — restrict powerful features (camera, geolocation…).",
            needed = false,
        )

        // Information leakage
        h["server"]?.let {
            rows += HeaderGrade("Server", it, DeviceSec.Level.WARN, "Reveals server software/version — consider hiding.")
        }
        h["x-powered-by"]?.let {
            rows += HeaderGrade("X-Powered-By", it, DeviceSec.Level.WARN, "Reveals the framework — remove it.")
        }

        val scored = rows.filter { it.level != DeviceSec.Level.INFO }
        val good = scored.count { it.level == DeviceSec.Level.OK }
        val grade = when {
            scored.isEmpty() -> "—"
            good == scored.size -> "A"
            good >= scored.size * 4 / 5 -> "B"
            good >= scored.size * 3 / 5 -> "C"
            good >= scored.size * 2 / 5 -> "D"
            good >= scored.size / 5 -> "E"
            else -> "F"
        }
        return HeaderReport(grade, https, rows)
    }
}
