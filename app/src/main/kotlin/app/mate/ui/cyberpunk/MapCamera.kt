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
 *
 * Pose : à plat, le centre est au milieu de l'écran, nord en haut. En conduite ([setPose]), la carte tourne de `bearing`
 * degrés (sens horaire), le centre descend à `anchor` × la hauteur, et `tilt` (0 à 1) la penche : un point au-dessus du
 * centre de `gy` pixels est divisé par `w = 1 − k·gy` (homographie, comme une caméra derrière la voiture).
 */
class MapCamera(private val geo: MapGeometry) {
    var scale = INITIAL_SCALE
        private set
    var centerX = geo.width / 2.0
        private set
    var centerY = geo.height / 2.0
        private set
    var bearing = 0.0
        private set
    var tilt = 0.0
        private set
    private var anchor = FLAT_ANCHOR
    private var viewW = 1
    private var viewH = 1
    private var cos = 1.0
    private var sin = 0.0

    val minScale get() = min(viewW.toDouble() / geo.width, viewH.toDouble() / geo.height)

    private val ax get() = viewW / 2.0
    private val ay get() = viewH * anchor

    /** Force de la perspective : en haut de l'écran, `w` vaut `1 − PERSPECTIVE` à pleine inclinaison. */
    private val k get() = tilt * PERSPECTIVE / ay

    /** Haut de la carte dessinée : au-dessus, le fondu est opaque et rien n'est chargé. */
    val visibleTop get() = viewH * TOP_CUT * tilt

    fun setViewport(w: Int, h: Int) {
        viewW = max(w, 1)
        viewH = max(h, 1)
        setScale(scale)
        panBy(0f, 0f)
    }

    /**
     * Penchée, la carte ne recule pas plus que le zoom de pleine vitesse : plus loin, le trapèze vu demanderait plus de tuiles
     * que le cache n'en tient. La borne suit l'inclinaison, pour que la transition depuis la carte à plat ne saute pas.
     */
    private val lowestScale get() = minScale + (max(DRIVE_SCALE_FAR, minScale) - minScale) * tilt

    fun setScale(value: Double) {
        scale = value.coerceIn(lowestScale, MAX_SCALE)
    }

    fun setPose(bearing: Double, tilt: Double, anchor: Double) {
        this.bearing = bearing
        this.tilt = tilt.coerceIn(0.0, 1.0)
        this.anchor = anchor.coerceIn(ANCHOR_MIN, ANCHOR_MAX)
        val r = Math.toRadians(bearing)
        cos = kotlin.math.cos(r)
        sin = kotlin.math.sin(r)
    }

    /** Point de la carte tourné et zoomé autour du centre, avant la perspective (« sol », en pixels d'écran). */
    private fun ground(px: Double, py: Double): Pt {
        val dx = (px - centerX) * scale
        val dy = (py - centerY) * scale
        return Pt(dx * cos - dy * sin, dx * sin + dy * cos)
    }

    private fun groundToScreen(g: Pt): Pt {
        val w = 1 - k * g.y
        return Pt(ax + g.x / w, ay + g.y / w)
    }

    private fun mapToScreen(px: Double, py: Double): Pt = groundToScreen(ground(px, py))

    /** Sol le plus en arrière encore projeté : au-delà la perspective passe derrière la caméra (point en miroir). */
    private val groundLimit get() = if (k > 0) (1 - MIN_W) / k else Double.POSITIVE_INFINITY

    private fun screenToMap(sx: Double, sy: Double): Pt {
        val u = sx - ax
        val v = sy - ay
        val d = 1 + k * v
        val gx = u / d
        val gy = v / d
        return Pt(centerX + (gx * cos + gy * sin) / scale, centerY + (-gx * sin + gy * cos) / scale)
    }

    fun worldToScreen(x: Double, y: Double): Pt = geo.worldToMap(x, y).let { mapToScreen(it.x, it.y) }

