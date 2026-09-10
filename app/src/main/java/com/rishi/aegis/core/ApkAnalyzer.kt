package com.rishi.aegis.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Offline static analysis of installed apps — no network, no root. For a chosen package we surface
 * the three things that actually describe its risk surface:
 *
 *  - **Dangerous permissions** it can request (protection level `dangerous`).
 *  - **Exported components** — activities/services/receivers/providers reachable by other apps; the
 *    ones with no guarding permission are the real attack surface.
 *  - **Signer** — the SHA-256 of the signing certificate, and whether it's a debug key.
 *  - **Trackers** — third-party analytics/ad SDKs, detected by matching the app's *manifest-declared*
 *    component class names against a bundled signature list ([Trackers]). This is the same idea as
 *    Exodus Privacy; it catches SDKs that register components (most do) and is honest about the rest.
 */
object ApkAnalyzer {

    data class AppEntry(val label: String, val pkg: String, val system: Boolean)

    data class Component(val type: String, val name: String, val permissionGuarded: Boolean)

    data class Report(
        val label: String,
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val system: Boolean,
        val debugSigned: Boolean,
        val signerSha256: String,
        val dangerousPermissions: List<String>,
        val totalPermissions: Int,
        val exported: List<Component>,
        val trackers: List<String>,
        val riskScore: Int,        // 0..100, rough
    )

    /** All launchable/user-visible apps, user apps first, alphabetical. */
    suspend fun installedApps(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        pm.getInstalledApplications(0)
            .map { ai ->
                AppEntry(
                    label = pm.getApplicationLabel(ai).toString(),
                    pkg = ai.packageName,
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    suspend fun analyze(ctx: Context, pkg: String): Report? = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val info: PackageInfo = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(
                pkg,
                PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_PROVIDERS or
                    signingFlag(),
            )
        } catch (_: Exception) {
            return@withContext null
        }
        val ai = info.applicationInfo ?: return@withContext null

        val requested = info.requestedPermissions?.toList() ?: emptyList()
        val dangerous = requested.filter { isDangerous(pm, it) }.sorted()

        val comps = buildList {
            info.activities?.forEach { add(Comp("Activity", it.name ?: "", !it.permission.isNullOrEmpty(), it.exported)) }
            info.services?.forEach { add(Comp("Service", it.name ?: "", !it.permission.isNullOrEmpty(), it.exported)) }
            info.receivers?.forEach { add(Comp("Receiver", it.name ?: "", !it.permission.isNullOrEmpty(), it.exported)) }
            info.providers?.forEach {
                val guarded = !it.readPermission.isNullOrEmpty() || !it.writePermission.isNullOrEmpty()
                add(Comp("Provider", it.name ?: "", guarded, it.exported))
            }
        }
        val exported = comps
            .filter { it.name.isNotEmpty() && it.exportedFlag }
            .map { Component(it.type, it.name, it.permissionGuarded) }

        val trackers = Trackers.detect(comps.map { it.name } + pkg)

        val signer = signerFingerprint(info)
        val debugSigned = signer.debug

        val report = Report(
            label = pm.getApplicationLabel(ai).toString(),
            pkg = pkg,
            versionName = info.versionName ?: "?",
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
            minSdk = if (Build.VERSION.SDK_INT >= 24) ai.minSdkVersion else 0,
            targetSdk = ai.targetSdkVersion,
            system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            debugSigned = debugSigned,
            signerSha256 = signer.sha256,
            dangerousPermissions = dangerous,
            totalPermissions = requested.size,
            exported = exported,
            trackers = trackers,
            riskScore = score(dangerous.size, exported.count { !it.permissionGuarded }, trackers.size, debugSigned),
        )
        report
    }

    // ---- internals ----

    /** Intermediate holder so we can keep the raw exported flag while mapping. */
    private data class Comp(
        val type: String, val name: String, val permissionGuarded: Boolean, val exportedFlag: Boolean,
    )

    private val dangerousCache = HashMap<String, Boolean>()

    private fun isDangerous(pm: PackageManager, perm: String): Boolean =
        dangerousCache.getOrPut(perm) {
            try {
                val pi = pm.getPermissionInfo(perm, 0)
                val level = if (Build.VERSION.SDK_INT >= 28) pi.protection
                else @Suppress("DEPRECATION") (pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
                level == PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: Exception) {
                // Unknown/custom permission we can't resolve — treat conservatively as not-dangerous.
                false
            }
        }

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private data class Signer(val sha256: String, val debug: Boolean)

    private fun signerFingerprint(info: PackageInfo): Signer {
        val certBytes: ByteArray? = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo
                val sigs = si?.apkContentsSigners ?: si?.signingCertificateHistory
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
        if (certBytes == null) return Signer("unavailable", false)
        val sha = MessageDigest.getInstance("SHA-256").digest(certBytes)
        val hex = sha.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        // Android's universal debug key has this well-known SHA-256.
        val debug = hex.equals(ANDROID_DEBUG_SHA256, ignoreCase = true)
        return Signer(hex, debug)
    }

    private fun score(dangerous: Int, unguardedExports: Int, trackers: Int, debug: Boolean): Int {
        var s = dangerous * 6 + unguardedExports * 4 + trackers * 5
        if (debug) s += 15
        return s.coerceIn(0, 100)
    }

    private const val ANDROID_DEBUG_SHA256 =
        "A4:0D:A8:0A:59:D1:70:CA:A9:50:CF:15:C1:8C:45:4D:47:A3:9B:26:98:9D:8B:64:0E:CD:74:5B:A7:1B:F5:DC"
}
