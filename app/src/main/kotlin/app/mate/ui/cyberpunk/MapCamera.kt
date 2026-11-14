package app.mate.ui.cyberpunk

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Double, val y: Double)

data class MapRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Une tuile de la pyramide : niveau (0 = ville entière, le plus grand = pleine définition), colonne, ligne. */
data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Carte de la ville : taille du niveau max en pixels, nombre de niveaux, côté d'une tuile, et l'affine
 * `[a, b, c, d, e, f]` (`GET /cyberpunk/map`) qui donne le pixel du niveau max : px = a·x + b·y + c, py = d·x + e·y + f.
 */
class MapGeometry(val width: Int, val height: Int, val levels: Int, val tile: Int, affine: List<Double>) {
    private val a = affine[0]
    private val b = affine[1]
    private val c = affine[2]
    private val d = affine[3]
    private val e = affine[4]
    private val f = affine[5]
    private val det = a * e - b * d

    val maxLevel get() = levels - 1

    fun worldToMap(x: Double, y: Double) = Pt(a * x + b * y + c, d * x + e * y + f)

    fun mapToWorld(px: Double, py: Double): Pt {
        val u = px - c
        val v = py - f
        return Pt((e * u - b * v) / det, (a * v - d * u) / det)
    }

    /** Pixels du niveau max qui tiennent dans un pixel du niveau `z`. */
    fun span(z: Int) = (1 shl (maxLevel - z)).toDouble()

    /** Nombre de colonnes et de lignes de tuiles du niveau `z`. */
    fun columns(z: Int) = ceil(width / span(z) / tile).toInt()
    fun rows(z: Int) = ceil(height / span(z) / tile).toInt()

    companion object {
        /** Null si la réponse n'est pas exploitable (affine absente ou dégénérée, taille nulle). */
        fun of(width: Int, height: Int, levels: Int, tile: Int, affine: List<Double>?): MapGeometry? {
            if (affine == null || affine.size != 6 || width <= 0 || height <= 0 || levels <= 0 || tile <= 0) return null
            if (affine[0] * affine[4] - affine[1] * affine[3] == 0.0) return null
            return MapGeometry(width, height, levels, tile, affine)
        }
    }
}

/**
 * Vue sur la carte : centre en pixels du niveau max et zoom (pixels d'écran par pixel du niveau max). Pur calcul, sans
 * Android. Le zoom va de la ville entière dans la vue jusqu'au niveau max × 1,5.
 */
class MapCamera(private val geo: MapGeometry) {
    var scale = INITIAL_SCALE
        private set
    var centerX = geo.width / 2.0
        private set
    var centerY = geo.height / 2.0
        private set
    private var viewW = 1
    private var viewH = 1

    val minScale get() = min(viewW.toDouble() / geo.width, viewH.toDouble() / geo.height)

    fun setViewport(w: Int, h: Int) {
        viewW = max(w, 1)
        viewH = max(h, 1)
        setScale(scale)
        panBy(0f, 0f)
    }

    fun setScale(value: Double) {
        scale = value.coerceIn(minScale, MAX_SCALE)
    }

    fun worldToScreen(x: Double, y: Double): Pt {
        val p = geo.worldToMap(x, y)
        return Pt((p.x - centerX) * scale + viewW / 2.0, (p.y - centerY) * scale + viewH / 2.0)
    }

    fun screenToWorld(sx: Double, sy: Double) = geo.mapToWorld((sx - viewW / 2.0) / scale + centerX, (sy - viewH / 2.0) / scale + centerY)

    fun centerOn(x: Double, y: Double) {
        val p = geo.worldToMap(x, y)
        centerX = p.x
        centerY = p.y
        panBy(0f, 0f)
    }

    /** Glisse la carte de (dx, dy) pixels d'écran. */
    fun panBy(dx: Float, dy: Float) {
        centerX = (centerX - dx / scale).coerceIn(0.0, geo.width.toDouble())
        centerY = (centerY - dy / scale).coerceIn(0.0, geo.height.toDouble())
    }

    /** Multiplie le zoom en gardant fixe le point d'écran (sx, sy). */
    fun zoomAt(factor: Double, sx: Float, sy: Float) {
        val mx = (sx - viewW / 2.0) / scale + centerX
        val my = (sy - viewH / 2.0) / scale + centerY
        setScale(scale * factor)
        centerX = mx - (sx - viewW / 2.0) / scale
        centerY = my - (sy - viewH / 2.0) / scale
        panBy(0f, 0f)
    }

    /** Premier niveau dont les pixels sont au moins aussi gros que ceux de l'écran (net sans gaspiller de mémoire). */
    fun level(): Int = (geo.maxLevel + ceil(ln(scale) / ln(2.0) - EPS).toInt()).coerceIn(0, geo.maxLevel)

    fun visibleTiles(z: Int = level()): List<TileKey> {
        val span = geo.span(z) * geo.tile
        val x0 = floor((centerX - viewW / 2.0 / scale) / span).toInt().coerceAtLeast(0)
        val x1 = floor((centerX + viewW / 2.0 / scale) / span).toInt().coerceAtMost(geo.columns(z) - 1)
        val y0 = floor((centerY - viewH / 2.0 / scale) / span).toInt().coerceAtLeast(0)
        val y1 = floor((centerY + viewH / 2.0 / scale) / span).toInt().coerceAtMost(geo.rows(z) - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileKey(z, x, y) } }
    }

    fun tileRect(k: TileKey): MapRect {
        val span = geo.span(k.z) * geo.tile
        val left = (k.x * span - centerX) * scale + viewW / 2.0
        val top = (k.y * span - centerY) * scale + viewH / 2.0
        val w = min(span, geo.width - k.x * span) * scale
        val h = min(span, geo.height - k.y * span) * scale
        return MapRect(left, top, left + w, top + h)
    }

    companion object {
        const val MAX_SCALE = 1.5

        /** À l'ouverture : environ 900 m de large sur la tablette. */
        const val INITIAL_SCALE = 0.5

        /**
         * Octets du cache de tuiles (RGB_565) pour une vue de `viewW` × `viewH` pixels : toutes les tuiles visibles au pire
         * (d'après [level], une tuile s'affiche sur plus de `tile / 2` pixels), plus celles du niveau au-dessus qui servent
         * de repli. Plus petit, chaque tuile chargée chasse une tuile encore visible et la carte clignote. Plafonné à
         * [MAX_CACHE_BYTES] si le serveur annonce des tuiles démesurées : mieux vaut un clignotement qu'un manque de mémoire.
         */
        fun tileCacheBytes(viewW: Int, viewH: Int, tile: Int): Int {
            fun across(view: Int, px: Int) = ceil(view.toDouble() / px.coerceAtLeast(1)).toLong() + 1
            val tiles = across(viewW, tile / 2) * across(viewH, tile / 2) + across(viewW, tile) * across(viewH, tile)
            return (tiles * tile * tile * RGB_565_BYTES).coerceAtMost(MAX_CACHE_BYTES.toLong()).toInt()
        }

        private const val RGB_565_BYTES = 2
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        private const val EPS = 1e-9
    }
}

/** Identifiant du point le plus proche de (x, y) dans le rayon donné, ou null. */
fun nearestPoint(points: List<Pair<String, Pt>>, x: Double, y: Double, radius: Double): String? =
    points.map { (id, p) -> id to hypot(p.x - x, p.y - y) }.filter { it.second <= radius }.minByOrNull { it.second }?.first
