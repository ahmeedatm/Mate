package com.unowhy.companion.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Caméra arrière en visualiseur : aperçu à l'écran, images 720p compressées en JPEG pour le flux vers le Mac,
 * photo pleine définition à la demande. Aperçu + YUV + JPEG max : combinaison garantie même au niveau LEGACY
 * (la 4.2 est LIMITED). Le flux passe par du YUV compressé ici plutôt que par un 2e flux JPEG, non garanti.
 *
 * Tout ce qui touche à la caméra tourne sur un fil dédié ; [onFrame], [onFailure] et le rappel de [takePhoto]
 * sont appelés depuis ce fil.
 */
class DocCamera(
    context: Context,
    /** Demandé avant chaque image : faux = image sautée sans être compressée (envoi en cours, image figée…). */
    private val wantFrame: () -> Boolean,
    private val onFrame: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("doc-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private val cameraId: String? = backCamera(manager)
    private val characteristics = cameraId?.let { manager.getCameraCharacteristics(it) }
    private val activeArray: Rect? = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

    /** Orientation du capteur par rapport à l'écran naturel (0 sur la 4.2). */
    val sensorOrientation: Int = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    /**
     * Cadence la plus haute et la plus stable proposée ([30, 30] sur la 4.2) : par défaut ([5, 30]), l'exposition
     * s'allonge dès que la lumière baisse et le flux tombe à 6 images/s.
     */
    private val fpsRange: Range<Int>? = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    val maxZoom: Float = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val available: Boolean get() = cameraId != null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var frames: ImageReader? = null
    private var stills: ImageReader? = null
    private var zoom = 1f
    private var pendingPhoto: ((ByteArray?) -> Unit)? = null
    private var closed = false

    // Tampons du flux, réutilisés d'une image à l'autre (sinon 1,4 Mo alloués par image, et le ramasse-miettes rame).
    private val nv21 = ByteArray(STREAM.width * STREAM.height * 3 / 2)
    private val row = ByteArray(STREAM.width * 2)
    private val jpeg = ByteArrayOutputStream(128 * 1024)

    /** Ouvre la caméra ; l'aperçu s'affiche sur [preview]. La permission CAMERA doit être accordée. */
    @SuppressLint("MissingPermission")
    fun open(preview: SurfaceTexture) {
        val id = cameraId ?: return onFailure("Pas de caméra arrière")
        handler.post {
            if (closed) return@post
            preview.setDefaultBufferSize(STREAM.width, STREAM.height)
            previewSurface = Surface(preview)
            frames = ImageReader.newInstance(STREAM.width, STREAM.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader -> onStreamImage(reader) }, handler)
            }
            val stillSize = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
            stills = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ reader -> onStillImage(reader) }, handler)
            }
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) return camera.close()
                        device = camera
                        createSession(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                        onFailure("Caméra prise par une autre appli")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                        onFailure("Caméra indisponible (erreur $error)")
                    }
                }, handler)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** 1 = champ entier ; au-delà, recadrage au centre du capteur 5 Mpx (plus de détail qu'un agrandissement du 720p). */
    fun setZoom(value: Float) {
        handler.post {
            zoom = value.coerceIn(1f, maxZoom)
            startPreview()
        }
    }

    /** Photo pleine définition, avec le même cadrage que l'aperçu ; null si la caméra n'est pas prête. */
    fun takePhoto(onDone: (ByteArray?) -> Unit) {
        handler.post {
            val camera = device
            val current = session
            val target = stills?.surface
            if (camera == null || current == null || target == null || pendingPhoto != null) return@post onDone(null)
            pendingPhoto = onDone
            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(target)
                    previewSurface?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                }
                current.capture(request.build(), null, handler)
            } catch (e: Exception) {
                pendingPhoto = null
                onDone(null)
            }
        }
    }

    /** Ferme la caméra et son fil ; l'objet ne sert plus ensuite. */
    fun close() {
        handler.post {
            closed = true
            runCatching { session?.close() }
            runCatching { device?.close() }
            frames?.close()
            stills?.close()
            previewSurface?.release()
            session = null
            device = null
            pendingPhoto?.invoke(null)
            pendingPhoto = null
            thread.quitSafely()
        }
    }

    @Suppress("DEPRECATION") // la variante à SessionConfiguration demande l'API 28
    private fun createSession(camera: CameraDevice) {
        val surfaces = listOfNotNull(previewSurface, frames?.surface, stills?.surface)
        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) {
                    if (closed) return created.close()
                    session = created
                    startPreview()
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) = onFailure("Configuration de la caméra refusée")
            }, handler)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Détail technique dans le journal, phrase simple à l'écran. */
    private fun fail(e: Exception) {
        Log.w("DocCamera", "Caméra", e)
        onFailure("Caméra indisponible : quitte le mode Caméra et reviens")
    }

    private fun startPreview() {
        val camera = device ?: return
        val current = session ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewSurface?.let { addTarget(it) }
                frames?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
            }
            current.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun cropRegion(): Rect? {
        val array = activeArray ?: return null
        if (zoom <= 1f) return null
        val w = (array.width() / zoom).toInt()
        val h = (array.height() / zoom).toInt()
        val left = array.left + (array.width() - w) / 2
        val top = array.top + (array.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun onStreamImage(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (!wantFrame()) return
            toNv21(image, nv21, row)
            jpeg.reset()
            YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                .compressToJpeg(Rect(0, 0, image.width, image.height), STREAM_QUALITY, jpeg)
            onFrame(jpeg.toByteArray())
        } finally {
            image.close()
        }
    }

    private fun onStillImage(reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        pendingPhoto?.invoke(bytes)
        pendingPhoto = null
    }

    companion object {
        /** Taille du flux et de l'aperçu : la limite de l'encodeur matériel de la 4.2, et lisible une fois projeté. */
        val STREAM = Size(1280, 720)
        private const val STREAM_QUALITY = 70

        private fun backCamera(manager: CameraManager): String? = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        /** Zoom numérique maximal de la caméra arrière (4 sur la 4.2), sans l'ouvrir. */
        fun maxZoom(context: Context): Float {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = backCamera(manager) ?: return 1f
            return manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }

        /** YUV_420_888 (plans séparés, pas et foulée propres au fabricant) → NV21 (Y puis V/U entrelacés). */
        /** `out` : taille × 1,5 ; `row` : au moins une ligne du plan le plus large (largeur × pas des pixels). */
        private fun toNv21(image: Image, out: ByteArray, row: ByteArray) {
            val w = image.width
            val h = image.height
            val (y, u, v) = image.planes
            copyPlane(y, w, h, out, 0, row)
            copyPlane(v, w / 2, h / 2, out, w * h, row, 2)
            copyPlane(u, w / 2, h / 2, out, w * h + 1, row, 2)
        }

        private fun copyPlane(plane: Image.Plane, width: Int, height: Int, out: ByteArray, offset: Int, row: ByteArray, outStep: Int = 1) {
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            // La dernière ligne peut s'arrêter juste après son dernier pixel : on ne lit que ce qui sert.
            val rowLength = (width - 1) * pixelStride + 1
            var o = offset
            for (r in 0 until height) {
                buffer.position(r * rowStride)
                if (pixelStride == 1 && outStep == 1) {
                    buffer.get(out, o, width)
                    o += width
                } else {
                    buffer.get(row, 0, rowLength)
                    for (c in 0 until width) {
                        out[o] = row[c * pixelStride]
                        o += outStep
                    }
                }
            }
        }
    }
}
