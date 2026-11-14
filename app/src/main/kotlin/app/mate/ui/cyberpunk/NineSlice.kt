package app.mate.ui.cyberpunk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Découpe d'une image en 9 zones : les 4 coins gardés tels quels, les bords et le centre étirés. */
object NineSlice {
    /** Rectangle source (s…) copié vers un rectangle destination (d…). */
    data class Zone(val sl: Int, val st: Int, val sr: Int, val sb: Int, val dl: Int, val dt: Int, val dr: Int, val db: Int)

    /** Neuf zones, lignes de haut en bas puis colonnes de gauche à droite ; la marge ne dépasse jamais la moitié de la source ni de la cible. */
    fun zones(srcW: Int, srcH: Int, dstW: Int, dstH: Int, margin: Int): List<Zone> {
        val m = minOf(margin, srcW / 2, srcH / 2, dstW / 2, dstH / 2).coerceAtLeast(0)
        val sx = intArrayOf(0, m, srcW - m, srcW)
        val sy = intArrayOf(0, m, srcH - m, srcH)
        val dx = intArrayOf(0, m, dstW - m, dstW)
        val dy = intArrayOf(0, m, dstH - m, dstH)
        return (0 until 3).flatMap { r -> (0 until 3).map { c -> Zone(sx[c], sy[r], sx[c + 1], sy[r + 1], dx[c], dy[r], dx[c + 1], dy[r + 1]) } }
    }
}

/** Image du jeu (masque blanc) étirée en gardant ses coins, teintée d'une couleur : le cadre des bulles et des lignes. */
class NineSliceDrawable(private val bitmap: Bitmap, private val margin: Int, tint: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) }
    private val src = Rect()
    private val dst = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        NineSlice.zones(bitmap.width, bitmap.height, b.width(), b.height(), margin).forEach {
            src.set(it.sl, it.st, it.sr, it.sb)
            dst.set(b.left + it.dl, b.top + it.dt, b.left + it.dr, b.top + it.db)
            if (!src.isEmpty && !dst.isEmpty) canvas.drawBitmap(bitmap, src, dst, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
