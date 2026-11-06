package app.mate.ui.home

import app.mate.R
import app.mate.net.GpuPerf
import app.mate.net.PcPerf
import kotlin.math.sin

/** Gravité d'une mesure du widget Performances : au-delà de NORMAL, la valeur passe au rouge d'alerte. */
enum class PerfLevel { NORMAL, WARN, CRITICAL }

/** Série de mesures du widget : processeur, carte graphique (utilisation et température), mémoire. */
enum class PerfSeries { CPU, GPU, RAM }

/** Ce qu'on colore : le chiffre, le remplissage d'une jauge ou d'une barre, le trait de la courbe (et son libellé). */
enum class PerfMark { VALUE, FILL, LINE }

/**
 * Couleur (attribut de thème) d'un élément du widget, dans l'esprit Console : tout reste neutre et le rouge d'alerte
 * (`c_accent`, seule couleur vivante) signale l'alerte, critique comprise (pas d'orange). Neutre : chiffre en `c_text`,
 * remplissage en `c_text_dim` (comme la suite de la ligne du jour : le chiffre mène, la jauge l'accompagne), trait du
 * processeur en `c_text`, de la carte graphique en `c_text_faint` et de la mémoire en `c_text_dim` pour les distinguer. Mesure ancienne (`stale`) :
 * chiffre en `c_text_faint`, remplissage et traits en `c_graphic` (le ton des graduations éteintes), même en alerte.
 */
fun perfColorAttr(series: PerfSeries, mark: PerfMark, level: PerfLevel, stale: Boolean = false): Int = when {
    stale -> if (mark == PerfMark.VALUE) R.attr.c_text_faint else R.attr.c_graphic
    level != PerfLevel.NORMAL -> R.attr.c_accent
    else -> when (mark) {
        PerfMark.VALUE -> R.attr.c_text
        PerfMark.FILL -> R.attr.c_text_dim
        PerfMark.LINE -> when (series) {
            PerfSeries.CPU -> R.attr.c_text
            PerfSeries.GPU -> R.attr.c_text_faint
            PerfSeries.RAM -> R.attr.c_text_dim
        }
    }
}

/** Utilisation (processeur, mémoire, carte graphique) en pourcentage : alerte dès 85, critique dès 95. */
fun usageLevel(percent: Int): PerfLevel = when {
    percent >= 95 -> PerfLevel.CRITICAL
    percent >= 85 -> PerfLevel.WARN
    else -> PerfLevel.NORMAL
}

/** Température du processeur ou de la carte graphique en °C : alerte dès 80, critique dès 90. */
fun tempLevel(celsius: Int): PerfLevel = when {
    celsius >= 90 -> PerfLevel.CRITICAL
    celsius >= 80 -> PerfLevel.WARN
    else -> PerfLevel.NORMAL
}

/** Mot d'une gravité (« en alerte », « critique ») : l'alerte n'est pas dite par la seule couleur. null : rien à dire. */
fun perfLevelWord(level: PerfLevel): Int? = when (level) {
    PerfLevel.NORMAL -> null
    PerfLevel.WARN -> R.string.perf_level_warn
    PerfLevel.CRITICAL -> R.string.perf_level_critical
}

/**
 * Alertes du moment sur une ligne, dans l'ordre reçu : « CPU critique · RAM en alerte » ; vide si tout va bien.
 * `readings` : (nom de la mesure, gravité) ; `word` : le texte d'une gravité (ressource de [perfLevelWord]).
 */
fun perfAlertText(readings: List<Pair<String, PerfLevel>>, word: (Int) -> String): String =
    readings.mapNotNull { (name, level) -> perfLevelWord(level)?.let { "$name ${word(it)}" } }.joinToString(" · ")

/** Dernières mesures pour les courbes (150 relevés de 2 s : 5 minutes) ; les plus anciennes sortent au-delà de `capacity`. `gpu` null : pas de carte lisible. */
class PerfHistory(val capacity: Int = 150) {
    private val cpuValues = ArrayDeque<Int>()
    private val gpuValues = ArrayDeque<Int?>()
    private val ramValues = ArrayDeque<Int>()

