package com.rishi.aegis.tools

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rishi.aegis.core.Guardian
import com.rishi.aegis.ui.AegisCyan
import com.rishi.aegis.ui.AegisError
import com.rishi.aegis.ui.AegisGreen
import com.rishi.aegis.ui.AegisWarn
import com.rishi.aegis.ui.Chip
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.SectionLabel
import com.rishi.aegis.ui.ToolPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GuardianScreens {

    @Composable
    fun Setup(onBack: () -> Unit) {
        val ctx = LocalContext.current
        var adminOn by remember { mutableStateOf(Guardian.isAdminActive(ctx)) }
        var camOk by remember { mutableStateOf(Guardian.hasCameraPermission(ctx)) }
        var armed by remember { mutableStateOf(Guardian.isArmed(ctx)) }
        var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
        var threshold by remember { mutableIntStateOf(Guardian.threshold(ctx)) }
        var status by remember { mutableStateOf("") }
        val hasFront = remember { Guardian.hasFrontCamera(ctx) }

        val camLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { camOk = it || Guardian.hasCameraPermission(ctx) }

        val adminLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { adminOn = Guardian.isAdminActive(ctx) }

        val overlayLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { overlayOk = Settings.canDrawOverlays(ctx) }

        ToolPage("Arm Guardian", onBack) {
            Note(
                "When someone fails to unlock this phone, Aegis silently takes a front-camera photo " +
                    "and saves it to the Capture Log. Use it only on a phone you own."
            )

            SectionLabel("Status")
            StatusRow("Device administrator", adminOn)
            StatusRow("Camera permission", camOk)
            StatusRow("Front camera present", hasFront)
            StatusRow("Background capture (overlay)", overlayOk)
            StatusRow("Armed", armed)

            SectionLabel("1 · Camera permission")
            Note("Needed to take the photo.")
            if (!camOk) {
                Button(onClick = { camLauncher.launch(android.Manifest.permission.CAMERA) }) {
                    Text("Grant camera")
                }
            } else {
                Note("Granted ✓")
            }

            SectionLabel("2 · Device administrator")
            Note(
                "Android only tells an app about failed unlocks if it's a device admin with the " +
                    "\"watch login\" policy. Aegis requests nothing else — it can't lock or wipe your phone."
            )
            if (!adminOn) {
                Button(onClick = { adminLauncher.launch(Guardian.enableAdminIntent(ctx)) }) {
                    Text("Activate device admin")
                }
            } else {
                Note("Active ✓")
                OutlinedButton(onClick = {
                    Guardian.disableAdmin(ctx)
                    adminOn = Guardian.isAdminActive(ctx)
                }) { Text("Deactivate") }
            }

            SectionLabel("3 · Background capture (recommended)")
            Note(
                "Newer Android blocks apps from using the camera from the background. Granting " +
                    "\"Display over other apps\" lets Aegis capture while the screen is locked. " +
                    "Without it, capture may only work while Aegis is open."
            )
            if (!overlayOk) {
                OutlinedButton(onClick = {
                    overlayLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                }) { Text("Allow display over apps") }
            } else {
                Note("Granted ✓")
            }

            SectionLabel("4 · Capture after")
            Note("How many failed PIN/pattern/password attempts before the first photo. 1 = every failed try.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 1..4) {
                    val sel = n == threshold
                    Box(Modifier.clickable {
                        threshold = n
                        Guardian.setThreshold(ctx, n)
                    }) {
                        Chip(if (n == 1) "1 (every)" else "$n", if (sel) AegisGreen else AegisCyan)
                    }
                }
            }

            SectionLabel("5 · Arm")
            SwitchRow(
                if (armed) "Guardian is armed" else "Guardian is off",
                armed,
            ) { on ->
                armed = on
                Guardian.setArmed(ctx, on)
                status = when {
                    on && Guardian.isFullyArmed(ctx) ->
                        "Armed. Aegis is now watching for wrong-PIN/pattern/password attempts."
                    on -> "Armed, but finish the steps above — capture won't fire until admin + camera are granted."
                    else -> "Disarmed. No photos will be taken."
                }
            }

            SectionLabel("Test it")
            Note("Fire a capture right now (bypasses the arm switch) and check the Capture Log.")
            Button(onClick = {
                if (!camOk) {
                    status = "Grant the camera permission first."
                } else {
                    Guardian.trigger(ctx, attempts = 1, force = true)
                    status = "Capturing… open the Capture Log in a moment to see the photo."
                }
            }) { Text("Test capture now") }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(status, color = AegisWarn, fontSize = 13.sp)
            }

            if (!hasFront) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No front camera detected — captures will use the default camera instead.",
                    color = AegisError,
                    fontSize = 13.sp,
                )
            }
        }
    }

    @Composable
    fun Log(onBack: () -> Unit) {
        val ctx = LocalContext.current
        var reloadKey by remember { mutableIntStateOf(0) }
        val captures = remember(reloadKey) { Guardian.captures(ctx) }

        ToolPage("Capture Log", onBack) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Note("${captures.size} capture${if (captures.size == 1) "" else "s"} — stored on this device only.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "refresh",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { reloadKey++ }.padding(4.dp),
                    )
                    if (captures.isNotEmpty()) {
                        Text(
                            "clear all",
                            color = AegisError,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                Guardian.clearAll(ctx)
                                reloadKey++
                            }.padding(4.dp),
                        )
                    }
                }
            }

            if (captures.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "No captures yet.\n\nArm Guardian, then a photo appears here the next time someone " +
                        "fails to unlock the phone. Use “Test capture now” on the Arm screen to try it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                return@ToolPage
            }

            for (cap in captures) {
                CaptureCard(
                    cap = cap,
                    onShare = { shareCapture(ctx, cap.file) },
                    onDelete = {
                        Guardian.delete(cap)
                        reloadKey++
                    },
                )
            }
        }
    }

    @Composable
    private fun CaptureCard(
        cap: Guardian.Capture,
        onShare: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                val bitmap by produceState<Bitmap?>(initialValue = null, cap.file.path) {
                    value = withContext(Dispatchers.IO) { loadRotated(cap.file, 900) }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Intruder capture",
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.height(28.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    formatTime(cap.timeMillis),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    "after ${cap.attempts} failed attempt${if (cap.attempts == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onShare) { Text("Share") }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // ---- helpers ----

    @Composable
    private fun StatusRow(label: String, ok: Boolean) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Chip(if (ok) "yes" else "no", if (ok) AegisGreen else AegisError)
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

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("EEE d MMM yyyy · HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun shareCapture(ctx: android.content.Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(
                Intent.createChooser(send, "Share capture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** Decode a downsampled bitmap and rotate it to match its EXIF orientation. */
    private fun loadRotated(file: File, reqWidth: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val degrees = when (
            runCatching {
                ExifInterface(file.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        return try {
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Exception) {
            bmp
        }
    }
}
