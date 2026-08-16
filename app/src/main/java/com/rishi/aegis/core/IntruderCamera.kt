package com.rishi.aegis.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless front-camera still capture using Camera2 — no preview shown on screen. Opens the
 * front camera, lets 3A settle briefly against a throwaway surface, grabs one JPEG, writes it to
 * [outFile], then tears everything down. Safe to call with the screen locked as long as the caller
 * is a camera foreground service.
 */
object IntruderCamera {

    private const val WARMUP_MS = 700L      // let auto-exposure/focus converge before the shot
    private const val HARD_TIMEOUT_MS = 6000L

    /**
     * @param onComplete invoked exactly once, true if a JPEG was written to [outFile].
     */
    @SuppressLint("MissingPermission") // caller verifies CAMERA permission first
    fun capture(ctx: Context, outFile: File, onComplete: (Boolean) -> Unit) {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("aegis-cam").apply { start() }
        val handler = Handler(thread.looper)
        val finished = AtomicBoolean(false)

        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var dummySurface: Surface? = null
        var dummyTexture: SurfaceTexture? = null

        fun done(success: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { dummySurface?.release() } catch (_: Exception) {}
            try { dummyTexture?.release() } catch (_: Exception) {}
            try { thread.quitSafely() } catch (_: Exception) {}
            onComplete(success)
        }

        // Bail out no matter what after a hard timeout so the service never hangs.
        handler.postDelayed({ done(finished.get() && outFile.exists()) }, HARD_TIMEOUT_MS)

        try {
            val cameraId = frontCameraId(manager) ?: run { done(false); return }
            val chars = manager.getCameraCharacteristics(cameraId)
            val size = pickSize(chars)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            reader!!.setOnImageAvailableListener({ r ->
                val image = try { r.acquireLatestImage() } catch (_: Exception) { null }
                var ok = false
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        FileOutputStream(outFile).use { it.write(bytes) }
                        ok = outFile.length() > 0
                    } catch (_: Exception) {
                        ok = false
                    } finally {
                        try { image.close() } catch (_: Exception) {}
                    }
                }
                done(ok)
            }, handler)

            dummyTexture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
            dummySurface = Surface(dummyTexture)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val targets = listOf(dummySurface!!, reader!!.surface)
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                session = cs
                                try {
                                    // Warm up 3A against the throwaway surface.
                                    val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(dummySurface!!)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    cs.setRepeatingRequest(preview.build(), null, handler)
                                    handler.postDelayed({ fireStill(device, cs, reader!!, dummySurface!!, sensorOrientation, handler) }, WARMUP_MS)
                                } catch (_: Exception) {
                                    done(false)
                                }
                            }

                            override fun onConfigureFailed(cs: CameraCaptureSession) = done(false)
                        }, handler)
                    } catch (_: Exception) {
                        done(false)
                    }
                }

                override fun onDisconnected(device: CameraDevice) = done(false)
                override fun onError(device: CameraDevice, error: Int) = done(false)
            }, handler)
        } catch (_: Exception) {
            done(false)
        }
    }

    private fun fireStill(
        device: CameraDevice,
        cs: CameraCaptureSession,
        reader: ImageReader,
        preview: Surface,
        sensorOrientation: Int,
        handler: Handler,
    ) {
        try {
            cs.stopRepeating()
            val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Assume the phone is held upright (portrait) — the common case at the lockscreen.
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }
            cs.capture(still.build(), null, handler)
        } catch (_: Exception) {
            // onImageAvailable won't fire; the hard timeout in capture() will finish us.
        }
    }

    private fun frontCameraId(manager: CameraManager): String? =
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: manager.cameraIdList.firstOrNull()

    /** A modest JPEG size — big enough to recognise a face, small enough to grab fast. */
    private fun pickSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 960)
        // Largest size at or below ~2 megapixels; else the smallest available.
        return sizes.filter { it.width.toLong() * it.height <= 2_100_000L }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 960)
    }
}