    val cpu: List<Int> get() = cpuValues.toList()
    val gpu: List<Int?> get() = gpuValues.toList()
    val ram: List<Int> get() = ramValues.toList()

    fun add(cpu: Int, gpu: Int?, ram: Int) {
        cpuValues.addLast(cpu)
        gpuValues.addLast(gpu)
        ramValues.addLast(ram)
        while (cpuValues.size > capacity) cpuValues.removeFirst()
        while (gpuValues.size > capacity) gpuValues.removeFirst()
        while (ramValues.size > capacity) ramValues.removeFirst()
    }

    /** Oublie toutes les mesures (page quittée : la courbe repart de zéro au retour). */
    fun clear() {
        cpuValues.clear()
        gpuValues.clear()
        ramValues.clear()
    }
}

/** Mise en page du widget Performances : un anneau, des chiffres et la courbe, des barres, ou trois anneaux. */
enum class PerfLayout { PILL, FIGURES, BARS, GAUGES }

/**
 * Mise en page par famille de formes (#183) : Pastille = un anneau ; Ligne = les chiffres en 2 × 1, des barres au-delà ;
 * Colonne = les barres empilées ; Bloc = les anneaux ; Grand et Plein écran = les chiffres et la courbe, en grand.
 * Les formes d'avant gardent la leur (Moyen = chiffres, Grand = anneaux, Large = barres).
 */
fun perfLayout(size: WidgetSize): PerfLayout = when (size.family) {
    ShapeFamily.PILL -> PerfLayout.PILL
    ShapeFamily.ROW -> if (size.w == 2) PerfLayout.FIGURES else PerfLayout.BARS
    ShapeFamily.COLUMN -> PerfLayout.BARS
    ShapeFamily.BLOCK -> PerfLayout.GAUGES
    ShapeFamily.LARGE, ShapeFamily.FULL -> PerfLayout.FIGURES
}

/** La mesure de la Pastille : sa série et son pourcentage. */
data class PillReading(val series: PerfSeries, val percent: Int)

/**
 * Ce que montre la Pastille (variante B choisie par Ahmed) : la plus chargée des trois mesures cochées et lisibles
 * (processeur, carte graphique, mémoire) ; à égalité, la première dans cet ordre. Null : aucune des trois.
 */
fun pillReading(perf: PcPerf, choice: Set<PerfOption>): PillReading? = listOfNotNull(
    PillReading(PerfSeries.CPU, perf.cpu).takeIf { PerfOption.CPU in choice },
    perf.gpu?.let { PillReading(PerfSeries.GPU, it.usage) }?.takeIf { PerfOption.GPU in choice },
    PillReading(PerfSeries.RAM, perf.ramPercent).takeIf { PerfOption.RAM in choice },
).maxByOrNull { it.percent }

/** Exemple de Performances pour le catalogue : un PC qui travaille un peu (processeur 42 %, carte graphique 71 %), sans ordinateur. */
object PerfSample : WidgetSample {
    val perf = PcPerf(
        cpu = 42,
        ramPercent = 63,
        ramUsedGb = 20.1,
        ramTotalGb = 32.0,
        gpu = GpuPerf("GPU", usage = 71, dedicated = true, tempC = 64, fanRpm = 1450),
    )

    /** Cinq minutes de relevés qui ondulent autour de la mesure d'exemple, pour la courbe. */
    fun fill(history: PerfHistory) {
        history.clear()
        repeat(history.capacity) { i ->
            val wave = (sin(i / WAVE_STEPS) * WAVE_PERCENT).toInt()
            history.add(perf.cpu + wave, perf.gpu?.usage?.minus(wave / 2), perf.ramPercent + wave / 4)
        }
    }

    private const val WAVE_STEPS = 9.0
    private const val WAVE_PERCENT = 12
}
