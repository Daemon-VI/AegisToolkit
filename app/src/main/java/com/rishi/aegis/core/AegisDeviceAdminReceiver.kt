package com.rishi.aegis.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

/**
 * Receives lockscreen events from Android once Aegis is an active device administrator with the
 * `watch-login` policy (see res/xml/device_admin.xml). We only listen — never lock, wipe, or
 * change the password.
 */
class AegisDeviceAdminReceiver : DeviceAdminReceiver {

    constructor() : super()

    /**
     * Called on every failed unlock attempt at the keyguard (wrong PIN / pattern / password, and
     * biometric lock-outs that fall back to the credential). Kick off a silent front-camera capture.
     */
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        val attempts = try {
            Guardian.dpm(context).currentFailedPasswordAttempts
        } catch (_: Exception) {
            1
        }.coerceAtLeast(1)
        Guardian.trigger(context, attempts)
    }
}
