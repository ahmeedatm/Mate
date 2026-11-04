package app.mate.presence

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface

/**
 * Caméra avant en capteur de mouvement, sans aucun aperçu : plus petite image YUV proposée, plan de luminance réduit à
 * [GRID_W]×[GRID_H] octets, une image sur [INTERVAL_MS] ms analysée (les autres sont rendues sans être lues), cadence
 * la plus basse du capteur. Aucune image n'est copiée, envoyée ni gardée : seul le résultat « mouvement ou non » sort.
 * Camera2 (API 21), comme DocCamera.
 *
 * [onMotion] est appelé depuis le fil de la caméra, avec le résultat de chaque image analysée ; [onLost] si la caméra
 * ne s'ouvre pas, est prise par une autre appli ou tombe en erreur.
 */
class PresenceCamera(
    context: Context,
    private val onMotion: (moved: Boolean) -> Unit,
    private val onLost: () -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("presence-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val detector = MotionDetector()
    private val luma = ByteArray(GRID_W * GRID_H)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastAnalysis = 0L
    private var closed = false

    /** Ouvre la caméra avant ; la permission CAMERA doit être accordée. */
    @SuppressLint("MissingPermission")
    fun open() {
        handler.post {
            if (closed) return@post
            val id = frontCamera(manager) ?: return@post lost(null)
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val size = smallest(characteristics) ?: return@post lost(null)
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ onImage(it) }, handler)
                }
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) return camera.close()
                        device = camera
                        createSession(camera, lowestRate(characteristics))
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                        lost(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                        lost(null)
                    }
                }, handler)
            } catch (e: Exception) {
                lost(e)
            }
        }
    }

    /** Ferme la caméra et son fil ; l'objet ne sert plus ensuite. */
    fun close() {
        handler.post {
            closed = true
            runCatching { session?.close() }
            runCatching { device?.close() }
            reader?.close()
            session = null
            device = null
            thread.quitSafely()
        }
    }

    @Suppress("DEPRECATION") // la variante à SessionConfiguration demande l'API 28
    private fun createSession(camera: CameraDevice, rate: Range<Int>?) {
        val surface: Surface = reader?.surface ?: return lost(null)
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        if (closed) return created.close()
                        session = created
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                rate?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            }
                            created.setRepeatingRequest(request.build(), null, handler)
                        } catch (e: Exception) {
                            lost(e)
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) = lost(null)
                },
                handler,
            )
        } catch (e: Exception) {
            lost(e)
        }
    }

    private fun onImage(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < INTERVAL_MS) return
            lastAnalysis = now
            val plane = image.planes[0]
            MotionDetector.reduce(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height, luma, GRID_W, GRID_H)
            onMotion(detector.moved(luma))
        } finally {
            image.close()
        }
    }

    private fun lost(e: Exception?) {
        if (e != null) Log.w(TAG, "Caméra avant", e)
        if (!closed) onLost()
    }

    companion object {
        private const val TAG = "PresenceCamera"

        /** Image réduite comparée d'une analyse à l'autre : 32×24 = 768 octets. */
        const val GRID_W = 32
        const val GRID_H = 24

        /** Une analyse toutes les 500 ms : assez pour voir quelqu'un arriver, très peu pour le processeur. */
        const val INTERVAL_MS = 500L

        /** Plus petite image YUV d'au moins 160×120 (de quoi réduire à 32×24 sans trou). */
        private fun smallest(characteristics: CameraCharacteristics): Size? =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { it.width >= 160 && it.height >= 120 }
                ?.minByOrNull { it.width.toLong() * it.height }

        /** Cadence la plus basse proposée (la plus stable parmi les plus basses) : le capteur produit moins d'images. */
        private fun lowestRate(characteristics: CameraCharacteristics): Range<Int>? =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })

        private fun frontCamera(manager: CameraManager): String? = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }

        /** Vrai si la tablette a une caméra avant. */
        fun available(context: Context): Boolean = runCatching { frontCamera(context.getSystemService(CameraManager::class.java)) != null }.getOrDefault(false)
    }
}
