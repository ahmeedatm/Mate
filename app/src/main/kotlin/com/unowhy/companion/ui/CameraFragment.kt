package com.unowhy.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.camera.DocCamera
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DocEntry
import com.unowhy.companion.voice.Mic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Caméra, deux usages choisis à l'entrée du mode :
 * - montrer sur l'ordi (visualiseur) : la tablette filme avec sa caméra arrière (feuille, objet…) ; la page /camera
 *   s'ouvre seule sur l'ordinateur, qui rediffuse l'image ; « Rouvrir » n'apparaît que si cette page a été fermée ;
 * - envoyer une photo : aperçu sans envoi, la photo pleine taille part dans le dossier « Partage tablette ».
 * La caméra ne tourne que quand un usage est choisi et le mode affiché.
 */
class CameraFragment : Fragment() {

    private lateinit var preview: TextureView
    private lateinit var frame: FrameLayout
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var messageBox: View
    private lateinit var message: TextView
    private lateinit var action: Button
    private lateinit var stop: Button
    private lateinit var frozenHint: View
    private lateinit var freeze: Button
    private lateinit var photo: Button
    private lateinit var copy: Button
    private lateinit var title: TextView
    private lateinit var controls: LinearLayout
    private lateinit var choice: View
    private lateinit var closedBanner: View
    private val zoomButtons = mutableListOf<Pair<Float, Button>>()

    private var camera: DocCamera? = null
    private var client: BackendClient? = null
    private var sender: ExecutorService? = null
    private var started = false
    private var askedPermission = false
    private enum class Purpose { SHOW, PHOTO }
    /** null : écran de choix, caméra éteinte. Revenir dans le mode ramène au choix. */
    private var purpose: Purpose? = null
    private var zoom = 1f
    private var rotation = 0
    /** Page du visualiseur ouverte sur l'ordinateur : null tant qu'on ne sait pas (ouverture en cours). */
    private var windowOpen: Boolean? = null
    /** Dernière ouverture demandée : on laisse au navigateur le temps de se connecter avant de dire la page fermée. */
    private var openedAt = 0L
    private var ticks = 0
    /** Dernière photo envoyée (chemin relatif au dossier partagé) : celle que « Copier sur l'ordi » met dans le presse-papiers. */
    private var lastPhoto: String? = null
    /** Photo en cours d'envoi ou copie en cours : « Copier sur l'ordi » attend. */
    private var photoBusy = false

