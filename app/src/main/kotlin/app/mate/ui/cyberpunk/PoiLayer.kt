package app.mate.ui.cyberpunk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import app.mate.net.CyberpunkPoi
import app.mate.net.PoiCategory

/** ICON : l'icône du jeu ; DOT : un petit rond de la couleur de la catégorie. */
enum class PoiStyle { ICON, DOT }

/** Règle de densité : de loin (la ville entière tient dans la vue), 500 icônes se marchent dessus et pèsent sur l'image de 18 ms. */
object PoiDensity {
    /** Zoom (pixels d'écran par pixel du niveau max) sous lequel les icônes deviennent des ronds : environ 1,5 km de large sur la tablette. */
    const val ICON_MIN_SCALE = 0.3

    fun style(scale: Double) = if (scale >= ICON_MIN_SCALE) PoiStyle.ICON else PoiStyle.DOT

    /** De loin, les points atténués (non découverts, terminés) ne sont pas dessinés. */
    fun shows(poi: CyberpunkPoi, style: PoiStyle) = !poi.dim || style == PoiStyle.ICON
}

/** Monde → écran de [MapCamera.worldToScreen], calculé une fois par image puis appliqué sans allocation (la transformation est affine). */
class ScreenAffine {
    private var ox = 0f
    private var oy = 0f
    private var xx = 0f
    private var xy = 0f
    private var yx = 0f
    private var yy = 0f

    fun set(cam: MapCamera) {
        val o = cam.worldToScreen(0.0, 0.0)
        val ex = cam.worldToScreen(1.0, 0.0)
        val ey = cam.worldToScreen(0.0, 1.0)
        ox = o.x.toFloat()
        oy = o.y.toFloat()
        xx = (ex.x - o.x).toFloat()
        xy = (ex.y - o.y).toFloat()
        yx = (ey.x - o.x).toFloat()
        yy = (ey.y - o.y).toFloat()
    }

    fun x(wx: Double, wy: Double) = ox + xx * wx.toFloat() + yx * wy.toFloat()
    fun y(wx: Double, wy: Double) = oy + xy * wx.toFloat() + yy * wy.toFloat()
}

enum class TapTarget { PIN, POI, NEW_PIN }

/** Ce que fait un toucher : un repère de Mate d'abord, puis un point d'intérêt (seulement en style icône), sinon un repère est posé. */
fun tapTarget(pinHit: Boolean, poi: CyberpunkPoi?, style: PoiStyle): TapTarget = when {
    pinHit -> TapTarget.PIN
    poi != null && style == PoiStyle.ICON -> TapTarget.POI
    else -> TapTarget.NEW_PIN
}

/** Point le plus proche de (x, y) dans le rayon, parmi ceux que `project` place à l'écran. */
fun pickPoi(points: List<CyberpunkPoi>, project: (CyberpunkPoi) -> Pt, x: Double, y: Double, radius: Double): CyberpunkPoi? {
    val id = nearestPoint(points.map { it.id to project(it) }, x, y, radius)
    return points.firstOrNull { it.id == id }
}

/**
 * Points d'intérêt de la carte, dessinés par `MapView` entre les tuiles et les repères. Peinture, filtres de teinte et rayons
 * sont préparés une fois : le dessin ne crée aucun objet. Les points hors écran sont sautés.
 */
class PoiLayer(private val kit: CyberpunkKit) {
    private var visible = emptyList<CyberpunkPoi>()
    private var icons = emptyMap<String, Bitmap>()
    private val affine = ScreenAffine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val colors = IntArray(PoiCategory.entries.size) { kit.color(PoiCategory.entries[it].color) }
    private val filters = Array(colors.size) { PorterDuffColorFilter(colors[it], PorterDuff.Mode.SRC_IN) }

    /** Côté d'une icône à l'écran, en pixels : à décoder à cette taille ([PoiIcons.load]). */
    val iconPx = kit.dp(ICON_DP)
    private val fallbackRadius = kit.dp(FALLBACK_R_DP).toFloat()
    private val dotRadius = kit.dp(DOT_R_DP).toFloat()
    private val hitRadius = kit.dp(HIT_DP) / 2.0

    fun setPoints(points: List<CyberpunkPoi>, hidden: Set<PoiCategory>) {
        visible = PoiFilters.visible(points, hidden)
    }

    fun setIcons(icons: Map<String, Bitmap>) {
        this.icons = icons
    }

    fun draw(canvas: Canvas, cam: MapCamera, width: Int, height: Int) {
        val list = visible
        if (list.isEmpty()) return
        val style = PoiDensity.style(cam.scale)
        affine.set(cam)
        val margin = iconPx.toFloat()
        for (i in list.indices) {
            val p = list[i]
            if (!PoiDensity.shows(p, style)) continue
            val x = affine.x(p.x, p.y)
            val y = affine.y(p.x, p.y)
            if (x < -margin || y < -margin || x > width + margin || y > height + margin) continue
            drawOne(canvas, p, x, y, style)
        }
    }

    private fun drawOne(canvas: Canvas, p: CyberpunkPoi, x: Float, y: Float, style: PoiStyle) {
        val cat = p.cat.ordinal
        val alpha = if (p.dim) DIM_ALPHA else OPAQUE
        val icon = if (style == PoiStyle.ICON) p.icon?.let { icons[it] } else null
        if (icon != null) {
            paint.colorFilter = filters[cat]
            paint.alpha = alpha
            canvas.drawBitmap(icon, x - icon.width / 2f, y - icon.height / 2f, paint)
            return
        }
        paint.colorFilter = null
        paint.color = colors[cat]
        paint.alpha = alpha
        canvas.drawCircle(x, y, if (style == PoiStyle.ICON) fallbackRadius else dotRadius, paint)
    }

    /** Point sous le doigt (cible de 48 dp) ; null en style rond : de loin, un toucher pose un repère. */
    fun pick(cam: MapCamera, x: Float, y: Float): CyberpunkPoi? {
        if (PoiDensity.style(cam.scale) != PoiStyle.ICON) return null
        return pickPoi(visible, { cam.worldToScreen(it.x, it.y) }, x.toDouble(), y.toDouble(), hitRadius)
    }

    private companion object {
        const val ICON_DP = 28
        const val FALLBACK_R_DP = 7
        const val DOT_R_DP = 4
        const val HIT_DP = 48
        const val DIM_ALPHA = 155
        const val OPAQUE = 255
    }
}
