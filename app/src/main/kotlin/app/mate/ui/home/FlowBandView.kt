package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.SpringAnimation
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.home.FlowBandModel.BAND
import app.mate.ui.home.FlowBandModel.HEIGHT
import app.mate.ui.home.FlowBandModel.OUTLINE
import app.mate.ui.home.FlowBandModel.PERIOD
import app.mate.ui.home.FlowBandModel.RADII
import app.mate.ui.home.FlowBandModel.WAVE
import app.mate.ui.home.FlowBandModel.WIDTH
import app.mate.ui.home.FlowBandModel.waves
import app.mate.ui.themeBool
import app.mate.ui.themeColor

/**
 * Bande des thèmes à tubes (Rétro, Cadran, Cadran nuit) derrière tous les écrans, calée à droite de la zone des modes
 * (MainActivity), d'après le dessin d'Eliot (328 × 800, toute la hauteur) : deux tubes de trois bandes qui font une
 * boucle tous les 400, détourée d'un liseré du fond. Un liquide y coule lentement vers le bas : une vague plus claire
 * glisse dans chaque bande (dégradé linéaire décalé dans les tiges, dégradé circulaire qui tourne dans les boucles, en
 * phase avec la tige au point où ils se touchent). Rien que des rectangles et des disques, 30 images/s. Masquée dans
 * les autres thèmes (`c_flow`) : GONE, ni mesurée ni dessinée.
 * Les images ne tournent que pendant un passage d'écran et [FLOW_TAIL_MS] après (le liquide coule alors), jamais à
 * l'arrêt ; le voile est recalculé avant chaque image de la fenêtre, et la bande ne se redessine que s'il a bougé.
 * Voilée derrière les widgets sans fond ([hidden]) comme à travers une carte. Les écrans sont comme empilés le long des
 * tubes : passer à l'écran ou au profil suivant de la barre fait défiler les tubes d'un écran entier ([travel]).
 */
