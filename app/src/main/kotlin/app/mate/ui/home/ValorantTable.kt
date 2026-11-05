package app.mate.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import app.mate.R
import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndPlayer
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMatch
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantState

/**
 * Mise en forme du widget Valorant, « Tableau » (d'après tracker.gg et VRY, choisie par Ahmed le 05/11 parmi trois) : une
 * ligne par joueur, des colonnes de chiffres alignées, les deux équipes côte à côte quand le widget est large.
 */
object ValorantTable {
    /** Largeur des colonnes de texte, en sp : elles grandissent avec le texte (Accessibilité, 1,3). */
    private const val STAT = 46
    private const val RANK = 76

    private const val KDA = 92
    private const val FIRST = 44

    /** Colonne du logo du meilleur rang, en dp (une image ne grandit pas avec le texte). */
    private const val PEAK = 34

    /** Hauteur d'une ligne quand elles ne s'étirent pas (grands widgets). */
    private const val ROW_DP = 52

    /** Air entre deux colonnes de chiffres. */
    private const val GAP = 8

    /** Taille de texte (réglage d'Accessibilité) au-delà de laquelle le tableau de fin perd ses trois dernières colonnes. */
    private const val LARGE_TEXT = 1.15f

    fun match(v: Valorant, kit: ValorantKit, shape: ValorantShape): View {
        val full = shape.detail
        val later = if (v.state == ValorantState.PREGAME) R.string.valorant_enemies_later else null
        val allies = team(kit, R.string.valorant_allies, v.allies, null, full, shape.stretch)
        val enemies = team(kit, R.string.valorant_enemies, v.enemies, later, full, shape.stretch)
        return sides(kit, allies, enemies, shape.side)
    }

    /** Ligne d'un tableau : se partage la hauteur, ou garde la sienne (le tableau se centre alors). */
    private fun rowLp(kit: ValorantKit, stretch: Boolean) =
        if (stretch) kit.lp(MATCH_PARENT, 0, 1f) else kit.lp(MATCH_PARENT, kit.dp(ROW_DP))

