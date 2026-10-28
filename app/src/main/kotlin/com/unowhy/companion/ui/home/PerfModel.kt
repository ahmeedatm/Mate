package com.unowhy.companion.ui.home

import com.unowhy.companion.R

/** Gravité d'une mesure du widget Performances : au-delà de NORMAL, la valeur passe au rouge Unowhy. */
enum class PerfLevel { NORMAL, WARN, CRITICAL }

/** Série de mesures du widget : processeur, carte graphique (utilisation et température), mémoire. */
enum class PerfSeries { CPU, GPU, RAM }

/** Ce qu'on colore : le chiffre, le remplissage d'une jauge ou d'une barre, le trait de la courbe (et son libellé). */
enum class PerfMark { VALUE, FILL, LINE }

/**
 * Couleur (attribut de thème) d'un élément du widget, dans l'esprit Cadran : tout reste neutre et le rouge Unowhy
 * (`c_accent`, seule couleur vivante) signale l'alerte, critique comprise (pas d'orange). Neutre : chiffre en `c_text`,
 * remplissage en `c_text_dim` (comme la suite de la ligne du jour : le chiffre mène, la jauge l'accompagne), trait du
 * processeur en `c_text` et de la carte graphique en `c_text_faint` pour les distinguer. Mesure ancienne (`stale`) :
 * chiffre en `c_text_faint`, remplissage et traits en `c_graphic` (le ton des graduations éteintes), même en alerte.
 */
fun perfColorAttr(series: PerfSeries, mark: PerfMark, level: PerfLevel, stale: Boolean = false): Int = when {
    stale -> if (mark == PerfMark.VALUE) R.attr.c_text_faint else R.attr.c_graphic
    level != PerfLevel.NORMAL -> R.attr.c_accent
    else -> when (mark) {
        PerfMark.VALUE -> R.attr.c_text
        PerfMark.FILL -> R.attr.c_text_dim
        PerfMark.LINE -> if (series == PerfSeries.GPU) R.attr.c_text_faint else R.attr.c_text
    }
}

/** Utilisation (processeur, mémoire, carte graphique) en pourcentage : alerte dès 85, critique dès 95. */
fun usageLevel(percent: Int): PerfLevel = when {
    percent >= 95 -> PerfLevel.CRITICAL
    percent >= 85 -> PerfLevel.WARN
    else -> PerfLevel.NORMAL
}

/** Température de la carte graphique en °C : alerte dès 80, critique dès 90. */
fun tempLevel(celsius: Int): PerfLevel = when {
    celsius >= 90 -> PerfLevel.CRITICAL
    celsius >= 80 -> PerfLevel.WARN
    else -> PerfLevel.NORMAL
}

/** Dernières mesures pour les courbes ; les plus anciennes sortent au-delà de `capacity`. `gpu` null : pas de carte lisible. */
class PerfHistory(val capacity: Int = 30) {
    private val cpuValues = ArrayDeque<Int>()
    private val gpuValues = ArrayDeque<Int?>()

    val cpu: List<Int> get() = cpuValues.toList()
    val gpu: List<Int?> get() = gpuValues.toList()

    fun add(cpu: Int, gpu: Int?) {
        cpuValues.addLast(cpu)
        gpuValues.addLast(gpu)
        while (cpuValues.size > capacity) cpuValues.removeFirst()
        while (gpuValues.size > capacity) gpuValues.removeFirst()
    }

    /** Oublie toutes les mesures (page quittée : la courbe repart de zéro au retour). */
    fun clear() {
        cpuValues.clear()
        gpuValues.clear()
    }
}
