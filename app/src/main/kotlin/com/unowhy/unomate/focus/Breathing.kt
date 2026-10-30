package com.unowhy.unomate.focus

import kotlin.math.PI
import kotlin.math.cos

/**
 * Exercices de respiration de Focus (logique pure, testée sur la JVM) : rythmes, étape du moment et niveau de l'onde.
 * L'horloge est celle de l'écran ([com.unowhy.unomate.ui.BreathingView]) ; rien ne tourne hors de l'écran Focus.
 */
object Breathing {

    enum class Step { INHALE, HOLD_IN, EXHALE, HOLD_OUT }

    /** Étapes d'un cycle, en secondes. */
    enum class Pattern(val steps: List<Pair<Step, Int>>) {
        /** Cohérence cardiaque : 6 respirations par minute. */
        COHERENCE(listOf(Step.INHALE to 5, Step.EXHALE to 5)),

        /** Respiration carrée : se recentrer. */
        BOX(listOf(Step.INHALE to 4, Step.HOLD_IN to 4, Step.EXHALE to 4, Step.HOLD_OUT to 4)),

        /** 4-7-8 : expiration longue, contre l'anxiété et avant de dormir. */
        RELAX(listOf(Step.INHALE to 4, Step.HOLD_IN to 7, Step.EXHALE to 8)),
        ;

        val cycleMs: Long get() = steps.sumOf { it.second } * 1_000L
    }

    /** Durées proposées, en minutes. */
    val DURATIONS = listOf(3, 5, 10)

    /** Exercice en cours : le cadre photo ne vient pas le cacher. */
    @Volatile var running = false

    /**
     * Où en est l'exercice : étape, secondes restantes de l'étape (arrondies au-dessus), avancée dans l'étape (0 à 1),
     * niveau de l'onde (0 = vide, 1 = plein) et numéro du cycle.
     */
    data class Moment(val step: Step, val secondsLeft: Int, val progress: Float, val level: Float, val cycle: Int)

    fun at(pattern: Pattern, elapsedMs: Long): Moment {
        val elapsed = elapsedMs.coerceAtLeast(0)
        var t = elapsed % pattern.cycleMs
        for ((step, seconds) in pattern.steps) {
            val ms = seconds * 1_000L
            if (t < ms) {
                val progress = t.toFloat() / ms
                return Moment(step, ((ms - t + 999) / 1_000).toInt(), progress, level(step, progress), (elapsed / pattern.cycleMs).toInt())
            }
            t -= ms
        }
        error("cycle vide")
    }

    /** Fin de l'exercice : la durée choisie, arrondie au cycle entier (on finit toujours sur une expiration). */
    fun endMs(pattern: Pattern, minutes: Int): Long {
        val cycles = (minutes * 60_000L + pattern.cycleMs - 1) / pattern.cycleMs
        return cycles * pattern.cycleMs
    }

    /** L'onde monte à l'inspiration, tient pendant la rétention, descend à l'expiration ; départ et arrivée en douceur. */
    fun level(step: Step, progress: Float): Float = when (step) {
        Step.INHALE -> ease(progress)
        Step.HOLD_IN -> 1f
        Step.EXHALE -> 1f - ease(progress)
        Step.HOLD_OUT -> 0f
    }

    private fun ease(p: Float) = ((1 - cos(PI * p.coerceIn(0f, 1f))) / 2).toFloat()
}
