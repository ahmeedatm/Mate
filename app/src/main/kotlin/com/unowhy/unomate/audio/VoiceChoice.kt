package com.unowhy.unomate.audio

import kotlin.math.abs

/** Ce qu'on garde d'une voix du moteur (android.speech.tts.Voice), pour la trier sans Android. */
data class VoiceInfo(
    val name: String,
    val language: String,
    val networkRequired: Boolean,
    val notInstalled: Boolean,
)

/** Choix de la voix et du débit de la synthèse vocale (Réglages > Accessibilité), sans Android. */
object VoiceChoice {
    /**
     * Débit par défaut, un peu sous celui du moteur (1) : la voix de Google lit vite et sèchement, 0,9 la rend plus
     * posée sans traîner. Choix possible dans Réglages (Lent, Doux, Normal).
     */
    const val DEFAULT_RATE = 0.9f

    /** Hauteur un peu sous celle du moteur (1) : voix moins aiguë, plus douce. Pas de réglage, toujours appliquée. */
    const val PITCH = 0.95f

    /** Débits proposés dans Réglages : Lent, Doux (par défaut), Normal. */
    val RATES = listOf(0.8f, DEFAULT_RATE, 1f)

    /**
     * Voix françaises utilisables sans réseau ni téléchargement (la tablette peut être hors ligne), noms triés pour
     * que « Voix 1 », « Voix 2 »… désignent toujours les mêmes d'un démarrage à l'autre.
     */
    fun offlineFrench(voices: Collection<VoiceInfo>): List<String> = voices
        .filter { it.language == "fr" && !it.networkRequired && !it.notInstalled }
        .map { it.name }
        .distinct()
        .sorted()

    /** Voix enregistrée si elle existe encore, sinon null (voix par défaut du moteur pour le français). */
    fun voiceToApply(saved: String?, available: List<String>): String? = saved?.takeIf { it in available }

    /** Débit proposé le plus proche de celui enregistré (pour la pastille sélectionnée). */
    fun rateChoice(saved: Float): Float = RATES.minBy { abs(it - saved) }
}
