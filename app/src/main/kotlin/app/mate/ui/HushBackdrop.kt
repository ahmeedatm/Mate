package app.mate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.ui.HushGlow.Tint

/**
 * Fond animé du thème Hush (attribut c_backdrop, absent ailleurs : la vue reste masquée), derrière tous les écrans de
 * MainActivity : l'aura et les éclats de HushGlow. Dessiné à la main une image sur deux, calé sur les images de
 * l'écran (Choreographer : le kiosque coupe les animateurs d'Android ; un minuteur, lui, tomberait entre deux images
 * et en ferait attendre une). Pas de flou ni de bitmap plein écran : des dégradés créés une fois à la taille de
 * l'écran, seulement déplacés ensuite. Toujours en mouvement, la nuit comme sur batterie ; Réduire les animations
 * le fige (seul interrupteur, choix d'Ahmed). En pause sous un écran qui le cache : boîte, verrou, ou [coveredBy].
 */
class HushBackdrop(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val still = Look.still(context)
    private val bg = themeColor(context, R.attr.c_bg)
    private val tints = mapOf(Tint.PINK to themeColor(context, R.attr.c_accent), Tint.VIOLET to themeColor(context, R.attr.c_hue_violet))
    private val halos = HushGlow.HALOS.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val radii = FloatArray(HushGlow.HALOS.size)
    private val density = resources.displayMetrics.density
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Éclats peints une fois chacun dans une petite image (losange étiré, corps violet sombre, arête rose), à la taille
     * de l'écran : la déplacer et la pencher à chaque image ne coûte presque rien, alors qu'un tracé redessiné penché
     * coûtait 50 ms par image sur l'émulateur.
     */
    private var shards = emptyList<Bitmap>()

    /** Écran posé par-dessus dans la même fenêtre (Bienvenue, Plugins) : le fond ne se redessine pas dessous. */
    var coveredBy: () -> Boolean = { false }

    private fun shardBitmap(shard: HushGlow.Shard): Bitmap {
        val s = shard.size * density
        val edge = density * 1.2f
        val half = s + edge
        val path = Path().apply {
            moveTo(half, half - s)
            lineTo(half + s * 0.42f, half - s * 0.18f)
            lineTo(half, half + s)
            lineTo(half - s * 0.42f, half - s * 0.18f)
            close()
        }
        return Bitmap.createBitmap((2 * half).toInt() + 1, (2 * half).toInt() + 1, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mixed(Tint.VIOLET, HushGlow.SHARD_FILL) })
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mixed(Tint.PINK, HushGlow.SHARD_EDGE)
                    style = Paint.Style.STROKE
                    strokeWidth = edge
                    strokeJoin = Paint.Join.ROUND
                },
            )
        }
    }

    private var ticking = false

    /** Une image sur deux : redessine si rien ne cache le fond, puis redemande une image après FRAME_DELAY_MS. */
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!live()) {
                ticking = false
                return
            }
            if (hasWindowFocus() && !coveredBy()) invalidate()
            Choreographer.getInstance().postFrameCallbackDelayed(this, HushGlow.FRAME_DELAY_MS)
        }
    }

    init {
        visibility = if (themeBool(context, R.attr.c_backdrop)) VISIBLE else GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun tinted(tint: Tint, alpha: Float) = ColorUtils.setAlphaComponent(tints.getValue(tint), (alpha * 255).toInt())

    /** Teinte mêlée au fond, opaque : `part` de la teinte. */
    private fun mixed(tint: Tint, part: Float) = ColorUtils.blendARGB(bg, tints.getValue(tint), part)

    /** Visible pour de vrai : vue affichée et fenêtre à l'écran (appli au premier plan, écran allumé). */
    private fun live() = !still && isShown && windowVisibility == VISIBLE

    private fun startTicking() {
        if (ticking || !live()) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(tick)
    }

    /** Halos centrés sur l'origine, même teinte transparente au bord (pas de liseré gris) ; éclats peints une fois. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (shards.isEmpty()) shards = HushGlow.SHARDS.map { shardBitmap(it) }
        HushGlow.HALOS.forEachIndexed { i, halo ->
            radii[i] = w * halo.radius
            halos[i].shader = RadialGradient(
                0f, 0f, radii[i], HushGlow.HALO_STRENGTH.map { tinted(halo.tint, halo.alpha * it) }.toIntArray(),
                HushGlow.HALO_STOPS, Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = if (still) HushGlow.STILL_AT_MS else SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in HushGlow.HALOS.indices) {
            val halo = HushGlow.HALOS[i]
            val scale = HushGlow.haloScale(halo, now)
            canvas.save()
            canvas.translate(w * HushGlow.haloX(halo, now), h * HushGlow.haloY(halo, now))
            canvas.scale(scale, scale)
            canvas.drawCircle(0f, 0f, radii[i], halos[i])
            canvas.restore()
        }
        // Éclats tenus en l'air : ils flottent et penchent, sans jamais tomber.
        for (i in shards.indices) {
            val shard = HushGlow.SHARDS[i]
            canvas.save()
            canvas.translate(w * shard.x, h * shard.y + shard.bob * density * HushGlow.wave(now, shard.bobMs))
            canvas.rotate(shard.tilt * HushGlow.wave(now, shard.tiltMs))
            canvas.drawBitmap(shards[i], -shards[i].width / 2f, -shards[i].height / 2f, sprite)
            canvas.restore()
        }
        startTicking()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(tick)
        ticking = false
        super.onDetachedFromWindow()
    }
}
