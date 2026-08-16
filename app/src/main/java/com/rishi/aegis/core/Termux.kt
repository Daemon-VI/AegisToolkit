package com.rishi.aegis.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Bridge to Termux via its RUN_COMMAND foreground-service intent.
 *
 * Requires, on the device (one-time, user-driven):
 *   1. Termux installed (F-Droid / GitHub build — NOT the abandoned Play Store one).
 *   2. `allow-external-apps=true` in ~/.termux/termux.properties (then termux-reload-settings).
 *   3. The com.termux.permission.RUN_COMMAND permission granted to Aegis.
 */
object TermuxBridge {

    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Result bundle keys (TermuxConstants.TERMUX_SERVICE)
    private const val RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERR = "err"
    private const val RESULT_ERRMSG = "errmsg"

    const val BIN = "/data/data/com.termux/files/usr/bin"
    const val HOME = "/data/data/com.termux/files/home"

    // Where to get a working Termux build
    const val FDROID_URL = "https://f-droid.org/en/packages/com.termux/"
    const val GITHUB_URL = "https://github.com/termux/termux-app/releases"

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val err: Int,
        val errmsg: String?,
    )

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Run [commandLine] through `bash -c` inside Termux in the background and return the captured
     * result, or null if Termux never replied within [timeoutMs] (usually a setup problem).
     */
    suspend fun run(ctx: Context, commandLine: String, timeoutMs: Long = 180_000): Result? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val action = "com.rishi.aegis.TERMUX_RESULT." + System.nanoTime()
                val cleaned = AtomicBoolean(false)
                var receiver: BroadcastReceiver? = null
                fun cleanup() {
                    if (cleaned.compareAndSet(false, true)) {
                        try {
                            receiver?.let { ctx.unregisterReceiver(it) }
                        } catch (_: Exception) {
                        }
                    }
                }
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        cleanup()
                        val b = intent?.getBundleExtra(RESULT_BUNDLE)
                        if (cont.isActive) {
                            cont.resume(
                                Result(
                                    stdout = b?.getString(RESULT_STDOUT).orEmpty(),
                                    stderr = b?.getString(RESULT_STDERR).orEmpty(),
                                    exitCode = b?.getInt(RESULT_EXIT_CODE, -1) ?: -1,
                                    err = b?.getInt(RESULT_ERR, -1) ?: -1,
                                    errmsg = b?.getString(RESULT_ERRMSG),
                                )
                            )
                        }
                    }
                }
                val recvFlags = if (Build.VERSION.SDK_INT >= 33) ContextCompat.RECEIVER_NOT_EXPORTED else 0
                ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), recvFlags)
                cont.invokeOnCancellation { cleanup() }

                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(action).setPackage(ctx.packageName), piFlags
                )

                try {
                    val intent = serviceIntent(commandLine, background = true).putExtra(EXTRA_PENDING_INTENT, pi)
                    ContextCompat.startForegroundService(ctx, intent)
                } catch (e: Exception) {
                    cleanup()
                    if (cont.isActive) {
                        cont.resume(Result("", "Failed to reach Termux: ${e.message}", -1, -1, e.message))
                    }
                }
            }
        }

    /** Open a visible Termux session running [commandLine] (for interactive / long-running tools). */
    fun runForeground(ctx: Context, commandLine: String) {
        ContextCompat.startForegroundService(ctx, serviceIntent(commandLine, background = false))
    }

    fun launchTermux(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    }

    fun openUrl(ctx: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun serviceIntent(commandLine: String, background: Boolean): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$BIN/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", commandLine))
            putExtra(EXTRA_WORKDIR, HOME)
            putExtra(EXTRA_BACKGROUND, background)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_COMMAND_LABEL, "Aegis")
            putExtra(EXTRA_COMMAND_DESCRIPTION, commandLine)
        }
}
