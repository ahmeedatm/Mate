package app.mate.ui.cyberpunk

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.mate.R

/** Chemin du monde `[x0, y0, x1, y1…]` en pixels du niveau max de la carte, calculé une fois à chaque nouveau chemin. */
fun routeToMap(world: DoubleArray, geo: MapGeometry): DoubleArray {
    val out = DoubleArray(world.size - world.size % 2)
    for (i in 0 until out.size / 2) {
        val p = geo.worldToMap(world[2 * i], world[2 * i + 1])
        out[2 * i] = p.x
        out[2 * i + 1] = p.y
    }
    return out
}

/** Taille du tampon de [projectRoute] : au pire deux sommets par segment (début d'un morceau, puis fin). */
fun routeBufferSize(map: DoubleArray) = 3 * 2 * (map.size / 2)

/**
 * Chemin de la carte à l'écran par la matrice de [MapCamera.matrix] (pixels relatifs au centre `cx`, `cy`), coupé là où il
 * passerait derrière la caméra en conduite (`w` < [MapCamera.MIN_W], comme [MapCamera.segmentToScreen]). Écrit dans `out`
 * des triplets (x, y, début), début = 1 quand un nouveau morceau commence, et rend leur nombre. Sans allocation : appelé à
 * chaque image.
 */
fun projectRoute(map: DoubleArray, m: FloatArray, cx: Double, cy: Double, out: FloatArray): Int {
    var count = 0
    var penDown = false
    fun w(x: Double, y: Double) = m[6] * x + m[7] * y + m[8]
    fun emit(x: Double, y: Double, start: Boolean) {
        val d = w(x, y)
        out[3 * count] = ((m[0] * x + m[1] * y + m[2]) / d).toFloat()
        out[3 * count + 1] = ((m[3] * x + m[4] * y + m[5]) / d).toFloat()
        out[3 * count + 2] = if (start) 1f else 0f
        count++
    }
    for (i in 1 until map.size / 2) {
        val ax = map[2 * i - 2] - cx
        val ay = map[2 * i - 1] - cy
        val bx = map[2 * i] - cx
        val by = map[2 * i + 1] - cy
        val wa = w(ax, ay)
        val wb = w(bx, by)
        if (wa < MapCamera.MIN_W && wb < MapCamera.MIN_W) {
            penDown = false
            continue
        }
        // `w` est affine le long du segment : la coupe à MIN_W est exacte.
        var sx = ax
        var sy = ay
        if (wa < MapCamera.MIN_W) {
            val t = (MapCamera.MIN_W - wa) / (wb - wa)
            sx = ax + (bx - ax) * t
            sy = ay + (by - ay) * t
            penDown = false
        }
        var ex = bx
        var ey = by
        if (wb < MapCamera.MIN_W) {
            val t = (MapCamera.MIN_W - wb) / (wa - wb)
            ex = bx + (ax - bx) * t
            ey = by + (ay - by) * t
        }
        if (!penDown) emit(sx, sy, start = true)
        emit(ex, ey, start = false)
        penDown = wb >= MapCamera.MIN_W
    }
    return count
}

/**
 * Chemin du GPS du jeu, le long des rues, dessiné par `MapView` à la place de la ligne droite vers la destination : un trait
 * plein jaune, bordé du fond pour rester lisible sur les tuiles, comme le GPS de la mini-map du jeu. Peintures, tracé et
 * tampon sont réutilisés d'une image à l'autre.
 */
class RouteLayer(kit: CyberpunkKit) {
    private var map = DoubleArray(0)
    private var buffer = FloatArray(0)
    private val path = Path()
    private val matrixValues = FloatArray(MATRIX_SIZE)
    private val casing = stroke(kit, R.color.cp_fond, CASING_DP).apply { alpha = CASING_ALPHA }
    private val line = stroke(kit, R.color.cp_jaune, LINE_DP)

    val hasRoute get() = map.size >= 4

    /** Nouveau chemin du monde, ou vide pour revenir à la ligne droite. */
    fun setRoute(world: DoubleArray, geo: MapGeometry) {
        map = routeToMap(world, geo)
        if (buffer.size < routeBufferSize(map)) buffer = FloatArray(routeBufferSize(map))
    }

    fun clear() {
        map = DoubleArray(0)
    }

    /** Faux s'il n'y a pas de chemin : `MapView` dessine alors la ligne droite. */
    fun draw(canvas: Canvas, cam: MapCamera): Boolean {
        if (!hasRoute) return false
        val n = projectRoute(map, cam.matrix(matrixValues), cam.centerX, cam.centerY, buffer)
        path.rewind()
        for (i in 0 until n) {
            val x = buffer[3 * i]
            val y = buffer[3 * i + 1]
            if (buffer[3 * i + 2] == 1f) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, casing)
        canvas.drawPath(path, line)
        return true
    }

    private fun stroke(kit: CyberpunkKit, colorRes: Int, widthDp: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = kit.dp(widthDp).toFloat()
        color = kit.color(colorRes)
    }

    private companion object {
        const val MATRIX_SIZE = 9
        const val LINE_DP = 5
        const val CASING_DP = 9
        const val CASING_ALPHA = 200
    }
}
