package app.mate.ui.cyberpunk

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Bascule de la carte entre à pied et en véhicule : une lecture fraîche en véhicule suffit pour entrer, il en faut deux
 * à pied de suite pour sortir (pas de clignotement). Une lecture périmée (jeu en pause, au menu) ne change rien.
 */
class DriveSwitch {
    var driving = false
        private set
    private var misses = 0

    fun feed(fresh: Boolean, inVehicle: Boolean): Boolean {
        if (!fresh) return driving
        if (inVehicle) {
            driving = true
            misses = 0
        } else if (driving && ++misses >= EXIT_READS) {
            driving = false
            misses = 0
        }
        return driving
    }

    private companion object {
        const val EXIT_READS = 2
    }
}

/** Écart d'angle de `from` à `to` en degrés, par le plus court chemin (entre −180 et 180). */
fun shortestTurn(from: Double, to: Double): Double {
    val d = ((to - from) % 360 + 540) % 360 - 180
    return if (d == -180.0 && to > from) 180.0 else d
}

/** Zoom de conduite : près à l'arrêt, plus loin avec la vitesse (m/s), atteint à [FAR_SPEED]. */
fun driveScale(speed: Double): Double {
    val f = (speed / FAR_SPEED).coerceIn(0.0, 1.0)
    return MapCamera.DRIVE_SCALE_NEAR + (MapCamera.DRIVE_SCALE_FAR - MapCamera.DRIVE_SCALE_NEAR) * f
}

/** 90 km/h : au-delà, le zoom ne recule plus. */
private const val FAR_SPEED = 25.0

data class Pose(val x: Double, val y: Double, val yaw: Double)

/**
 * Pose lissée du véhicule entre deux positions (~4 par seconde) : la cible est la dernière position prolongée par la
 * vitesse mesurée entre les deux dernières (au plus [MAX_EXTRAPOLATE_MS]), et la pose affichée glisse vers elle. Temps en
 * millisecondes de la tablette (réception), coordonnées du monde, yaw en degrés.
 */
class PoseTrack {
    private var last: Sample? = null
    private var vx = 0.0
    private var vy = 0.0
    private var shown: Pose? = null
    private var shownAt = 0L

    /** Vitesse mesurée, en unités du monde par seconde (mètres). */
    val speed get() = hypot(vx, vy)

    fun push(atMs: Long, x: Double, y: Double, yaw: Double) {
        val prev = last
        last = Sample(atMs, x, y, yaw)
        if (prev == null || atMs <= prev.atMs) return
        val dt = (atMs - prev.atMs) / MS
        if (hypot(x - prev.x, y - prev.y) > JUMP_M) {
            vx = 0.0
            vy = 0.0
            shown = Pose(x, y, yaw)
            return
        }
        vx = (x - prev.x) / dt
        vy = (y - prev.y) / dt
    }

    fun at(nowMs: Long): Pose? {
        val s = last ?: return null
        val ahead = min((nowMs - s.atMs).coerceAtLeast(0), MAX_EXTRAPOLATE_MS) / MS
        val tx = s.x + vx * ahead
        val ty = s.y + vy * ahead
        val cur = shown
        val pose = if (cur == null) {
            Pose(tx, ty, s.yaw)
        } else {
            val f = 1 - exp(-(nowMs - shownAt).coerceAtLeast(0) / TAU_MS)
            Pose(cur.x + (tx - cur.x) * f, cur.y + (ty - cur.y) * f, cur.yaw + shortestTurn(cur.yaw, s.yaw) * f)
        }
        shown = pose
        shownAt = nowMs
        return pose
    }

    private data class Sample(val atMs: Long, val x: Double, val y: Double, val yaw: Double)

    companion object {
        const val MAX_EXTRAPOLATE_MS = 300L

        /** Lissage : la pose fait 63 % du chemin vers la cible en 120 ms. */
        private const val TAU_MS = 120.0

        /** Au-delà d'un saut de 200 m entre deux positions (voyage rapide, chargement), la carte saute aussi. */
        private const val JUMP_M = 200.0
        private const val MS = 1000.0
    }
}
