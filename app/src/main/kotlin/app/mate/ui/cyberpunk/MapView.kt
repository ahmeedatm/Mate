package app.mate.ui.cyberpunk

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.R
import app.mate.net.CyberpunkApi
import app.mate.net.CyberpunkMap
import app.mate.net.CyberpunkMapStatus
import app.mate.net.CyberpunkPos
import app.mate.net.CyberpunkServerPin
import app.mate.net.CyberpunkTracked
import app.mate.net.runCatchingCancellable
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP
import app.mate.ui.errorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Carte de Night City de la page Cyberpunk : tuiles du compagnon (pyramide WebP, cache taillé par `MapCamera.tileCacheBytes`), teintées rouge-ocre au
 * dessin, joueur en flèche jaune orientée par son yaw, objectif de quête, repères, trajet GPS. Glisser déplace, pincer ou
 * double toucher zoome, toucher le vide pose un repère, toucher un repère le fait suivre (re-toucher le suivi : retour à la
 * quête), appui long sur un repère le supprime. Tant que la carte n'a pas été déplacée à la main, elle suit le joueur.
 * Carte absente ou en préparation : fond de grille, bouton et progression.
 */
class MapView(
    private val kit: CyberpunkKit,
    private val api: CyberpunkApi,
    parentScope: CoroutineScope,
    /** Un repère a changé côté ordinateur : relire la liste. */
    private val onPinsChanged: () -> Unit,
    /** La préparation de la carte est lancée : relire son état. */
    private val onMapChanged: () -> Unit,
    private val say: (String) -> Unit,
) : FrameLayout(kit.context) {
    /** Une portée par ouverture de la page : [release] annule les tuiles et les requêtes en cours. */
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** Faux quand la page n'est plus en partie : ni repère posé, ni repère supprimé. */
    var interactive = true

    private var mapKey: List<Any?>? = null
    private var camera: MapCamera? = null
    private var cache: TileCache<Bitmap>? = null
    private var player: CyberpunkPos? = null
    private var tracked: CyberpunkTracked? = null
    private var questId: String? = null
    private var pins = emptyList<CyberpunkServerPin>()
    private var userMoved = false
    private var phase = 0f
    private var drawnPhase = 0f
    private var dialog: AlertDialog? = null

    private val surface = Surface(kit.context)
    private val statusText = kit.text("", 20f, R.color.cp_texte, kit.medium).apply { gravity = Gravity.CENTER }
    private val prepareButton = kit.button(context.getString(R.string.cp_map_prepare), context.getString(R.string.cp_map_prepare_description))
        .apply { setOnClickListener { prepare() } }
    private val statusPanel = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = kit.panel()
        setPadding(kit.dp(32), kit.dp(24), kit.dp(32), kit.dp(24))
        addView(statusText, kit.lp(WRAP, WRAP))
        addView(prepareButton, kit.lp(WRAP, kit.dp(52)).apply { topMargin = kit.dp(18) })
    }
    private val centerButton = kit.button(context.getString(R.string.cp_map_center), context.getString(R.string.cp_map_center_description), R.color.cp_panneau, R.color.cp_jaune)
        .apply {
            visibility = GONE
            setOnClickListener { recenter() }
        }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = WAVE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val p = it.animatedValue as Float
            // Un redessin toutes les ~40 ms suffit pour l'onde et le pouls (tablette à 55 Hz, peu de GPU).
            if (abs(p - drawnPhase) >= WAVE_STEP) {
                phase = p
                drawnPhase = p
                surface.invalidate()
            }
        }
    }

    init {
        contentDescription = context.getString(R.string.cp_map_description)
        addView(CyberpunkGridView(context, kit.color(R.color.cp_grille), kit.dp(GRID_DP)), LayoutParams(MATCH, MATCH))
        addView(surface, LayoutParams(MATCH, MATCH))
        addView(statusPanel, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(STATUS_BOTTOM_DP) })
        addView(centerButton, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = kit.dp(MARGIN_DP); marginEnd = kit.dp(CENTER_END_DP) })
        statusPanel.visibility = GONE
    }

    // --- Données

    fun setMap(map: CyberpunkMap) {
        val geo = if (map.status == CyberpunkMapStatus.READY) MapGeometry.of(map.width, map.height, map.levels, map.tile, map.affine) else null
        if (geo == null) {
            camera = null
            cache = null
            mapKey = null
            showStatus(map)
            surface.invalidate()
            return
        }
        statusPanel.visibility = GONE
        val key = listOf(map.width, map.height, map.levels, map.tile, map.affine)
        if (key == mapKey) return
        mapKey = key
        camera = MapCamera(geo).also { it.setViewport(surface.width, surface.height) }
        cache = TileCache(scope, cacheBytes(geo.tile), { it.byteCount }, { surface.postInvalidateOnAnimation() }) { k -> fetch(k) }
        userMoved = false
        centerButton.visibility = VISIBLE
        recenter()
        updateAnimation()
    }

    /** La vue ne dépasse jamais l'écran : le cache est taillé pour l'écran entier, avant même la mesure de la vue. */
    private fun cacheBytes(tile: Int) = resources.displayMetrics.let { MapCamera.tileCacheBytes(it.widthPixels, it.heightPixels, tile) }

    /** La page se ferme : requêtes de tuiles annulées, cache vidé. */
    fun release() {
        scope.cancel()
        cache = null
        camera = null
        mapKey = null
        dialog?.dismiss()
    }

    /** La lecture de l'état de la carte a échoué : phrase déjà rédigée (errorText). */
    fun setMapError(text: String) {
        camera = null
        cache = null
        mapKey = null
        showStatusText(text, retry = true)
        surface.invalidate()
    }

    fun update(pos: CyberpunkPos?, tracked: CyberpunkTracked?, questId: String?) {
        player = pos
        this.tracked = tracked
        this.questId = questId
        if (!userMoved) pos?.let { camera?.centerOn(it.x, it.y) }
        updateAnimation()
        surface.invalidate()
    }

    fun setPins(pins: List<CyberpunkServerPin>) {
        this.pins = pins
        updateAnimation()
        surface.invalidate()
    }

    private suspend fun fetch(k: TileKey): Bitmap? {
        val bytes = api.tile(k.z, k.x, k.y) ?: return null
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }
    }

    // --- État de la carte

    private fun showStatus(map: CyberpunkMap) {
        when (map.status) {
            CyberpunkMapStatus.LOADING -> showStatusText(context.getString(R.string.cp_map_loading, map.done, map.total), retry = false)
            CyberpunkMapStatus.ERROR -> showStatusText(context.getString(R.string.cp_map_failed), retry = true)
            else -> showStatusText(context.getString(R.string.cp_map_absent), retry = true)
        }
    }

    private fun showStatusText(text: String, retry: Boolean) {
        statusText.text = text
        prepareButton.visibility = if (retry) VISIBLE else GONE
        statusPanel.visibility = VISIBLE
        centerButton.visibility = GONE
    }

    private fun prepare() {
        prepareButton.visibility = GONE
        statusText.text = context.getString(R.string.cp_map_loading, 0, 0)
        scope.launch {
            runCatchingCancellable { api.prepareMap() }
                .onSuccess { onMapChanged() }
                .onFailure { setMapError(context.errorText(it)) }
        }
    }

    private fun recenter() {
        userMoved = false
        player?.let { camera?.centerOn(it.x, it.y) }
        surface.invalidate()
    }

    // --- Actions

    private fun launchAction(done: String?, call: suspend CyberpunkApi.() -> Unit) {
        scope.launch {
            runCatchingCancellable { api.call() }
                .onSuccess {
                    done?.let(say)
                    onPinsChanged()
                }
                .onFailure { say(context.errorText(it)) }
        }
    }

    private fun tapAt(x: Float, y: Float) {
        if (!interactive) return
        val cam = camera ?: return
        val hit = hitPin(x, y)
        when {
            hit == null -> {
                val w = cam.screenToWorld(x.toDouble(), y.toDouble())
                launchAction(context.getString(R.string.cp_pin_added)) { addPin(w.x, w.y, player?.z) }
            }
            hit.tracked -> questId?.let { id -> launchAction(context.getString(R.string.cp_pin_back_to_quest)) { action("track", kind = "quest", id = id) } }
            else -> launchAction(context.getString(R.string.cp_pin_followed)) { trackPin(hit.id) }
        }
    }

    private fun longPressAt(x: Float, y: Float) {
        if (!interactive) return
        val pin = hitPin(x, y) ?: return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.cp_pin_delete_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> launchAction(context.getString(R.string.cp_pin_deleted)) { deletePin(pin.id) } }
            .show()
    }

    /** Repère sous le doigt : cible de 56 dp centrée sur le corps du repère, pas sur sa pointe. */
    private fun hitPin(x: Float, y: Float): CyberpunkServerPin? {
        val cam = camera ?: return null
        val centers = pins.map { it.id to cam.worldToScreen(it.x, it.y).let { p -> Pt(p.x, p.y - kit.dp(PIN_R_DP) * PIN_BODY) } }
        val id = nearestPoint(centers, x.toDouble(), y.toDouble(), kit.dp(HIT_DP).toDouble())
        return pins.firstOrNull { it.id == id }
    }

    // --- Animation (une seule, coupée quand la vue est cachée ou rien ne bouge)

    private fun updateAnimation() {
        val wanted = camera != null && isShown && (player != null || pins.any { it.tracked })
        if (wanted && !animator.isStarted) animator.start() else if (!wanted && animator.isStarted) animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        dialog?.dismiss()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    // --- Dessin et gestes

    private inner class Surface(context: Context) : View(context) {
        private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = tintFilter() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = kit.dp(4).toFloat()
            color = kit.color(R.color.cp_jaune)
            alpha = GPS_ALPHA
            pathEffect = DashPathEffect(floatArrayOf(kit.dp(2).toFloat(), kit.dp(10).toFloat()), 0f)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = kit.dp(1).toFloat()
        }
        private val pinPath = pinPath(kit.dp(PIN_R_DP).toFloat())
        private val arrow = Path().apply {
            val h = kit.dp(13).toFloat()
            val w = kit.dp(11).toFloat()
            moveTo(0f, -h)
            lineTo(w, h)
            lineTo(0f, h * 0.45f)
            lineTo(-w, h)
            close()
        }
        private val diamond = Path().apply {
            val r = kit.dp(14).toFloat()
            moveTo(0f, -r)
            lineTo(r, 0f)
            lineTo(0f, r)
            lineTo(-r, 0f)
            close()
        }
        private val src = Rect()
        private val dst = RectF()
        private val matrix = Matrix()
        private val matrixValues = FloatArray(9)

        private val scale = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    zoom(d.scaleFactor.toDouble(), d.focusX, d.focusY)
                    return true
                }
            },
        ).apply { isQuickScaleEnabled = false }

        private val gestures = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    if (scale.isInProgress) return false
                    userMoved = true
                    camera?.panBy(-dx, -dy)
                    invalidate()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    tapAt(e.x, e.y)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val cam = camera ?: return true
                    zoom(if (cam.scale >= MapCamera.MAX_SCALE * ALMOST) MapCamera.INITIAL_SCALE / cam.scale else 2.0, e.x, e.y)
                    return true
                }

                override fun onLongPress(e: MotionEvent) = longPressAt(e.x, e.y)
            },
        )

        /** Tant que la carte suit le joueur, le zoom se fait autour de lui (le centre) ; sinon autour du doigt. */
        private fun zoom(factor: Double, x: Float, y: Float) {
            if (userMoved) camera?.zoomAt(factor, x, y) else camera?.zoomAt(factor, width / 2f, height / 2f)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            camera?.setViewport(w, h)
            if (!userMoved) player?.let { camera?.centerOn(it.x, it.y) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (camera == null) return false
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val cam = camera ?: return
            drawTiles(canvas, cam)
            drawRoute(canvas, cam)
            pins.filter { !it.tracked }.forEach { drawPin(canvas, cam, it, R.color.cp_cyan, 0f) }
            pins.firstOrNull { it.tracked }?.let { drawPin(canvas, cam, it, R.color.cp_jaune, -kit.dp(PULSE_DP) * abs(sin(phase * PI.toFloat()))) }
            objective()?.takeIf { pins.none { it.tracked } }?.let { p ->
                val s = cam.worldToScreen(p.x, p.y)
                fill.color = kit.color(R.color.cp_jaune)
                canvas.withAt(s) { drawPath(diamond, fill) }
            }
            player?.let { drawPlayer(canvas, cam, it) }
        }

        private fun objective(): Pt? = tracked?.takeIf { it.x != null && it.y != null }?.let { Pt(it.x!!, it.y!!) }

        /** Tuiles dessinées en pixels du niveau max, à travers la matrice de la caméra (rotation et perspective comprises). */
        private fun drawTiles(canvas: Canvas, cam: MapCamera) {
            val tiles = cache ?: return
            cam.matrix().forEachIndexed { i, v -> matrixValues[i] = v.toFloat() }
            matrix.setValues(matrixValues)
            canvas.save()
            canvas.concat(matrix)
            for (key in cam.visibleTiles()) {
                val bmp = tiles.get(key)
                if (bmp != null) {
                    drawTile(canvas, bmp, cam.tileMapRect(key), null)
                    continue
                }
                // En attendant la tuile nette : la tuile du niveau au-dessus déjà en mémoire, coupée à la place de celle-ci.
                var parent = key
                while (parent.z > 0) {
                    parent = TileKey(parent.z - 1, parent.x shr 1, parent.y shr 1)
                    val coarse = tiles.peek(parent) ?: continue
                    drawTile(canvas, coarse, cam.tileMapRect(parent), cam.tileMapRect(key))
                    break
                }
            }
            canvas.restore()
        }

        private fun drawTile(canvas: Canvas, bmp: Bitmap, r: MapRect, clip: MapRect?) {
            src.set(0, 0, bmp.width, bmp.height)
            dst.set(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
            if (clip == null) {
                canvas.drawBitmap(bmp, src, dst, tilePaint)
                return
            }
            canvas.save()
            canvas.clipRect(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat())
            canvas.drawBitmap(bmp, src, dst, tilePaint)
            canvas.restore()
        }

        private fun drawRoute(canvas: Canvas, cam: MapCamera) {
            val me = player ?: return
            val target = pins.firstOrNull { it.tracked }?.let { Pt(it.x, it.y) } ?: objective() ?: return
            val a = cam.worldToScreen(me.x, me.y)
            val b = cam.worldToScreen(target.x, target.y)
            canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line)
        }

        private fun drawPin(canvas: Canvas, cam: MapCamera, pin: CyberpunkServerPin, colorRes: Int, lift: Float) {
            val s = cam.worldToScreen(pin.x, pin.y)
            if (s.x < -OFFSCREEN || s.y < -OFFSCREEN || s.x > width + OFFSCREEN || s.y > height + OFFSCREEN) return
            canvas.withAt(s, lift) {
                fill.color = kit.color(colorRes)
                drawPath(pinPath, fill)
                fill.color = kit.color(R.color.cp_fond)
                drawCircle(0f, -kit.dp(PIN_R_DP) * PIN_BODY, kit.dp(PIN_R_DP) * PIN_HOLE, fill)
            }
        }

        private fun drawPlayer(canvas: Canvas, cam: MapCamera, me: CyberpunkPos) {
            val s = cam.worldToScreen(me.x, me.y)
            val yellow = kit.color(R.color.cp_jaune)
            canvas.withAt(s) {
                ring.color = yellow
                ring.alpha = (WAVE_ALPHA * (1f - phase)).toInt()
                drawCircle(0f, 0f, kit.dp(WAVE_DP) * (WAVE_MIN + (WAVE_MAX - WAVE_MIN) * phase), ring)
                // shortcut: yaw lu comme le jeu (0 = nord, positif vers la gauche), sens non mesuré en partie ; à vérifier à l'essai.
                rotate(-me.yaw.toFloat())
                fill.color = yellow
                drawPath(arrow, fill)
            }
        }

        private fun Canvas.withAt(p: Pt, dy: Float = 0f, draw: Canvas.() -> Unit) {
            save()
            translate(p.x.toFloat(), p.y.toFloat() + dy)
            draw()
            restore()
        }
    }

    private fun tintFilter(): ColorMatrixColorFilter {
        val tint = kit.color(R.color.cp_carte_teinte)
        fun row(channel: Int) = (channel / 255f).let { c ->
            floatArrayOf(LUMA_R * CONTRAST * c, LUMA_G * CONTRAST * c, LUMA_B * CONTRAST * c, 0f, BRIGHTNESS * c)
        }
        val m = row(android.graphics.Color.red(tint)) + row(android.graphics.Color.green(tint)) + row(android.graphics.Color.blue(tint)) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
        return ColorMatrixColorFilter(ColorMatrix(m))
    }

    private fun pinPath(r: Float) = Path().apply {
        addCircle(0f, -r * PIN_BODY, r, Path.Direction.CW)
        moveTo(-r * PIN_NECK, -r * PIN_BODY * PIN_NECK)
        lineTo(0f, 0f)
        lineTo(r * PIN_NECK, -r * PIN_BODY * PIN_NECK)
        close()
    }

    private companion object {
        const val GRID_DP = 64
        const val WAVE_MS = 2400L
        const val WAVE_STEP = 0.016f
        const val WAVE_DP = 40
        const val WAVE_MIN = 0.2f
        const val WAVE_MAX = 1.4f
        const val WAVE_ALPHA = 128
        const val GPS_ALPHA = 230
        const val PIN_R_DP = 11
        const val PIN_BODY = 2.2f
        const val PIN_HOLE = 0.4f
        const val PIN_NECK = 0.8f
        const val PULSE_DP = 4
        const val HIT_DP = 28
        const val OFFSCREEN = 80
        const val MARGIN_DP = 16

        /** À gauche de la carte de quête (360 dp + 2 marges de 16), loin du tiroir qui couvre la gauche. */
        const val CENTER_END_DP = 392
        const val STATUS_BOTTOM_DP = 110
        const val ALMOST = 0.99

        // Gris (luminance), contraste puis luminosité : la teinte `cp_carte_teinte` est le blanc de la carte.
        const val LUMA_R = 0.299f
        const val LUMA_G = 0.587f
        const val LUMA_B = 0.114f
        const val CONTRAST = 1.25f
        const val BRIGHTNESS = -25f
    }
}
