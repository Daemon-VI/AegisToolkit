package com.rishi.aegis.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Intruder Watch: catch whoever fails to unlock the phone.
 *
 * How it works (no root needed):
 *  - Aegis registers as a **Device Administrator** with the `watch-login` policy, so Android calls
 *    [AegisDeviceAdminReceiver.onPasswordFailed] on every wrong PIN / pattern / password at the
 *    lockscreen (and on biometric lock-outs that fall back to the PIN).
 *  - That callback starts [IntruderService], a camera foreground service that silently takes a
 *    front-camera photo and drops it in app-private storage.
 *  - The Capture Log screen reads those photos back.
 *
 * Everything is kept on-device in `filesDir/intruders/` — nothing leaves the phone.
 */
object Guardian {

    private const val PREFS = "aegis_guardian"
    private const val KEY_ARMED = "armed"
    private const val KEY_THRESHOLD = "threshold"
    private const val DIR = "intruders"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master on/off switch. Nothing is captured while disarmed. */
    fun isArmed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ARMED, false)

    fun setArmed(ctx: Context, armed: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ARMED, armed).apply()
    }

    /** Capture once the failed-attempt count reaches this (min 1 = every failed try). */
    fun threshold(ctx: Context): Int = prefs(ctx).getInt(KEY_THRESHOLD, 1).coerceAtLeast(1)

    fun setThreshold(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt(KEY_THRESHOLD, n.coerceIn(1, 10)).apply()
    }

    // ---- Device administrator ----

    fun adminComponent(ctx: Context) = ComponentName(ctx, AegisDeviceAdminReceiver::class.java)

    fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(ctx: Context): Boolean = dpm(ctx).isAdminActive(adminComponent(ctx))

    /** Intent that opens the system "activate device admin" screen. */
    fun enableAdminIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Aegis needs this so Android will tell it when someone fails to unlock your " +
                    "phone. It only watches for failed unlocks to trigger the camera — it does " +
                    "not lock, wipe, or change your password.",
            )

    fun disableAdmin(ctx: Context) {
        try {
            dpm(ctx).removeActiveAdmin(adminComponent(ctx))
        } catch (_: Exception) {
        }
    }

    fun hasCameraPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun hasFrontCamera(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

    /** Fully ready to capture on a real failed unlock. */
    fun isFullyArmed(ctx: Context): Boolean =
        isArmed(ctx) && isAdminActive(ctx) && hasCameraPermission(ctx)

    // ---- Capture trigger ----

    /**
     * Fire a capture for a failed unlock with [attempts] total failures so far. Called from the
     * device-admin callback and the in-app test button. Respects [isArmed] and [threshold];
     * [force] bypasses both (used by the test button).
     */
    fun trigger(ctx: Context, attempts: Int, force: Boolean = false) {
        if (!force) {
            if (!isArmed(ctx)) return
            if (attempts < threshold(ctx)) return
        }
        if (!hasCameraPermission(ctx)) return
        val intent = Intent(ctx, IntruderService::class.java)
            .putExtra(IntruderService.EXTRA_ATTEMPTS, attempts)
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (_: Exception) {
            // Background start can be blocked on some OEM builds; nothing else we can do here.
        }
    }

    // ---- Capture storage ----

    fun captureDir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun newCaptureFile(ctx: Context, attempts: Int): File =
        File(captureDir(ctx), "cap_${System.currentTimeMillis()}_$attempts.jpg")

    data class Capture(val file: File, val timeMillis: Long, val attempts: Int)

    /** All captures, newest first. */
    fun captures(ctx: Context): List<Capture> =
        captureDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.mapNotNull { f ->
                val parts = f.nameWithoutExtension.removePrefix("cap_").split("_")
                val millis = parts.getOrNull(0)?.toLongOrNull() ?: f.lastModified()
                val attempts = parts.getOrNull(1)?.toIntOrNull() ?: 0
                Capture(f, millis, attempts)
            }
            ?.sortedByDescending { it.timeMillis }
            ?: emptyList()

    fun delete(capture: Capture) {
        try {
            capture.file.delete()
        } catch (_: Exception) {
        }
    }

    fun clearAll(ctx: Context) {
        captureDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Foreground-service camera type is only meaningful on API 29+. */
    val supportsCameraFgsType: Boolean get() = Build.VERSION.SDK_INT >= 29
}