    // Lus depuis le fil de la caméra et le fil d'envoi.
    @Volatile private var frozen = false
    @Volatile private var nextFrameAt = 0L
    @Volatile private var online = true
    /** Images compressées pas encore envoyées (au plus [PIPELINE]) : on compresse la suivante pendant l'envoi. */
    private val inFlight = AtomicInteger()
    private val sentFrames = AtomicInteger()

    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderStatus(sentFrames.getAndSet(0))
            if (ticks++ % 2 == 0) checkWindow()
            main.postDelayed(this, 1_000)
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { sync() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = updateRotation()
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        preview = view.findViewById(R.id.camera_preview)
        frame = view.findViewById(R.id.camera_frame)
        dot = view.findViewById(R.id.camera_dot)
        status = view.findViewById(R.id.camera_status)
        messageBox = view.findViewById(R.id.camera_message_box)
        message = view.findViewById(R.id.camera_message)
        action = view.findViewById(R.id.camera_action)
        stop = view.findViewById(R.id.camera_stop)
        frozenHint = view.findViewById(R.id.camera_frozen)
        freeze = view.findViewById(R.id.camera_freeze)
        photo = view.findViewById(R.id.camera_photo)
        copy = view.findViewById(R.id.camera_copy)
        title = view.findViewById(R.id.camera_title)
        controls = view.findViewById(R.id.camera_controls)
        choice = view.findViewById(R.id.camera_choice)
        closedBanner = view.findViewById(R.id.camera_closed)
        view.findViewById<View>(R.id.camera_choose_show).setOnClickListener { choose(Purpose.SHOW) }
        view.findViewById<View>(R.id.camera_choose_photo).setOnClickListener { choose(Purpose.PHOTO) }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                applyTransform()
                sync()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = applyTransform()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        // L'aperçu garde le format 16:9 du flux, centré dans la place disponible.
        frame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> fitPreview(right - left, bottom - top) }
        // Largeur changée (panneau document ouvert ou fermé à côté) : la barre du bas se réajuste après la mise en page.
        controls.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) controls.post { fitControls() }
        }

        stop.setOnClickListener { choose(null) }
        freeze.setOnClickListener { setFrozen(!frozen) }
        photo.setOnClickListener { takePhoto() }
        copy.setOnClickListener { copyPhoto() }
        view.findViewById<Button>(R.id.camera_show).setOnClickListener { showOnComputer() }

        val zooms = view.findViewById<LinearLayout>(R.id.camera_zooms)
        val maxZoom = DocCamera.maxZoom(requireContext())
        for (value in ZOOMS.filter { it <= maxZoom }) {
            val button = Button(requireContext(), null, 0, R.style.Chip).apply {
                text = getString(R.string.camera_zoom, if (value % 1f == 0f) value.toInt().toString() else value.toString().replace('.', ','))
                // Jamais sur deux lignes (« 2 / × ») : fitControls garde la place de toutes les pastilles.
                isSingleLine = true
                setOnClickListener { setZoom(value) }
            }
            zooms.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
            zoomButtons += value to button
        }
        renderControls()
    }

    override fun onStart() {
        super.onStart()
        started = true
        requireContext().getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        sync()
    }

    override fun onStop() {
        started = false
        requireContext().getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        sync()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        // Le bouton « Copier sur l'ordi » ne survit pas à la sortie du mode.
        lastPhoto = null
        if (!hidden) {
            askedPermission = false
            purpose = null
            renderControls()
        }
        sync()
    }

    override fun onDestroyView() {
        stopCamera()
        Mic.release(Mic.CAMERA)
        super.onDestroyView()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Allume la caméra quand le mode est à l'écran (et la permission accordée), l'éteint sinon. */
    private fun sync() {
        if (view == null) return
        val visible = started && !isHidden
        // Mode Caméra à l'écran : le mot de réveil se met en pause.
        Mic.set(Mic.CAMERA, visible)
        choice.visibility = if (purpose == null) View.VISIBLE else View.GONE
        if (purpose == null) {
            stopCamera()
            showMessage(null)
            return
        }
        if (visible && !hasPermission()) {
            showMessage(getString(R.string.camera_permission), R.string.camera_grant) { permission.launch(Manifest.permission.CAMERA) }
            if (!askedPermission) {
                askedPermission = true
                permission.launch(Manifest.permission.CAMERA)
            }
            return
        }
        if (visible && preview.isAvailable) startCamera() else if (!visible) stopCamera()
    }

    private fun startCamera() {
        if (camera != null) return
        val texture = preview.surfaceTexture ?: return
        val backend = BackendClient(requireContext())
        val cam = DocCamera(requireContext(), ::wantFrame, ::sendFrame) { error -> main.post { if (camera != null) showMessage(error) } }
        if (!cam.available) {
            cam.close()
            showMessage(getString(R.string.camera_unavailable))
            return
        }
        client = backend
        sender = Executors.newSingleThreadExecutor()
        camera = cam
        frozen = false
        online = true
        inFlight.set(0)
        showMessage(null)
        updateRotation(force = true)
        cam.open(texture)
        cam.setZoom(zoom)
        renderControls()
        renderStatus(null)
        main.postDelayed(ticker, 1_000)
        // Montrer sur l'ordi : la fenêtre s'ouvre d'elle-même, sauf si elle l'est déjà (retour dans le mode).
        if (purpose == Purpose.SHOW) lifecycleScope.launch {
            val open = runCatching { backend.camera.viewers() > 0 }.getOrDefault(false)
            if (camera === cam && !open) showOnComputer() else if (camera === cam) setWindowOpen(true)
        }
    }

    private fun stopCamera() {
        val cam = camera ?: return
        camera = null
        cam.close()
        sender?.shutdown()
        sender = null
        main.removeCallbacks(ticker)
        postState("off")
        if (view != null) {
            renderControls()
            renderStatus(null)
        }
    }

    // --- Flux vers le Mac

    /**
     * Appelé par le fil de la caméra pour chaque image (25 à 30/s) : on saute celles que l'envoi ne pourrait pas suivre.
     * L'intervalle part du début de la compression (≈ 50 ms sur la 4.2), pas de sa fin.
     */
    private fun wantFrame(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (purpose != Purpose.SHOW || frozen || inFlight.get() >= PIPELINE || now < nextFrameAt) return false
        nextFrameAt = now + FRAME_INTERVAL_MS
        return true
    }

    private fun sendFrame(jpeg: ByteArray) {
        val backend = client ?: return
        val executor = sender ?: return
        inFlight.incrementAndGet()
        try {
            executor.execute {
                try {
                    backend.camera.sendFrame(jpeg)
                    sentFrames.incrementAndGet()
                    online = true
                } catch (e: Exception) {
                    online = false
                    nextFrameAt = SystemClock.elapsedRealtime() + RETRY_MS
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            inFlight.decrementAndGet() // caméra en cours d'arrêt
        }
    }

    private fun postState(state: String) {
        val backend = client ?: return
        // Photo : l'ordinateur n'a pas d'image en direct à annoncer.
        if (purpose != Purpose.SHOW && state != "off") return
        lifecycleScope.launch { runCatching { backend.camera.state(state, rotation) } }
    }

    private fun setFrozen(value: Boolean) {
        frozen = value
        renderControls()
        renderStatus(null)
        if (camera != null) postState(if (value) "frozen" else "live")
    }

    private fun setZoom(value: Float) {
        zoom = value
        camera?.setZoom(value)
        renderControls()
    }

    private fun takePhoto() {
        val cam = camera ?: return
        val backend = client ?: return
        photo.isEnabled = false
        setPhotoBusy(true)
        cam.takePhoto { jpeg ->
            main.post {
                if (jpeg == null || view == null) {
                    if (view != null) Toast.makeText(requireContext(), R.string.camera_photo_failed, Toast.LENGTH_SHORT).show()
                    photo.isEnabled = true
                    setPhotoBusy(false)
                    return@post
                }
                val file = File(File(requireContext().cacheDir, "photos").apply { mkdirs() }, "visualiseur.jpg")
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { file.writeBytes(jpeg) }
                        val saved = backend.docs.uploadPhoto(file)
                        // Seulement si l'on est toujours dans ce mode : « Copier sur l'ordi » vise cette photo-ci.
                        if (purpose != null && !isHidden) lastPhoto = saved
                        Toast.makeText(requireContext(), getString(R.string.camera_photo_sent, saved.substringAfterLast('/')), Toast.LENGTH_LONG).show()
                        // Photo pour l'ordi : on la montre à côté, comme dans Depuis l'ordi.
                        if (purpose == Purpose.PHOTO) (activity as? MainActivity)?.openDocument(
                            DocEntry(saved.substringAfterLast('/'), saved, "image", jpeg.size.toLong(), System.currentTimeMillis()),
                        )
                    } catch (e: Exception) {
                        toastError(e)
                    } finally {
                        photo.isEnabled = true
                        setPhotoBusy(false)
                    }
                }
            }
        }
    }

    /** Met la dernière photo envoyée dans le presse-papiers de l'ordinateur (le fichier y est déjà : rien n'est renvoyé). */
    private fun copyPhoto() {
        val path = lastPhoto ?: return
        setPhotoBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendClient(requireContext()).system.copyImageToComputer(path)
                Toast.makeText(requireContext(), R.string.camera_copied, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                toastError(e)
            } finally {
                setPhotoBusy(false)
            }
        }
    }

    private fun setPhotoBusy(busy: Boolean) {
        photoBusy = busy
        if (view != null) renderCopy()
    }

    /** « Copier sur l'ordi » : après une photo envoyée dans ce mode, en attente pendant un envoi ou une copie. */
    private fun renderCopy() {
        copy.visibility = if (purpose != null && lastPhoto != null) View.VISIBLE else View.GONE
        copy.isEnabled = !photoBusy
        fitControls()
    }

    /**
     * Barre du bas trop étroite (panneau document ouvert à côté : la Caméra n'a que la moitié de l'écran, ~590 dp) :
     * « Copier sur l'ordi », puis le bouton photo si ça ne suffit pas, ne gardent que leur icône (64 × 52 dp, libellé
     * pour l'accessibilité). Les pastilles de zoom gardent toutes leur place. En pleine largeur, rien ne change.
     */
    private fun fitControls() {
        if (view == null) return
        val width = controls.width - controls.paddingStart - controls.paddingEnd
        if (width <= 0) return
        val buttons = listOf(copy, photo)
        buttons.forEach { setIconOnly(it, false) }
        for (button in buttons) {
            if (naturalWidth() <= width) break
            if (button.isVisible) setIconOnly(button, true)
        }
        // Les mesures ci-dessous sont faites sans contrainte : la barre est remesurée pour de bon.
        controls.requestLayout()
    }

    /** Largeur que la barre du bas demanderait sans contrainte (boutons et pastilles visibles, marges comprises). */
    private fun naturalWidth(): Int {
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        return (0 until controls.childCount).map { controls.getChildAt(it) }.filter { it.visibility != View.GONE }.sumOf { child ->
            child.measure(free, free)
            val params = child.layoutParams as ViewGroup.MarginLayoutParams
            child.measuredWidth + params.marginStart + params.marginEnd
        }
    }

    private fun setIconOnly(button: Button, on: Boolean) {
        val label = getString(
            when {
                button === copy -> R.string.camera_copy
                purpose == Purpose.SHOW -> R.string.camera_photo
                else -> R.string.camera_take_photo
            },
        )
        button.text = if (on) "" else label
        button.contentDescription = if (on) label else null
        // Icône seule centrée : ni écart avant le texte, ni largeur minimale d'ActionButton (88 dp).
        button.compoundDrawablePadding = if (on) 0 else dp(10)
        button.minWidth = if (on) 0 else dp(88)
        button.minimumWidth = if (on) 0 else dp(88)
    }

    private fun showOnComputer() {
        openedAt = SystemClock.elapsedRealtime()
        setWindowOpen(null)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendClient(requireContext()).camera.openOnComputer()
            } catch (e: Exception) {
                setWindowOpen(false)
                toastError(e)
            }
        }
    }

    /** Toutes les 2 s : la page du visualiseur est-elle encore ouverte sur l'ordinateur ? */
    private fun checkWindow() {
        val backend = client ?: return
        if (purpose != Purpose.SHOW) return
        lifecycleScope.launch {
            val viewers = runCatching { backend.camera.viewers() }.getOrNull() ?: return@launch
            if (viewers > 0) setWindowOpen(true)
            else if (SystemClock.elapsedRealtime() - openedAt > OPEN_GRACE_MS) setWindowOpen(false)
        }
    }

    private fun setWindowOpen(value: Boolean?) {
        if (windowOpen == value) return
        windowOpen = value
        if (view != null) renderControls()
    }

    // --- Sens de l'image

    /** Rotation qui remet l'image du capteur à l'endroit pour le sens actuel de l'écran (tablette retournée = 180°). */
    private fun updateRotation(force: Boolean = false) {
        val cam = camera ?: return
        val display = when (view?.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val next = (cam.sensorOrientation - display + 360) % 360
        if (next == rotation && !force) return
        rotation = next
        applyTransform()
        postState(if (frozen) "frozen" else "live")
    }

    /** Seul le demi-tour est géré : la tablette est toujours en paysage. */
    private fun applyTransform() {
        val matrix = Matrix()
        if (rotation == 180) matrix.postRotate(180f, preview.width / 2f, preview.height / 2f)
        preview.setTransform(matrix)
    }

    private fun fitPreview(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        val ratio = DocCamera.STREAM.width.toFloat() / DocCamera.STREAM.height
        val w = minOf(width, (height * ratio).toInt())
        val h = (w / ratio).toInt()
        val params = preview.layoutParams as FrameLayout.LayoutParams
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        // Pas de nouvelle mise en page pendant celle en cours.
        preview.post { preview.layoutParams = params }
    }

    // --- Affichage

    /** Message au centre de l'aperçu, avec un bouton si `actionLabel` est donné ; null efface. */
    private fun showMessage(text: String?, actionLabel: Int? = null, onAction: (() -> Unit)? = null) {
        message.text = text
        messageBox.visibility = if (text == null) View.GONE else View.VISIBLE
        action.visibility = if (actionLabel == null) View.GONE else View.VISIBLE
        actionLabel?.let { action.setText(it) }
        action.setOnClickListener { onAction?.invoke() }
    }

    private fun choose(next: Purpose?) {
        purpose = next
        frozen = false
        windowOpen = null
        lastPhoto = null
        renderControls()
        sync()
    }

    private fun renderControls() {
        val show = purpose == Purpose.SHOW
        title.setText(when (purpose) {
            Purpose.SHOW -> R.string.camera_title_show
            Purpose.PHOTO -> R.string.camera_title_photo
            null -> R.string.mode_camera
        })
        controls.visibility = if (purpose == null) View.INVISIBLE else View.VISIBLE
        stop.visibility = if (purpose == null) View.GONE else View.VISIBLE
        dot.visibility = if (purpose == null) View.INVISIBLE else View.VISIBLE
        closedBanner.visibility = if (show && camera != null && windowOpen == false) View.VISIBLE else View.GONE
        freeze.visibility = if (show) View.VISIBLE else View.GONE
        photo.setText(if (show) R.string.camera_photo else R.string.camera_take_photo)
        freeze.setText(if (frozen) R.string.camera_resume else R.string.camera_freeze)
        freeze.setCompoundDrawablesRelativeWithIntrinsicBounds(if (frozen) R.drawable.ic_play else R.drawable.ic_pause, 0, 0, 0)
        frozenHint.visibility = if (frozen) View.VISIBLE else View.GONE
        zoomButtons.forEach { (value, button) -> button.isSelected = value == zoom }
        val ready = camera != null
        // Caméra éteinte : la dernière image resterait affichée, comme si elle filmait encore.
        preview.alpha = if (ready) 1f else 0f
        freeze.isEnabled = ready
        photo.isEnabled = ready
        stop.isEnabled = ready
        renderCopy()
    }

    /** `fps` : images envoyées dans la dernière seconde, null au démarrage ou après un changement d'état. */
    private fun renderStatus(fps: Int?) {
        val (text, color) = when {
            camera == null -> "" to R.attr.c_text_faint
            purpose == Purpose.PHOTO -> getString(R.string.camera_photo_preview) to R.attr.c_text_faint
            !online -> getString(R.string.camera_offline) to R.attr.c_ko
            frozen -> getString(R.string.camera_frozen) to R.attr.c_warn
            fps == null -> getString(R.string.camera_starting) to R.attr.c_text_faint
            windowOpen == null -> getString(R.string.camera_opening) to R.attr.c_text_faint
            else -> getString(R.string.camera_live, fps) to R.attr.c_ok
        }
        status.text = text
        dot.background.mutate().setTint(themeColor(requireContext(), color))
    }

    companion object {
        /** Une image sur deux de la caméra (25 à 30/s), soit environ 12/s : fluide, et la tablette garde du souffle. */
        private const val FRAME_INTERVAL_MS = 70L
        private const val RETRY_MS = 1_000L
        private const val OPEN_GRACE_MS = 8_000L
        private const val PIPELINE = 2
        private val ZOOMS = listOf(1f, 1.5f, 2f, 3f)
    }
}