    /** Comme [worldToScreen], ou null pour un point trop loin derrière le véhicule en conduite. */
    fun worldToScreenOrNull(x: Double, y: Double): Pt? {
        val m = geo.worldToMap(x, y)
        val g = ground(m.x, m.y)
        return if (g.y > groundLimit) null else groundToScreen(g)
    }

    /** Segment du monde à l'écran, coupé avant de passer derrière la caméra ; null s'il y est en entier. */
    fun segmentToScreen(x1: Double, y1: Double, x2: Double, y2: Double): Pair<Pt, Pt>? {
        val a = geo.worldToMap(x1, y1).let { ground(it.x, it.y) }
        val b = geo.worldToMap(x2, y2).let { ground(it.x, it.y) }
        val limit = groundLimit
        if (a.y > limit && b.y > limit) return null
        fun cut(p: Pt, q: Pt): Pt {
            if (p.y <= limit) return p
            val t = (limit - q.y) / (p.y - q.y)
            return Pt(q.x + (p.x - q.x) * t, limit)
        }
        return groundToScreen(cut(a, b)) to groundToScreen(cut(b, a))
    }

    fun screenToWorld(sx: Double, sy: Double) = screenToMap(sx, sy).let { geo.mapToWorld(it.x, it.y) }

    /**
     * Matrice 3 × 3 (ligne par ligne, comme `android.graphics.Matrix.setValues`) qui envoie un point de la carte, en pixels
     * du niveau max **relatifs au centre** ([centerX], [centerY]), à l'écran : rotation et zoom, puis perspective autour du
     * point d'ancrage. Relative au centre pour que le passage en float ne perde rien (pas de translation de 1e4 px).
     */
    fun matrix(out: FloatArray = FloatArray(MATRIX_SIZE)): FloatArray {
        val a = scale * cos
        val b = -scale * sin
        val d = scale * sin
        val e = scale * cos
        // Perspective [1 0 ax ; 0 1 ay ; 0 −k 1] appliquée après : la 3e ligne vaut −k × la 2e, plus 1.
        val m = doubleArrayOf(a - k * ax * d, b - k * ax * e, ax, d - k * ay * d, e - k * ay * e, ay, -k * d, -k * e, 1.0)
        for (i in m.indices) out[i] = m[i].toFloat()
        return out
    }

    fun centerOn(x: Double, y: Double) {
        val p = geo.worldToMap(x, y)
        centerX = p.x
        centerY = p.y
        panBy(0f, 0f)
    }

    /** Glisse la carte de (dx, dy) pixels d'écran : le point sous le centre suit le doigt. */
    fun panBy(dx: Float, dy: Float) {
        val m = screenToMap(ax - dx, ay - dy)
        centerX = m.x.coerceIn(0.0, geo.width.toDouble())
        centerY = m.y.coerceIn(0.0, geo.height.toDouble())
    }

    /** Multiplie le zoom en gardant fixe le point d'écran (sx, sy). */
    fun zoomAt(factor: Double, sx: Float, sy: Float) {
        val before = screenToMap(sx.toDouble(), sy.toDouble())
        setScale(scale * factor)
        val after = screenToMap(sx.toDouble(), sy.toDouble())
        centerX += before.x - after.x
        centerY += before.y - after.y
        panBy(0f, 0f)
    }

    /** Premier niveau dont les pixels sont au moins aussi gros que ceux de l'écran au centre (net sans gaspiller de mémoire). */
    fun level(): Int = (geo.maxLevel + ceil(ln(scale) / ln(2.0) - EPS).toInt()).coerceIn(0, geo.maxLevel)

    /** Sol vu à l'écran, en pixels du niveau max : les 4 coins sous le fondu (un trapèze tourné en conduite). */
    private fun groundQuad(): List<Pt> {
        val top = visibleTop
        return listOf(screenToMap(0.0, top), screenToMap(viewW.toDouble(), top), screenToMap(viewW.toDouble(), viewH.toDouble()), screenToMap(0.0, viewH.toDouble()))
    }

