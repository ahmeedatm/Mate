package app.mate.ui.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Référentiel des lignes d'Île-de-France Mobilités (assets/lignes-idfm.tsv, construit par scripts/lignes-idfm.mjs,
 * licence ODbL citée dans Réglages > À propos) : nom court, mode et couleurs officielles de chaque ligne, pour
 * dessiner le picto d'un passage du widget Transports. Lu une seule fois, hors du fil de l'interface.
 */
object TransportLines {
    private const val ASSET = "lignes-idfm.tsv"
    private const val COLUMNS = 5

    enum class Mode { METRO, RER, TRAIN, TRAM, BUS }

    /** [bg] : fond du picto, [fg] : texte du picto (ARGB opaques). */
    class Line(val name: String, val mode: Mode, val bg: Int, val fg: Int)

    @Volatile private var cache: Map<String, Line>? = null

    /** Le référentiel, chargé à la première demande puis gardé (quelques centaines de Ko). */
    suspend fun load(context: Context): Map<String, Line> = cache ?: withContext(Dispatchers.IO) {
        context.assets.open(ASSET).bufferedReader().useLines { parse(it) }
    }.also { cache = it }

    /** « STIF:Line::C01371: » → « C01371 » ; null si la référence n'a pas ce format. */
    fun idOf(lineRef: String): String? = lineRef.split(':').filter { it.isNotEmpty() }.lastOrNull()?.takeIf { lineRef.contains("::") }

    /** Lignes invalides (colonnes manquantes, mode ou couleur illisible) ignorées. */
    fun parse(lines: Sequence<String>): Map<String, Line> = buildMap {
        for (row in lines) {
            if (row.isBlank() || row.startsWith("#")) continue
            val c = row.split('\t')
            if (c.size < COLUMNS) continue
            val mode = Mode.entries.firstOrNull { it.name.equals(c[2], ignoreCase = true) }
            val bg = color(c[3])
            val fg = color(c[4])
            if (mode != null && bg != null && fg != null) put(c[0], Line(c[1], mode, bg, fg))
        }
    }

    private fun color(hex: String): Int? = hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}
