package app.mate.ui.home

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMapStats
import app.mate.net.ValorantRound
import app.mate.net.ValorantScore
import app.mate.net.ValorantState
import kotlin.math.roundToInt

/**
 * Bandeau de carte du widget Valorant (variante « A », choisie par Ahmed le 10/11/2026) : l'image de la carte jouée, son
 * nom, le score et la frise des rounds posés dessus ; en grand, des cartes en lévitation (bilan sur la carte, journée,
 * RR de la partie finie). Au menu, en plein écran seulement : ma journée, sur fond sombre. Taille selon la forme ([Size]) :
 * rien en pastille, mini à la place du score en ligne et colonne, fin à côté du titre, moyen ou plein au-dessus du tableau.
 */
object ValorantBand {
    /** Taille du bandeau ; MEDIUM et FULL seulement là où le tableau garde la hauteur de ses lignes. */
    enum class Size(val heightDp: Int) {
        NONE(0),
        MINI(48),
        THIN(48),
        MEDIUM(104),
        FULL(196),
        ;

        companion object {
            fun of(size: WidgetSize): Size = when {
                size.family == ShapeFamily.PILL -> NONE
                size.family == ShapeFamily.ROW || size.family == ShapeFamily.COLUMN -> MINI
                size == WidgetSize.FULL -> FULL
                size == WidgetSize.XL3 -> MEDIUM
                else -> THIN
            }
        }
    }

    /** Cartes posées sur le bandeau. */
    enum class Chip { MAP, TODAY, MATCH_RR }

    private const val ROUNDS = 24
    private const val FEW_GAMES = 3

    private fun endOf(v: Valorant) = v.end?.takeIf { it.status == ValorantEndStatus.READY }

    fun shows(v: Valorant, size: Size): Boolean {
        if (size == Size.NONE || v.state == ValorantState.OFF) return false
        val end = endOf(v)
        return when {
            end != null -> size != Size.MINI || end.score != null
            v.state == ValorantState.INGAME -> true
            v.state == ValorantState.PREGAME -> size != Size.MINI
            else -> size == Size.FULL && v.today?.results?.isNotEmpty() == true
        }
    }

    fun chips(v: Valorant, size: Size): List<Chip> {
        if (size != Size.FULL && size != Size.MEDIUM) return emptyList()
        val end = endOf(v)
        val today = Chip.TODAY.takeIf { size == Size.FULL && v.today != null }
        return when {
            end != null -> listOfNotNull(Chip.MATCH_RR.takeIf { end.rr != null }, today)
            v.state == ValorantState.INGAME || v.state == ValorantState.PREGAME -> listOfNotNull(Chip.MAP.takeIf { v.mapStats != null }, today)
            else -> emptyList()
        }
    }

    /** Cases de la frise : en partie, place gardée pour les 24 rounds (null : pas joué), prolongations en plus. */
    fun cells(rounds: List<ValorantRound>, live: Boolean): List<ValorantRound?> =
        if (live && rounds.size < ROUNDS) rounds + List(ROUNDS - rounds.size) { null } else rounds

    /** % de victoires sur la carte ; null sous 3 parties (un chiffre sur 1 ou 2 parties ne dit rien). */
    fun mapRate(stats: ValorantMapStats?): Int? = stats?.takeIf { it.games >= FEW_GAMES }?.let { (100f * it.wins / it.games).roundToInt() }

