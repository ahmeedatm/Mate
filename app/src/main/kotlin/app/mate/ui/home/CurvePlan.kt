package app.mate.ui.home

import kotlin.math.ceil

/** Calculs de la courbe des températures ([TempCurve]), sans vue : combien d'heures, où, quelles étiquettes. */
object CurvePlan {
    /** En dessous de cet écart (degrés), la courbe est plate. */
    private const val FLAT = 0.5

    /** Nombre d'heures qui tiennent sur [length] px avec au moins [minStep] px entre deux, au plus [available] ; 0 : pas de courbe. */
    fun count(length: Float, minStep: Float, available: Int): Int {
        if (available < 2 || length < minStep) return 0
        return ((length / minStep).toInt() + 1).coerceAtMost(available)
    }

    /** Position de chaque température entre [low] (la plus froide) et [high] (la plus chaude) ; toutes égales : au milieu. */
    fun place(temps: List<Double>, low: Float, high: Float): FloatArray {
        val min = temps.minOrNull() ?: return FloatArray(0)
        val max = temps.max()
        return FloatArray(temps.size) { i -> if (max - min < FLAT) (low + high) / 2 else low + ((temps[i] - min) / (max - min)).toFloat() * (high - low) }
    }

    /** Une étiquette toutes les combien d'heures pour qu'elles ne se touchent pas ([labelWidth] avec sa marge, [step] entre deux heures). */
    fun every(labelWidth: Float, step: Float): Int = if (step <= 0f) 1 else ceil(labelWidth / step).toInt().coerceAtLeast(1)
}
