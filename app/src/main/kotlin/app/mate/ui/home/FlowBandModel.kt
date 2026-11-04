package app.mate.ui.home

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/** Calculs de [FlowBandView] : géométrie du dessin d'Eliot (en unités du dessin, 328 × 800), défilement et voile. */
object FlowBandModel {
    const val WIDTH = 328f
    const val HEIGHT = 800f
    const val PERIOD = 400f
    const val BAND = 24f
    const val OUTLINE = 82f + BAND
    val RADII = floatArrayOf(22f, 46f, 70f)

    /** Longueur d'une vague, éclaircissement en son milieu. */
    const val WAVE = 150f

    /** Défilement au bout duquel tout se retrouve (boucles tous les [PERIOD], vagues tous les [WAVE]) : 1200. */
    const val LOOP = 1200f

    /** Défilement visé après un passage à l'écran voisin ([direction] > 0 : on descend, les tubes montent d'un écran). */
    fun scrolled(target: Float, direction: Int) = target + direction.sign * HEIGHT

    /** Défilement posé, ramené dans un tour : le dessin est le même et le nombre ne grandit pas sans fin. */
    fun settled(target: Float) = target % LOOP

    /** Première boucle d'un tube (la première est en `firstLoop`, puis une tous les [PERIOD]) visible à ce défilement. */
    fun firstLoop(offset: Float, firstLoop: Float) = ceil((offset - OUTLINE - firstLoop) / PERIOD).toInt()

    fun lastLoop(offset: Float, firstLoop: Float) = floor((offset + HEIGHT + OUTLINE - firstLoop) / PERIOD).toInt()

    /** Vagues sur le tour d'une boucle de ce rayon : un nombre entier, pour qu'elle se referme sans raccord. */
    fun waves(radius: Float) = maxOf(1, (2 * PI * radius / WAVE).roundToInt())

    /**
     * Hauteurs voilées, à partir de celles des vues sans carte. Deux zones à moins de [join] l'une de l'autre se
     * rejoignent, et une zone aussi près du haut ou du bas va jusqu'au bord : pas de bout de tube vif isolé dans les marges.
     */
    fun veils(spans: List<Pair<Float, Float>>, join: Float, height: Float): List<Pair<Float, Float>> {
        val merged = ArrayList<Pair<Float, Float>>()
        for ((top, bottom) in spans.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && top - last.second < join) merged[merged.size - 1] = last.first to maxOf(last.second, bottom)
            else merged.add(top to bottom)
        }
        return merged.map { (top, bottom) ->
            (if (top < join) 0f else top - join / 4) to (if (bottom > height - join) height else bottom + join / 4)
        }
    }

    /** Fin (uptime) du liquide qui coule à partir de [now], [tail] plus tard ; 0 en Réduire les animations : il ne coule pas. */
    fun flowEnd(now: Long, still: Boolean, tail: Long) = if (still) 0L else now + tail

    /**
     * Le voile est recalculé avant une image seulement si les tubes tournent (le liquide coule encore à [now], ressort
     * compris) ou juste après un changement de mise en page (jusqu'à [layoutUntil] : les widgets qui arrivent bougent
     * encore) : une bande à l'arrêt ne coûte rien à chaque image des autres animations.
     */
    fun follows(now: Long, flowUntil: Long, layoutUntil: Long) = now < flowUntil || now < layoutUntil
}