    fun visibleTiles(z: Int = level()): List<TileKey> {
        val span = geo.span(z) * geo.tile
        val quad = groundQuad()
        val x0 = floor(quad.minOf { it.x } / span).toInt().coerceAtLeast(0)
        val x1 = floor(quad.maxOf { it.x } / span).toInt().coerceAtMost(geo.columns(z) - 1)
        val y0 = floor(quad.minOf { it.y } / span).toInt().coerceAtLeast(0)
        val y1 = floor(quad.maxOf { it.y } / span).toInt().coerceAtMost(geo.rows(z) - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileKey(z, x, y) } }
            .filter { k -> intersects(quad, tileMapRect(k)) }
    }

    /** Rectangle d'une tuile en pixels du niveau max (coupé au bord de la carte). */
    fun tileMapRect(k: TileKey): MapRect {
        val span = geo.span(k.z) * geo.tile
        val left = k.x * span
        val top = k.y * span
        return MapRect(left, top, min(left + span, geo.width.toDouble()), min(top + span, geo.height.toDouble()))
    }

    companion object {
        const val MAX_SCALE = 1.5

        /** À l'ouverture : environ 900 m de large sur la tablette. */
        const val INITIAL_SCALE = 0.5

        /** Pose à plat : centre au milieu de l'écran. */
        const val FLAT_ANCHOR = 0.5

        /** Pose de conduite : le véhicule au tiers bas, de quoi voir la route devant. */
        const val DRIVE_ANCHOR = 0.7

        /** Zoom de conduite, de l'arrêt (près) à pleine vitesse (loin). */
        const val DRIVE_SCALE_NEAR = 1.0
        const val DRIVE_SCALE_FAR = 0.7

        /** Perspective à pleine inclinaison : en haut de l'écran on voit 1 / (1 − 0,5) = 2 fois plus loin qu'à plat. */
        const val PERSPECTIVE = 0.5

        /** Part du haut de l'écran couverte par le fondu opaque à pleine inclinaison. */
        const val TOP_CUT = 0.12

        /** Plus petit diviseur de perspective accepté (un point projeté 5 fois plus bas que sur la carte à plat). */
        private const val MIN_W = 0.2
        private const val MATRIX_SIZE = 9
        private const val ANCHOR_MIN = 0.1
        private const val ANCHOR_MAX = 0.9

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

/** Vrai si le polygone convexe `quad` et le rectangle se touchent (axes séparateurs). */
internal fun intersects(quad: List<Pt>, r: MapRect): Boolean {
    var qx0 = Double.MAX_VALUE
    var qx1 = -Double.MAX_VALUE
    var qy0 = Double.MAX_VALUE
    var qy1 = -Double.MAX_VALUE
    for (p in quad) {
        qx0 = min(qx0, p.x)
        qx1 = max(qx1, p.x)
        qy0 = min(qy0, p.y)
        qy1 = max(qy1, p.y)
    }
    if (qx1 < r.left || qx0 > r.right || qy1 < r.top || qy0 > r.bottom) return false
    // Sans allocation : appelé pour chaque tuile candidate, à chaque image en conduite.
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        val nx = b.y - a.y
        val ny = a.x - b.x
        fun side(x: Double, y: Double) = (x - a.x) * nx + (y - a.y) * ny
        var q0 = Double.MAX_VALUE
        var q1 = -Double.MAX_VALUE
        for (p in quad) side(p.x, p.y).let { q0 = min(q0, it); q1 = max(q1, it) }
        val c1 = side(r.left, r.top)
        val c2 = side(r.right, r.top)
        val c3 = side(r.right, r.bottom)
        val c4 = side(r.left, r.bottom)
        if (q1 < min(min(c1, c2), min(c3, c4)) || q0 > max(max(c1, c2), max(c3, c4))) return false
    }
    return true
}

/** Identifiant du point le plus proche de (x, y) dans le rayon donné, ou null. */
fun nearestPoint(points: List<Pair<String, Pt>>, x: Double, y: Double, radius: Double): String? =
    points.map { (id, p) -> id to hypot(p.x - x, p.y - y) }.filter { it.second <= radius }.minByOrNull { it.second }?.first
