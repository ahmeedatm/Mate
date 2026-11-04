package app.mate.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import app.mate.R

/** Petite tuile de `w` × `h` pixels : blanc (-1) là où passe le trait, transparent ailleurs (teinté ensuite). */
class PatternTile(val w: Int, val h: Int, val pixels: IntArray)

/**
 * Motif des thèmes Registre, Studio, Tableau et Terminal, par-dessus tout l'écran comme [Grain] : dessiné une fois,
 * statique, sans boucle. Le thème le choisit (`c_pattern`) et lui donne sa couleur, alpha compris (`c_pattern_color`) :
 * - `ledger` (Registre) : filets horizontaux tous les 32 dp ;
 * - `accent` (Studio) : quart de disque et anneau dans le coin bas droit ;
 * - `grid` (Tableau) : trame de cellules de 48 dp ;
 * - `scan` (Terminal) : une ligne de scan d'un pixel tous les 4 pixels.
 * Les trois trames sont une petite tuile répétée (au plus 48 dp de côté) : jamais de bitmap plein écran. La forme
 * d'accent n'a pas de bitmap : quelques arcs tracés à la demande.
 */
object ThemePattern {
    private const val NONE = 0
    private const val LEDGER = 1
    private const val ACCENT = 2
    private const val GRID = 3
    private const val SCAN = 4

    private const val LEDGER_STEP_DP = 32
    private const val GRID_STEP_DP = 48
    private const val SCAN_STEP_PX = 4

    fun applyTo(root: View) {
        val context = root.context
        val kind = themeInt(context, R.attr.c_pattern)
        if (kind == NONE) return
        val color = themeColor(context, R.attr.c_pattern_color)
        root.foregroundGravity = Gravity.FILL
        root.foreground = drawable(root.resources, kind, color)
    }

    /** Tuile de la trame `kind` ; null pour aucun motif et pour la forme d'accent, qui se trace. */
    fun tile(kind: Int, density: Float): PatternTile? = when (kind) {
        LEDGER -> lines(4, (LEDGER_STEP_DP * density).toInt()) { _, y, _, h -> y == h - 1 }
        GRID -> (GRID_STEP_DP * density).toInt().let { step -> lines(step, step) { x, y, w, h -> x == w - 1 || y == h - 1 } }
        SCAN -> lines(4, SCAN_STEP_PX) { _, y, _, h -> y == h - 1 }
        else -> null
    }

    private fun lines(w: Int, h: Int, line: (x: Int, y: Int, w: Int, h: Int) -> Boolean) =
        PatternTile(w, h, IntArray(w * h) { i -> if (line(i % w, i / w, w, h)) -1 else 0 })

    private fun drawable(resources: Resources, kind: Int, color: Int): Drawable {
        val density = resources.displayMetrics.density
        val tile = tile(kind, density) ?: return AccentShape(color, density)
        val bitmap = Bitmap.createBitmap(tile.pixels, tile.w, tile.h, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(resources, bitmap).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    /** Quart de disque plein et anneau plus large, centrés sur le coin bas droit de l'écran. */
    private class AccentShape(private val color: Int, density: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@AccentShape.color }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = this@AccentShape.color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val radius = minOf(b.width(), b.height()) * DISC
            canvas.drawCircle(b.right.toFloat(), b.bottom.toFloat(), radius, fill)
            canvas.drawCircle(b.right.toFloat(), b.bottom.toFloat(), radius * RING, ring)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private companion object {
            const val DISC = 0.32f
            const val RING = 1.4f
        }
    }
}
