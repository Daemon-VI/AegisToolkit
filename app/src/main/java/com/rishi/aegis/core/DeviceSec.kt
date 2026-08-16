package com.rishi.aegis.core

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Read-only checks on this phone's own security posture, plus an audit of which installed apps hold
 * sensitive permissions. Everything here uses public framework APIs — no root, nothing leaves the
 * device.
 */
object DeviceSec {

    enum class Level { OK, WARN, BAD, INFO }

    data class Check(val label: String, val value: String, val level: Level, val note: String? = null)

    // ---------------- Security checkup ----------------

    fun checkup(ctx: Context): List<Check> {
        val out = mutableListOf<Check>()
        val cr = ctx.contentResolver

        // Screen lock
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = km.isDeviceSecure
        out += Check(
            "Screen lock", if (secure) "set" else "NONE",
            if (secure) Level.OK else Level.BAD,
            if (secure) null else "No PIN/pattern/password — anyone can open the phone. Set one.",
        )

        // Storage encryption
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enc = try { dpm.storageEncryptionStatus } catch (_: Exception) { -1 }
        val encActive = enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
            enc == 4 /* ACTIVE_DEFAULT_KEY */ || enc == 5 /* ACTIVE_PER_USER */
        out += Check(
            "Storage encryption", if (encActive) "active" else "not active ($enc)",
            if (encActive) Level.OK else Level.WARN,
        )

        // OS security patch level
        val patch = Build.VERSION.SECURITY_PATCH
        val patchAgeDays = patchAgeDays(patch)
        out += Check(
            "Security patch", patch.ifBlank { "unknown" },
            when {
                patchAgeDays == null -> Level.INFO
                patchAgeDays > 180 -> Level.WARN
                else -> Level.OK
            },
            patchAgeDays?.let { if (it > 180) "About $it days old — check for a system update." else null },
        )

        // Android version
        out += Check("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Level.INFO)

        // Root indicators
        val root = rootIndicators(ctx)
        out += Check(
            "Root indicators", if (root.isEmpty()) "none found" else root.joinToString(", "),
            if (root.isEmpty()) Level.OK else Level.BAD,
            if (root.isEmpty()) null else "A rooted / tampered device weakens every app's sandbox.",
        )

        // Developer options
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        out += Check("Developer options", if (dev) "ON" else "off", if (dev) Level.WARN else Level.OK)

        // USB debugging
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        out += Check(
            "USB debugging (ADB)", if (adb) "ON" else "off",
            if (adb) Level.WARN else Level.OK,
            if (adb) "Leave off when you're not actively developing/debugging." else null,
        )

        // Unknown-app installs for Aegis itself (can throw on some OEM builds)
        val canInstall = try {
            if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else false
        } catch (_: Exception) {
            null
        }
        out += Check(
            "Install unknown apps (Aegis)",
            when (canInstall) { true -> "allowed"; false -> "blocked"; else -> "unknown" },
            Level.INFO,
        )

        // VPN
        val vpn = vpnActive(ctx)
        out += Check("VPN tunnel", if (vpn) "active" else "none", Level.INFO)

        // User-installed CA certificates (MITM risk)
        val cas = userCaCerts()
        out += Check(
            "User-added CA certs", if (cas.isEmpty()) "none" else "${cas.size} installed",
            if (cas.isEmpty()) Level.OK else Level.WARN,
            if (cas.isEmpty()) null
            else "Extra root certificates can let someone intercept HTTPS:\n" + cas.joinToString("\n") { "  • $it" },
        )

        // Active device-admin apps
        val admins = try { dpm.activeAdmins ?: emptyList() } catch (_: Exception) { emptyList() }
        out += Check(
            "Device-admin apps", if (admins.isEmpty()) "none" else "${admins.size}",
            Level.INFO,
            if (admins.isEmpty()) null
            else admins.joinToString("\n") { "  • " + appLabel(ctx, it.packageName) },
        )

        return out
    }

    private fun rootIndicators(ctx: Context): List<String> {
        val found = mutableListOf<String>()
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/su",
            "/system/app/Superuser.apk", "/system/bin/magisk", "/data/adb/magisk", "/data/adb/ksu",
        )
        if (paths.any { runCatching { File(it).exists() }.getOrDefault(false) }) found += "su/magisk binary"
        if (Build.TAGS?.contains("test-keys") == true) found += "test-keys build"
        val rootPkgs = listOf("com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser")
        rootPkgs.forEach { pkg ->
            if (runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false))
                found += pkg.substringAfterLast('.')
        }
        return found
    }

    private fun userCaCerts(): List<String> = try {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        ks.aliases().toList().filter { it.startsWith("user:") }.mapNotNull { alias ->
            (ks.getCertificate(alias) as? X509Certificate)?.subjectX500Principal?.name ?: alias
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** Days between the build's security-patch date and the device clock; null if unparseable. */
    private fun patchAgeDays(patch: String): Long? {
        return try {
            val parts = patch.split("-")
            if (parts.size < 3) return null
            val cal = java.util.Calendar.getInstance().apply {
                clear(); set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
            (System.currentTimeMillis() - cal.timeInMillis) / 86_400_000L
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- Permission auditor ----------------

    data class AppEntry(val label: String, val pkg: String)
    data class PermGroup(val label: String, val apps: List<AppEntry>)
    data class SpecialAccess(val label: String, val apps: List<String>, val danger: Boolean, val note: String)

    private val SENSITIVE = listOf(
        "Camera" to listOf(Manifest.permission.CAMERA),
        "Microphone" to listOf(Manifest.permission.RECORD_AUDIO),
        "Fine location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        "Background location" to listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
        "Contacts" to listOf(Manifest.permission.READ_CONTACTS),
        "SMS" to listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS"),
        "Call log" to listOf("android.permission.READ_CALL_LOG"),
        "Phone" to listOf(Manifest.permission.READ_PHONE_STATE),
        "Body sensors" to listOf("android.permission.BODY_SENSORS"),
        "Calendar" to listOf(Manifest.permission.READ_CALENDAR),
    )

    fun permissionAudit(ctx: Context): List<PermGroup> {
        val pm = ctx.packageManager
        val pkgs = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) {
            emptyList()
        }
        return SENSITIVE.map { (label, perms) ->
            val apps = pkgs.mapNotNull { pi ->
                val requested = pi.requestedPermissions ?: return@mapNotNull null
                val flags = pi.requestedPermissionsFlags
                var granted = false
                for (i in requested.indices) {
                    if (requested[i] in perms) {
                        val isGranted = flags != null && i < flags.size &&
                            (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                        if (isGranted) { granted = true; break }
                    }
                }
                if (granted) AppEntry(appLabel(ctx, pi.packageName), pi.packageName) else null
            }.sortedBy { it.label.lowercase() }
            PermGroup(label, apps)
        }
    }

    /** The high-power "special access" grants that ordinary permission screens hide. */
    fun specialAccess(ctx: Context): List<SpecialAccess> {
        val cr = ctx.contentResolver
        val out = mutableListOf<SpecialAccess>()

        val acc = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        out += SpecialAccess(
            "Accessibility services", componentApps(ctx, acc), danger = true,
            "Can read everything on screen and act for you. Only keep ones you trust.",
        )

        val notif = Settings.Secure.getString(cr, "enabled_notification_listeners")
        out += SpecialAccess(
            "Notification access", componentApps(ctx, notif), danger = true,
            "Can read the content of all your notifications.",
        )

        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = try { dpm.activeAdmins ?: emptyList() } catch (_: Exception) { emptyList() }
        out += SpecialAccess(
            "Device administrators", admins.map { appLabel(ctx, it.packageName) }, danger = true,
            "Can enforce policies, and some can lock or wipe the device.",
        )

        return out
    }

    private fun componentApps(ctx: Context, colonList: String?): List<String> {
        if (colonList.isNullOrBlank()) return emptyList()
        return colonList.split(":").mapNotNull { comp ->
            val pkg = comp.substringBefore("/").trim()
            if (pkg.isEmpty()) null else appLabel(ctx, pkg)
        }.distinct()
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) {
        pkg
    }
}