    fun view(v: Valorant, kit: ValorantKit, size: Size): View {
        val end = endOf(v)
        if (end == null && v.state != ValorantState.INGAME && v.state != ValorantState.PREGAME) return day(v, kit)
        val frame = photo(kit, end?.mapId ?: v.mapId, size)
        val big = size == Size.FULL || size == Size.MEDIUM
        val map = (end?.map ?: v.map).orEmpty().uppercase()
        val score = end?.score ?: v.score.takeIf { v.state == ValorantState.INGAME }
        val rounds = when {
            end != null -> cells(end.rounds, live = false)
            v.state == ValorantState.INGAME -> cells(v.rounds, live = true)
            else -> emptyList()
        }
        val content = if (big) bigContent(v, end, kit, size, map, score, rounds) else smallContent(kit, size, map, score, rounds)
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (big) {
            val chips = kit.row(gravity = Gravity.TOP).apply { chips(v, size).forEach { c -> addView(chip(v, end, kit, c), kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(12) }) } }
            frame.addView(chips, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, kit.dp(16), kit.dp(16), 0) })
        }
        frame.contentDescription = listOfNotNull(
            map.ifEmpty { null },
            score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) },
            rounds.filterNotNull().takeIf { it.isNotEmpty() }?.let { r -> kit.string(R.string.valorant_band_rounds, r.count { it.won == true }, r.count { it.won == false }) },
        ).joinToString(", ")
        return frame
    }

    /** Image de la carte, recadrée, sous un voile sombre qui tient le texte lisible ; fond sombre sans image. */
    private fun photo(kit: ValorantKit, mapId: String?, size: Size) = FrameLayout(kit.context).apply {
        background = GradientDrawable().apply {
            cornerRadius = kit.dp(if (size == Size.FULL || size == Size.MEDIUM) 14 else 10).toFloat()
            setColor(kit.color(R.attr.c_frame_bg))
        }
        clipToOutline = true
        if (mapId != null) addView(kit.mapImage(mapId), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val dark = kit.color(R.attr.c_frame_bg)
        val veil = intArrayOf(ColorUtils.setAlphaComponent(dark, 0xDB), ColorUtils.setAlphaComponent(dark, 0x8C), ColorUtils.setAlphaComponent(dark, 0x1F))
        addView(View(kit.context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, veil) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Texte blanc sur la photo (couleur des cadres photo du thème). */
    private fun onPhoto(kit: ValorantKit, text: String, size: Float, font: Int = R.attr.c_font_regular) = kit.text(text, size, R.attr.c_frame_text, font)

    /** Teinte d'équipe éclaircie, lisible sur la photo sombre. */
    private fun light(kit: ValorantKit, hue: Int) = ColorUtils.blendARGB(kit.color(hue), kit.color(R.attr.c_frame_text), 0.55f)

    private fun scoreText(kit: ValorantKit, score: ValorantScore, size: Float, font: Int = R.attr.c_font_display) = onPhoto(kit, "", size, font).apply {
        val ally = score.ally.toString()
        val text = SpannableStringBuilder(kit.string(R.string.valorant_score, score.ally, score.enemy))
        text.setSpan(ForegroundColorSpan(light(kit, R.attr.c_hue_cyan)), 0, ally.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(light(kit, R.attr.c_hue_red)), text.length - score.enemy.toString().length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text = text
    }

    private fun mapName(kit: ValorantKit, map: String, size: Float) = onPhoto(kit, map, size, R.attr.c_font_display).apply { letterSpacing = 0.14f }

    private fun caption(kit: ValorantKit, text: String) = onPhoto(kit, text.uppercase(), 12f, R.attr.c_font_medium).apply {
        letterSpacing = 0.16f
        alpha = 0.85f
    }

    private fun frieze(kit: ValorantKit, rounds: List<ValorantRound?>, heightDp: Int, icons: Boolean) =
        ValorantFrieze(kit.context, rounds, heightDp, icons, ColorUtils.setAlphaComponent(kit.color(R.attr.c_frame_text), 0x33))

    /** Plein et moyen : petite ligne, nom de la carte, score, puis la frise (ou, à la sélection, mes parties sur la carte). */
    private fun bigContent(v: Valorant, end: ValorantEnd?, kit: ValorantKit, size: Size, map: String, score: ValorantScore?, rounds: List<ValorantRound?>): View {
        val full = size == Size.FULL
        val sub = when {
            end != null -> kit.string(resultText(end.won))
            v.state == ValorantState.PREGAME -> listOfNotNull(v.queue, kit.string(R.string.valorant_pregame)).joinToString(" · ")
            else -> listOfNotNull(v.queue, score?.let { kit.string(R.string.valorant_band_round, it.ally + it.enemy + 1) }).joinToString(" · ")
        }
        val column = kit.column(caption(kit, sub), mapName(kit, map, if (full) 44f else 28f))
        val top = if (full) column else kit.row(column)
        if (score != null) {
            val s = scoreText(kit, score, if (full) 38f else 26f)
            if (full) column.addView(s, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(6) }) else top.addView(s, kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(20) })
        }
        val bottom: View? = when {
            rounds.isNotEmpty() -> frieze(kit, rounds, if (end != null) (if (full) 28 else 20) else (if (full) 20 else 14), icons = end != null)
            v.state == ValorantState.PREGAME -> v.mapStats?.let { onMap(kit, it, v.map.orEmpty()) }
            else -> null
        }
        return LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(22), kit.dp(if (full) 20 else 14), kit.dp(22), kit.dp(if (full) 20 else 14))
            addView(top, kit.lp(MATCH_PARENT))
            addView(View(context), kit.lp(MATCH_PARENT, 0, 1f))
            bottom?.let { addView(it, kit.lp(MATCH_PARENT)) }
        }
    }

    /**
     * Fin et mini : nom de la carte en haut (le score à côté en mini ; en fin, il est déjà dans la ligne d'état), la frise
     * dessous, sur toute la largeur (à côté du nom, les 24 cases seraient trop fines dans un bloc de 2 de large).
     */
    private fun smallContent(kit: ValorantKit, size: Size, map: String, score: ValorantScore?, rounds: List<ValorantRound?>): View {
        val top = kit.row(mapName(kit, map, 14f).apply { layoutParams = kit.lp(0, WRAP_CONTENT, 1f) })
        // Petit : police du titre, plus épaisse que celle des grands chiffres (lisible sur la photo).
        if (size == Size.MINI) score?.let { top.addView(scoreText(kit, it, 18f, R.attr.c_font_title)) }
        return kit.column(top.apply { layoutParams = kit.lp(MATCH_PARENT) }).apply {
            addView(View(context), kit.lp(MATCH_PARENT, 0, 1f))
            if (rounds.isNotEmpty()) addView(frieze(kit, rounds, if (size == Size.THIN) 9 else 7, icons = false), kit.lp(MATCH_PARENT))
            setPadding(kit.dp(12), kit.dp(6), kit.dp(12), kit.dp(8))
        }
    }

    /** Sélection d'agents : mes dernières parties sur cette carte, en cases (de la plus ancienne). */
    private fun onMap(kit: ValorantKit, stats: ValorantMapStats, map: String): View {
        if (stats.games == 0) return caption(kit, kit.string(R.string.valorant_band_never, stats.window))
        val label = kit.context.resources.getQuantityString(R.plurals.valorant_band_on_map, stats.games, stats.games, map)
        return kit.row(onPhoto(kit, label, 14f), kit.space(10)).apply { squares(kit, stats.results, 14).forEach(::addView) }
    }

    private fun squares(kit: ValorantKit, results: List<Boolean?>, sizeDp: Int) = results.map { won ->
        View(kit.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = kit.dp(3).toFloat()
                setColor(kit.color(resultHue(won)))
            }
            layoutParams = kit.lp(kit.dp(sizeDp), kit.dp(sizeDp)).apply { marginEnd = kit.dp(4) }
        }
    }

    /** Carte posée sur la photo : petite ligne, gros chiffre, détail. */
    private fun chip(v: Valorant, end: ValorantEnd?, kit: ValorantKit, chip: Chip): View {
        val (label, value, hue, detail) = when (chip) {
            Chip.MAP -> {
                val rate = mapRate(v.mapStats)
                Quad(
                    kit.string(R.string.valorant_band_map, v.map.orEmpty()),
                    rate?.let { "$it %" } ?: ValorantKit.DASH,
                    R.attr.c_text,
                    if (rate != null) kit.string(R.string.valorant_band_map_games, v.mapStats?.games ?: 0) else kit.string(R.string.valorant_band_map_few),
                )
            }
            Chip.TODAY -> {
                val t = v.today!!
                Quad(kit.string(R.string.valorant_band_today), kit.string(R.string.valorant_rr_change, ValorantKit.signed(t.rr)), rrHue(t.rr), kit.string(R.string.valorant_band_wl, t.wins, t.losses))
            }
            Chip.MATCH_RR -> {
                val rr = end?.rr ?: 0
                val me = v.me?.let { m -> listOfNotNull(m.rank, m.rr?.let { kit.string(R.string.valorant_rr, it) }).joinToString(" · ") }.orEmpty()
                Quad(kit.string(R.string.valorant_band_match), kit.string(R.string.valorant_rr_change, ValorantKit.signed(rr)), rrHue(rr), me)
            }
        }
        return kit.column(
            kit.text(label.uppercase(), 11f, R.attr.c_text_faint, R.attr.c_font_medium).apply { letterSpacing = 0.14f },
            kit.text(value, 24f, hue, R.attr.c_font_title),
            kit.text(detail, 13f, R.attr.c_text_dim),
        ).apply {
            background = GradientDrawable().apply {
                cornerRadius = kit.dp(12).toFloat()
                setColor(kit.color(R.attr.c_surface))
            }
            elevation = kit.dp(6).toFloat()
            minimumWidth = kit.dp(176)
            setPadding(kit.dp(14), kit.dp(9), kit.dp(14), kit.dp(10))
            contentDescription = listOf(label, value, detail).joinToString(", ")
        }
    }

    private data class Quad(val label: String, val value: String, val hue: Int, val detail: String)

    /** Au menu, plein écran : ma journée, sur fond sombre teinté de l'accent (pas de carte jouée). */
    private fun day(v: Valorant, kit: ValorantKit): View {
        val t = v.today ?: return View(kit.context)
        val dark = kit.color(R.attr.c_frame_bg)
        val rr = kit.string(R.string.valorant_rr_change, ValorantKit.signed(t.rr))
        val wl = kit.string(R.string.valorant_band_wl, t.wins, t.losses)
        val line = kit.row(onPhoto(kit, wl, 15f), kit.space(12)).apply { squares(kit, t.results, 16).forEach(::addView) }
        val big = mapName(kit, rr, 44f).apply { setTextColor(ColorUtils.blendARGB(kit.color(rrHue(t.rr)), kit.color(R.attr.c_frame_text), 0.45f)) }
        return kit.column(caption(kit, kit.string(R.string.valorant_band_today)), big).apply {
            addView(View(context), kit.lp(MATCH_PARENT, 0, 1f))
            addView(line)
            setPadding(kit.dp(22), kit.dp(20), kit.dp(22), kit.dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ColorUtils.blendARGB(kit.color(R.attr.c_accent), dark, 0.75f), dark)).apply {
                cornerRadius = kit.dp(14).toFloat()
            }
            contentDescription = listOf(kit.string(R.string.valorant_band_today), rr, wl).joinToString(", ")
        }
    }

    /** Sous le tableau de fin (plein écran) : ce que veut dire chaque dessin de la frise. */
    fun legend(kit: ValorantKit): View = kit.row().apply {
        val names = mapOf(
            R.drawable.ic_round_elimination to R.string.valorant_round_elimination,
            R.drawable.ic_round_detonate to R.string.valorant_round_detonate,
            R.drawable.ic_round_defuse to R.string.valorant_round_defuse,
            R.drawable.ic_round_time to R.string.valorant_round_time,
        )
        for ((_, res) in ValorantFrieze.ICONS) {
            val icon = ImageView(kit.context).apply {
                setImageDrawable(ContextCompat.getDrawable(kit.context, res)?.mutate()?.apply { setTint(kit.color(R.attr.c_text_faint)) })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(icon, kit.lp(kit.dp(15), kit.dp(15)))
            addView(kit.text(kit.string(names.getValue(res)), 13f, R.attr.c_text_faint), kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(5); marginEnd = kit.dp(18) })
        }
    }
}
