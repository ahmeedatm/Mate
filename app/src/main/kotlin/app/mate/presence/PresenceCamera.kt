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

    /** Fermeture demandée : plus rien ne s'ouvre ni ne se signale ; le fil s'arrête quand la caméra est vraiment fermée. */
    private var closed = false

    /** `openCamera` lancé et sans réponse : la caméra peut encore s'ouvrir après la demande de fermeture. */
    private var opening = false
    private var whenClosed: (() -> Unit)? = null

    /** Ouvre la caméra avant ; la permission CAMERA doit être accordée. */
    @SuppressLint("MissingPermission")
    fun open() {
        handler.post {
            if (closed) return@post
            val id = frontCamera(manager) ?: return@post lost(null)
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val size = CameraChoice.smallest(yuvSizes(characteristics)) ?: return@post lost(null)
                reader = ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ onImage(it) }, handler)
                }
                val rate = CameraChoice.lowestRate(fpsRanges(characteristics))?.let { Range(it.first, it.second) }
                opening = true
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        // Fermeture demandée pendant l'ouverture : la caméra se referme aussitôt, onClosed arrête le fil.
                        if (closed) return camera.close()
                        device = camera
                        createSession(camera, rate)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        opening = false
                        camera.close()
                        device = null
                        lost(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        opening = false
                        camera.close()
                        device = null
                        lost(null)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (closed) finish()
                    }
                }, handler)
            } catch (e: Exception) {
                opening = false
                lost(e)
            }
        }
    }

    /**
     * Ferme la caméra ; [done] est appelé (depuis le fil de la caméra, une seule fois) quand elle est vraiment fermée
     * et le fil arrêté : une autre caméra peut alors s'ouvrir. L'objet ne sert plus ensuite.
     */
    fun close(done: () -> Unit = {}) {
        handler.post {
            if (closed) return@post
            closed = true
            whenClosed = done
            runCatching { session?.close() }
            session = null
            val camera = device
            device = null
            if (camera != null) {
                runCatching { camera.close() }
                // onClosed fait la suite ; sans réponse du système, on ne bloque pas l'appelant.
                handler.postDelayed({ finish() }, CLOSE_TIMEOUT_MS)
            } else if (opening) {
                handler.postDelayed({ finish() }, CLOSE_TIMEOUT_MS)
            } else {
                finish()
            }
        }
    }

    /** Fin de la fermeture : image, fil, puis l'appelant (une seule fois, même si le délai de secours arrive après). */
    private fun finish() {
        val done = whenClosed ?: return
        whenClosed = null
        reader?.close()
        reader = null
        thread.quitSafely()
        done()
    }

    @Suppress("DEPRECATION") // la variante à SessionConfiguration demande l'API 28
    private fun createSession(camera: CameraDevice, rate: Range<Int>?) {
        val surface: Surface = reader?.surface ?: return lost(null)
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        // Fermeture demandée pendant la configuration : close() a déjà fermé la caméra, la session aussi.
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
            if (closed || now - lastAnalysis < INTERVAL_MS) return
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

        /** Attente maximale de la fermeture réelle de la caméra avant de rendre la main. */
        private const val CLOSE_TIMEOUT_MS = 2_000L

        private fun yuvSizes(characteristics: CameraCharacteristics): List<Pair<Int, Int>> =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
                .orEmpty().map { it.width to it.height }

        private fun fpsRanges(characteristics: CameraCharacteristics): List<Pair<Int, Int>> =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.lower to it.upper }

        private fun frontCamera(manager: CameraManager): String? = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }

        /** Vrai si la tablette a une caméra avant. */
        fun available(context: Context): Boolean =
            runCatching { frontCamera(context.getSystemService(CameraManager::class.java)) != null }.getOrDefault(false)
    }
}