    private fun sides(kit: ValorantKit, left: View, right: View, side: Boolean) = LinearLayout(kit.context).apply {
        orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (side) {
            addView(left, kit.lp(0, MATCH_PARENT, 1f).apply { marginEnd = kit.dp(12) })
            addView(divider(kit), kit.lp(kit.dp(1), MATCH_PARENT))
            addView(right, kit.lp(0, MATCH_PARENT, 1f).apply { marginStart = kit.dp(12) })
        } else {
            addView(left, kit.lp(MATCH_PARENT, 0, 1f))
            addView(right, kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(6) })
        }
    }

    private fun divider(kit: ValorantKit) = View(kit.context).apply { setBackgroundColor(kit.color(R.attr.c_stroke)) }

    private fun team(kit: ValorantKit, title: Int, players: List<ValorantPlayer>, empty: Int?, full: Boolean, stretch: Boolean) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val head = kit.row(kit.caption(title))
        head.getChildAt(0).layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        if (full) {
            head.addView(kit.caption(R.string.valorant_col_rank, Gravity.START), kit.lp(kit.sp(RANK)))
            head.addView(kit.caption(R.string.valorant_col_peak, Gravity.CENTER), kit.lp(kit.dp(PEAK)))
            for (c in listOf(R.string.valorant_col_kd, R.string.valorant_col_hs, R.string.valorant_col_wr)) head.addView(kit.caption(c, Gravity.END), statLp(kit, STAT))
        } else {
            head.addView(kit.caption(R.string.valorant_col_kd, Gravity.END), statLp(kit, STAT))
        }
        addView(head, kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(2) })
        if (players.isEmpty() && empty != null) addView(kit.text(kit.string(empty), 14f, R.attr.c_text_faint))
        for (p in players) addView(player(kit, p, full), rowLp(kit, stretch))
    }

    private fun player(kit: ValorantKit, p: ValorantPlayer, full: Boolean): View {
        val icon = if (full) 30 else 22
        val line = kit.row(
            kit.partyBar(p.party),
            kit.space(6),
            kit.agent(p.agentId, icon, dim = !p.locked, name = p.agent),
            kit.space(8),
        )
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 15f else 13f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular)
        val who = if (full && p.level != null) kit.column(name, kit.text(kit.string(R.string.valorant_level_short, p.level), 12f, R.attr.c_text_faint)) else name
        line.addView(who, kit.lp(0, WRAP_CONTENT, 1f))
        if (full) {
            val rank = kit.row(kit.rank(p.tier, 26), kit.space(4), kit.text(ValorantKit.number(p.rr), 13f, R.attr.c_text_dim))
            line.addView(rank, kit.lp(kit.sp(RANK)))
            // Meilleur rang pas encore lu : la place reste vide (pas de logo « Non classé » trompeur, rien à lire à voix haute).
            val peak = if (p.peakTier == null) View(kit.context) else kit.rank(p.peakTier, 22)
            line.addView(peak, kit.lp(kit.dp(PEAK), kit.dp(22)).apply { gravity = Gravity.CENTER })
            line.addView(stat(kit, ValorantKit.kd(p.kd), p.kd), statLp(kit, STAT))
            line.addView(stat(kit, ValorantKit.pct(p.hs), p.hs), statLp(kit, STAT))
            line.addView(stat(kit, ValorantKit.pct(p.winRate), p.winRate), statLp(kit, STAT))
        } else {
            line.addView(kit.rank(p.tier, 22))
            line.addView(stat(kit, ValorantKit.kd(p.kd), p.kd), statLp(kit, STAT))
        }
        line.setPadding(0, 0, kit.dp(6), 0)
        if (p.me) kit.mine(line)
        line.contentDescription = kit.describe(p)
        return line
    }

    /** Colonne de chiffres : largeur en sp, et de l'air avant (deux colonnes ne se touchent jamais). */
    private fun statLp(kit: ValorantKit, width: Int) = kit.lp(kit.sp(width) + kit.dp(GAP))

    private fun stat(kit: ValorantKit, text: String, value: Any?) =
        kit.text(text, 14f, if (value == null) R.attr.c_text_faint else R.attr.c_text).apply { gravity = Gravity.END }

    fun end(end: ValorantEnd, kit: ValorantKit, shape: ValorantShape): View {
        // Texte grossi (Accessibilité) : sans % tête, ADR et premiers kills, pour que les noms restent lisibles.
        val full = shape.detail && kit.context.resources.configuration.fontScale <= LARGE_TEXT
        // Combat à mort : chacun pour soi, une seule liste classée par kills (ordre du compagnon).
        if (end.ffa) return board(kit, R.string.valorant_ffa, end.players, full, shape.stretch)
        return sides(kit, board(kit, R.string.valorant_allies, end.allies, full, shape.stretch), board(kit, R.string.valorant_enemies, end.enemies, full, shape.stretch), shape.side)
    }

    private fun board(kit: ValorantKit, title: Int, players: List<ValorantEndPlayer>, full: Boolean, stretch: Boolean) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val head = kit.row(kit.caption(title))
        head.getChildAt(0).layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        val cols = if (full) {
            listOf(R.string.valorant_col_acs to STAT, R.string.valorant_col_kda to KDA, R.string.valorant_col_hs to STAT, R.string.valorant_col_adr to STAT, R.string.valorant_col_fk to FIRST)
        } else {
            listOf(R.string.valorant_col_acs to STAT, R.string.valorant_col_kda to KDA)
        }
        for ((c, w) in cols) head.addView(kit.caption(c, Gravity.END), statLp(kit, w))
        addView(head, kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(2) })
        for (p in players) addView(scoreLine(kit, p, full), rowLp(kit, stretch))
    }

    private fun scoreLine(kit: ValorantKit, p: ValorantEndPlayer, full: Boolean): View {
        val icon = if (full) 28 else 22
        val line = kit.row(kit.agent(p.agentId, icon, name = p.agent), kit.space(6), kit.rank(p.tier, icon - 4), kit.space(8))
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 15f else 13f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular)
        line.addView(name, kit.lp(0, WRAP_CONTENT, 1f))
        if (p.mvp) line.addView(mvp(kit), kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(6) })
        line.addView(kit.text(p.acs.toString(), 15f, R.attr.c_text, R.attr.c_font_medium).apply { gravity = Gravity.END }, statLp(kit, STAT))
        line.addView(kit.text(kit.string(R.string.valorant_kda, p.kills, p.deaths, p.assists), 14f, R.attr.c_text_dim).apply { gravity = Gravity.END }, statLp(kit, KDA))
        if (full) {
            line.addView(stat(kit, ValorantKit.pct(p.hs), p.hs), statLp(kit, STAT))
            line.addView(stat(kit, p.adr.toString(), p.adr), statLp(kit, STAT))
            line.addView(stat(kit, p.firstKills.toString(), p.firstKills), statLp(kit, FIRST))
        }
        line.setPadding(0, 0, kit.dp(6), 0)
        if (p.me) kit.mine(line)
        line.contentDescription = kit.describe(p)
        return line
    }

    /**
     * Pastille, ligne, colonne : mon rang (logo et nom), mes RR et la barre, et le score de la partie en cours (ou le
     * résultat de celle qui vient de finir). Le reste se lit dans un widget plus grand.
     */
    fun summary(v: Valorant, kit: ValorantKit, family: ShapeFamily): View {
        val me = v.me ?: return kit.text(kit.string(R.string.valorant_off_hint), 14f, R.attr.c_text_dim)
        val end = v.end?.takeIf { it.status == ValorantEndStatus.READY }
        val result = end?.let { e -> listOfNotNull(kit.string(resultText(e.won)), e.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) }).joinToString(" ") }
        val live = v.score?.takeIf { v.state == ValorantState.INGAME }?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) }
        val score = when {
            result != null -> kit.text(result, 18f, resultHue(end.won), R.attr.c_font_medium)
            live != null -> kit.text(live, 26f, R.attr.c_text, R.attr.c_font_display)
            else -> null
        }
        val rr = me.rr?.let { kit.text(kit.string(R.string.valorant_rr, it), 13f, R.attr.c_text_dim) }
        val bar = ValorantKit.rrProgress(me.tier, me.rr)?.let { kit.rrBar(it) }
        val name = kit.text(me.rank, 18f, R.attr.c_text, R.attr.c_font_light)
        return when (family) {
            ShapeFamily.PILL -> kit.column(kit.rank(me.tier, 56), gravity = Gravity.CENTER).apply {
                addView(score ?: rr ?: name, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(6) })
            }
            ShapeFamily.ROW -> kit.row(kit.rank(me.tier, 64), kit.space(12)).apply {
                val info = kit.column(name)
                rr?.let { info.addView(it) }
                bar?.let { info.addView(it, kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6) }) }
                addView(info, kit.lp(0, WRAP_CONTENT, 1f))
                score?.let { addView(it, kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(12) }) }
            }
            else -> kit.column(kit.rank(me.tier, 72), name, gravity = Gravity.CENTER).apply {
                rr?.let { addView(it) }
                bar?.let { addView(it, kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6) }) }
                score?.let { addView(it, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(10) }) }
            }
        }.apply { contentDescription = listOfNotNull(me.rank, me.rr?.let { kit.string(R.string.valorant_rr, it) }, result ?: live).joinToString(", ") }
    }

    /** Pastille « MVP » : texte sur l'accent (pas une étoile seule : le mot se lit). */
    private fun mvp(kit: ValorantKit) = kit.text(kit.string(R.string.valorant_mvp), 12f, R.attr.c_on_accent, R.attr.c_font_medium).apply {
        background = GradientDrawable().apply {
            cornerRadius = kit.dp(4).toFloat()
            setColor(kit.color(R.attr.c_accent))
        }
        setPadding(kit.dp(5), kit.dp(1), kit.dp(5), kit.dp(1))
    }

    fun menu(v: Valorant, kit: ValorantKit, shape: ValorantShape): View {
        val full = shape.detail
        val me = v.me ?: return kit.text(kit.string(R.string.valorant_off_hint), 16f, R.attr.c_text_dim)
        val details = listOfNotNull(me.rr?.let { kit.string(R.string.valorant_rr, it) }, me.level?.let { kit.string(R.string.valorant_level, it) }).joinToString(" · ")
        val info = kit.column(
            kit.text(me.rank, if (full) 26f else 20f, R.attr.c_text, R.attr.c_font_light),
            kit.text(details, 14f, R.attr.c_text_dim),
        )
        ValorantKit.rrProgress(me.tier, me.rr)?.let { info.addView(kit.rrBar(it), kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(8) }) }
        if (me.name.isNotEmpty()) info.addView(kit.text(me.name, 13f, R.attr.c_text_faint), kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(6) })
        val hero = kit.row(kit.rank(me.tier, if (full) 88 else 60), kit.space(12))
        hero.addView(info, kit.lp(0, WRAP_CONTENT, 1f))
        val list = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.caption(R.string.valorant_last_matches))
            if (v.history.isEmpty()) addView(kit.text(kit.string(R.string.valorant_no_history), 14f, R.attr.c_text_faint))
            for (m in v.history.take(if (full) 5 else 3)) addView(game(kit, m, full), kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(2) })
        }
        return LinearLayout(kit.context).apply {
            orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            if (shape.side) {
                addView(hero, kit.lp(kit.dp(330)).apply { marginEnd = kit.dp(16) })
                addView(divider(kit), kit.lp(kit.dp(1), MATCH_PARENT))
                addView(list, kit.lp(0, MATCH_PARENT, 1f).apply { marginStart = kit.dp(16) })
            } else {
                addView(hero, kit.lp(MATCH_PARENT))
                addView(list, kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(8) })
            }
        }
    }

    private fun game(kit: ValorantKit, m: ValorantMatch, full: Boolean): View {
        val band = View(kit.context).apply {
            background = GradientDrawable().apply { cornerRadius = kit.dp(2).toFloat(); setColor(kit.color(resultHue(m.won))) }
        }
        val line = kit.row(band, kit.space(8))
        band.layoutParams = kit.lp(kit.dp(4), MATCH_PARENT)
        val score = m.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: kit.string(resultText(m.won))
        line.addView(kit.text(score, 14f, R.attr.c_text, R.attr.c_font_medium), kit.lp(kit.sp(64)))
        line.addView(kit.agent(m.agentId, 24, name = m.agent))
        line.addView(kit.text(m.map ?: ValorantKit.DASH, 14f, R.attr.c_text_dim), kit.lp(0, WRAP_CONTENT, 1f).apply { marginStart = kit.dp(8) })
        if (full) {
            val kda = if (m.kills != null && m.deaths != null && m.assists != null) kit.string(R.string.valorant_kda, m.kills, m.deaths, m.assists) else ValorantKit.DASH
            line.addView(kit.text(kda, 14f, R.attr.c_text_dim).apply { gravity = Gravity.END }, kit.lp(kit.sp(84)))
            line.addView(kit.text(ValorantKit.number(m.acs), 14f).apply { gravity = Gravity.END }, kit.lp(kit.sp(STAT)))
        }
        line.addView(kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(m.rr)), 14f, rrHue(m.rr), R.attr.c_font_medium).apply { gravity = Gravity.END }, kit.lp(kit.sp(70)))
        line.contentDescription = kit.describe(m)
        return line
    }
}