class FlowBandView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /**
     * Un tube, en unités du dessin : centre des boucles en x, première boucle en y, couleurs de la bande intérieure
     * vers l'extérieure. [side] = côté de la tige (+1 : à droite de la boucle, qui tourne alors dans le sens des
     * aiguilles d'une montre). [lowerOver] : la tige du dessous passe sur la boucle (sinon celle du dessus).
     */
    private inner class Tube(val cx: Float, val firstLoop: Float, val colors: IntArray, val side: Int, val lowerOver: Boolean) {
        val stems = Array(3) { k -> LinearGradient(0f, 0f, 0f, WAVE, wave(k), null, Shader.TileMode.REPEAT) }
        val loops = Array(3) { k -> SweepGradient(0f, 0f, IntArray(waves(RADII[k]) * 2 + 1) { wave(k)[it % 2] }, null) }

        private fun wave(k: Int): IntArray {
            val color = colors[k]
            return intArrayOf(color, ColorUtils.blendARGB(color, if (color == cream) paper else cream, TINT), color)
        }
    }

    /** Une zone voilée et ses deux fondus, créés une fois quand elle change (jamais dans [onDraw]). */
    private class Veil(val top: Float, val bottom: Float, val fadeIn: Shader?, val fadeOut: Shader?)

    private val bg = themeColor(context, R.attr.c_bg)
    private val paper = themeColor(context, R.attr.c_paper)
    private val cream = themeColor(context, R.attr.c_flow_cream)
    private val tubes = arrayOf(
        Tube(
            82f, 153f,
            intArrayOf(themeColor(context, R.attr.c_flow_dark), themeColor(context, R.attr.c_flow_red), themeColor(context, R.attr.c_flow_orange)),
            side = 1, lowerOver = false,
        ),
        Tube(
            278f, 270f,
            intArrayOf(cream, themeColor(context, R.attr.c_flow_orange), themeColor(context, R.attr.c_flow_red)),
            side = -1, lowerOver = true,
        ),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var running = false
    private val origin = IntArray(2)
    private val at = IntArray(2)
    private val join = 48 * resources.displayMetrics.density
    private val solid = ColorUtils.setAlphaComponent(bg, CARD_ALPHA)
    private val clear = ColorUtils.setAlphaComponent(bg, 0)
    private val flowOn = themeBool(context, R.attr.c_flow)

    /** Défilement des tubes, en unités du dessin (un écran = [HEIGHT]) ; ramené dans [FlowBandModel.LOOP] une fois posé. */
    private var offset = 0f
    private var target = 0f
    private var travelling: SpringAnimation? = null

    /** Phase du liquide ([0, WAVE[), avancée seulement tant que le liquide coule ; jusqu'à quand (uptime), et dernière image. */
    private var flowed = 0f
    private var flowUntil = 0L
    private var lastFrame = 0L

    /** Hauteurs voilées calculées avant la dernière image (voir [follow]) et leurs dégradés. */
    private var bands = emptyList<Pair<Float, Float>>()
    private var veils = emptyList<Veil>()

    /** Vues sans carte (héros, ligne du jour) derrière lesquelles les tubes passent voilés de la couleur du fond. */
    var hidden: () -> List<View> = { emptyList() }

    private val follow = ViewTreeObserver.OnPreDrawListener {
        val now = hiddenBands()
        if (now != bands) {
            bands = now
            veils = now.map { (top, bottom) -> veil(top, bottom) }
            invalidate()
        }
        true
    }

    init {
        visibility = if (flowOn) VISIBLE else GONE
    }

    /**
     * Passage à l'écran voisin dans la barre ([direction] > 0 : on descend, les tubes montent d'un écran ; < 0 : ils
     * descendent). Ressort de navigation : joue aussi en kiosque ; Réduire les animations : posé sans glisser.
     */
    fun travel(direction: Int) {
        if (direction == 0 || visibility != VISIBLE) return
        travelling?.cancel()
        target = FlowBandModel.scrolled(target, direction)
        flowUntil = Long.MAX_VALUE
        travelling = Look.spring(context, Look.SPATIAL_DEFAULT, offset, target, {
            offset = it
            invalidate()
        }, {
            target = FlowBandModel.settled(target)
            offset = target
            travelling = null
            flowUntil = SystemClock.uptimeMillis() + FLOW_TAIL_MS
            invalidate()
        }, always = true)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension((height * WIDTH / HEIGHT).toInt(), height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (flowOn) viewTreeObserver.addOnPreDrawListener(follow)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        travelling?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Dégradés du voile à la largeur de la vue : recalculés à la prochaine image.
        bands = emptyList()
        veils = emptyList()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (isVisible) {
            // Le liquide coule un moment à l'apparition, puis la bande reste fixe.
            flowUntil = maxOf(flowUntil, SystemClock.uptimeMillis() + FLOW_TAIL_MS)
            invalidate()
        }
    }

    private fun hiddenBands(): List<Pair<Float, Float>> {
        getLocationInWindow(origin)
        val spans = hidden().filter { it.isShown }.map { view ->
            view.getLocationInWindow(at)
            (at[1] - origin[1]).toFloat() to (at[1] - origin[1] + view.height).toFloat()
        }
        return FlowBandModel.veils(spans, join, height.toFloat())
    }

    /** Voile de la couleur du fond, estompé en haut et en bas (sauf au bord de la vue). */
    private fun veil(top: Float, bottom: Float): Veil {
        val fade = join / 2
        return Veil(
            top, bottom,
            if (top > 0f) LinearGradient(0f, top - fade, 0f, top, clear, solid, Shader.TileMode.CLAMP) else null,
            if (bottom < height) LinearGradient(0f, bottom, 0f, bottom + fade, solid, clear, Shader.TileMode.CLAMP) else null,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        // Réduire les animations : le liquide ne coule plus (vague figée).
        if (now < flowUntil && !Look.still(context)) {
            flowed = (flowed + SPEED * minOf(now - lastFrame, MAX_STEP_MS) / 1000f) % WAVE
        }
        lastFrame = now
        val scale = height / HEIGHT
        canvas.save()
        canvas.scale(scale, scale)
        // Le dessin défile sous la vue : on dessine les boucles de [offset] à [offset] + [HEIGHT].
        canvas.translate(0f, -offset)
        for (i in tubes.indices) drawTube(canvas, tubes[i], flowed)
        canvas.restore()
        for (i in veils.indices) drawVeil(canvas, veils[i])
        paint.shader = null
        // Le ressort relance les images lui-même ; ensuite seulement la queue du liquide, puis plus rien.
        if (running && travelling == null && now < flowUntil) postInvalidateDelayed(FRAME_MS)
    }

    private fun drawVeil(canvas: Canvas, veil: Veil) {
        val w = width.toFloat()
        val fade = join / 2
        paint.shader = null
        paint.color = solid
        canvas.drawRect(0f, veil.top, w, veil.bottom, paint)
        if (veil.fadeIn != null) {
            paint.shader = veil.fadeIn
            canvas.drawRect(0f, veil.top - fade, w, veil.top, paint)
        }
        if (veil.fadeOut != null) {
            paint.shader = veil.fadeOut
            canvas.drawRect(0f, veil.bottom, w, veil.bottom + fade, paint)
        }
    }

    /** Boucles visibles de `first` à `last`, tiges de `first` - 1 à `last`, dans l'ordre où elles se recouvrent. */
    private fun drawTube(canvas: Canvas, tube: Tube, flowed: Float) {
        val first = FlowBandModel.firstLoop(offset, tube.firstLoop)
        val last = FlowBandModel.lastLoop(offset, tube.firstLoop)
        if (tube.lowerOver) {
            drawStem(canvas, tube, first - 1, flowed)
            for (n in first..last) {
                drawLoop(canvas, tube, n, flowed)
                drawStem(canvas, tube, n, flowed)
            }
        } else {
            drawStem(canvas, tube, last, flowed)
            for (n in last downTo first) {
                drawLoop(canvas, tube, n, flowed)
                drawStem(canvas, tube, n - 1, flowed)
            }
        }
    }

    /** Tige entre la boucle [n] et la suivante : trois bandes droites où la vague descend. */
    private fun drawStem(canvas: Canvas, tube: Tube, n: Int, flowed: Float) {
        val top = tube.firstLoop + n * PERIOD
        matrix.setTranslate(0f, flowed)
        for (k in 0 until 3) {
            val x = tube.cx + tube.side * RADII[k]
            tube.stems[k].setLocalMatrix(matrix)
            paint.shader = tube.stems[k]
            canvas.drawRect(x - BAND / 2, top, x + BAND / 2, top + PERIOD, paint)
        }
    }

    /**
     * Boucle [n] : liseré du fond qui coupe la tige passant dessous, puis disques concentriques dont la vague tourne
     * dans le sens du tube. Au point de jonction (angle de départ), la phase est celle de la tige à la même hauteur.
     */
    private fun drawLoop(canvas: Canvas, tube: Tube, n: Int, flowed: Float) {
        val cy = tube.firstLoop + n * PERIOD
        paint.shader = null
        paint.color = bg
        canvas.drawCircle(tube.cx, cy, OUTLINE, paint)
        val start = if (tube.side > 0) 0f else 180f
        val phase = (cy - flowed) / WAVE
        for (k in 2 downTo 0) {
            matrix.setRotate(start - tube.side * 360f * phase / waves(RADII[k]))
            matrix.postTranslate(tube.cx, cy)
            tube.loops[k].setLocalMatrix(matrix)
            paint.shader = tube.loops[k]
            canvas.drawCircle(tube.cx, cy, RADII[k] + BAND / 2, paint)
        }
        // Le centre du disque intérieur, où le dégradé circulaire se resserre en moulinet : couleur pleine.
        paint.shader = null
        paint.color = tube.colors[0]
        canvas.drawCircle(tube.cx, cy, RADII[0] - BAND / 2, paint)
    }

    companion object {
        /** Opacité du voile, sur 255 : celle des widgets de l'Accueil posés sur les tubes (color/home_card, 0,8). */
        private const val CARD_ALPHA = 204
        private const val TINT = 0.75f

        /** Vitesse du liquide, en unités du dessin par seconde. */
        private const val SPEED = 34f
        private const val FRAME_MS = 33L

        /** Le liquide coule encore ce temps après la fin d'un passage d'écran (et à l'apparition de la bande). */
        private const val FLOW_TAIL_MS = 4000L

        /** Pas de vague maximal entre deux images : une image tardive ne fait pas sauter le liquide. */
        private const val MAX_STEP_MS = 100L
    }
}
