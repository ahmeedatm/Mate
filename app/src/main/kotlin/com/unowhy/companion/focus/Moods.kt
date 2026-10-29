package com.unowhy.companion.focus

/**
 * Humeur de Focus (logique pure, testée sur la JVM) : à chaque humeur, ce qu'on propose. Rien n'est gardé ni envoyé :
 * l'humeur ne sert qu'à choisir la proposition du moment.
 */
object Moods {

    enum class Mood { STRESSED, TIRED, SCATTERED, MOTIVATED }

    /** Respirer d'abord, ou se mettre au travail ; avec une ambiance. */
    sealed class Plan {
        abstract val ambiance: NoisePlayer.Kind

        data class Breathe(val pattern: Breathing.Pattern, val minutes: Int, override val ambiance: NoisePlayer.Kind) : Plan()

        data class Work(val preset: FocusPresets.Preset, override val ambiance: NoisePlayer.Kind) : Plan()
    }

    fun planFor(mood: Mood): Plan = when (mood) {
        // Stress : cohérence cardiaque, la pluie couvre le bruit autour.
        Mood.STRESSED -> Plan.Breathe(Breathing.Pattern.COHERENCE, 5, NoisePlayer.Kind.STORM)
        // Dispersé : respiration carrée courte pour se recentrer, en forêt.
        Mood.SCATTERED -> Plan.Breathe(Breathing.Pattern.BOX, 3, NoisePlayer.Kind.FOREST)
        // Fatigue : séance courte, du lofi pour tenir.
        Mood.TIRED -> Plan.Work(FocusPresets.Preset(15, 3), NoisePlayer.Kind.LOFI)
        // Motivé : séance longue, une ambiance qui ne distrait pas.
        Mood.MOTIVATED -> Plan.Work(FocusPresets.Preset(50, 10), NoisePlayer.Kind.JAPAN)
    }
}
