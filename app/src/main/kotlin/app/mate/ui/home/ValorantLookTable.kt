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
import app.mate.net.ValorantMatch
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantState

/**
 * Variante A, « Tableau » (d'après tracker.gg et VRY) : une ligne par joueur, des colonnes de chiffres alignées, les deux
 * équipes côte à côte. Dense, se lit comme un tableur.
 */
object ValorantLookTable : ValorantLook {
    private const val STAT = 46
    private const val RANK = 76
    private const val PEAK = 34

    override fun match(v: Valorant, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        val later = if (v.state == ValorantState.PREGAME) R.string.valorant_enemies_later else null
        val allies = team(kit, R.string.valorant_allies, v.allies, null, full)
        val enemies = team(kit, R.string.valorant_enemies, v.enemies, later, full)
        return sides(kit, allies, enemies, full)
    }

    private fun sides(kit: ValorantKit, left: View, right: View, full: Boolean) = LinearLayout(kit.context).apply {
        orientation = if (full) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (full) {
            addView(left, kit.lp(0, MATCH_PARENT, 1f).apply { marginEnd = kit.dp(12) })
            addView(divider(kit), kit.lp(kit.dp(1), MATCH_PARENT))
            addView(right, kit.lp(0, MATCH_PARENT, 1f).apply { marginStart = kit.dp(12) })
        } else {
            addView(left, kit.lp(MATCH_PARENT, 0, 1f))
            addView(right, kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(6) })
        }
    }

    private fun divider(kit: ValorantKit) = View(kit.context).apply { setBackgroundColor(kit.color(R.attr.c_stroke)) }

    private fun team(kit: ValorantKit, title: Int, players: List<ValorantPlayer>, empty: Int?, full: Boolean) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        val head = kit.row(kit.caption(title))
        head.getChildAt(0).layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        if (full) {
            head.addView(kit.caption(R.string.valorant_col_rank, Gravity.START), kit.lp(kit.dp(RANK)))
            head.addView(kit.caption(R.string.valorant_col_peak, Gravity.CENTER), kit.lp(kit.dp(PEAK)))
            for (c in listOf(R.string.valorant_col_kd, R.string.valorant_col_hs, R.string.valorant_col_wr)) head.addView(kit.caption(c, Gravity.END), kit.lp(kit.dp(STAT)))
        } else {
            head.addView(kit.caption(R.string.valorant_col_kd, Gravity.END), kit.lp(kit.dp(STAT)))
        }
        addView(head, kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(2) })
        if (players.isEmpty() && empty != null) addView(kit.text(kit.string(empty), 14f, R.attr.c_text_faint))
        for (p in players) addView(player(kit, p, full), kit.lp(MATCH_PARENT, 0, 1f))
    }

    private fun player(kit: ValorantKit, p: ValorantPlayer, full: Boolean): View {
        val icon = if (full) 30 else 22
        val line = kit.row(
            kit.partyBar(p.party),
            kit.space(6),
            kit.agent(p.agentId, icon, dim = !p.locked),
            kit.space(8),
        )
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 15f else 13f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular)
        val who = if (full && p.level != null) kit.column(name, kit.text(kit.string(R.string.valorant_level_short, p.level), 12f, R.attr.c_text_faint)) else name
        line.addView(who, kit.lp(0, WRAP_CONTENT, 1f))
        if (full) {
            val rank = kit.row(kit.rank(p.tier, 26), kit.space(4), kit.text(ValorantKit.number(p.rr), 13f, R.attr.c_text_dim))
            line.addView(rank, kit.lp(kit.dp(RANK)))
            val peak = kit.rank(p.peakTier, 22).apply { alpha = if (p.peakTier == null) 0f else 1f }
            line.addView(kit.row(peak, gravity = Gravity.CENTER), kit.lp(kit.dp(PEAK)))
            line.addView(stat(kit, ValorantKit.kd(p.kd), p.kd), kit.lp(kit.dp(STAT)))
            line.addView(stat(kit, ValorantKit.pct(p.hs), p.hs), kit.lp(kit.dp(STAT)))
            line.addView(stat(kit, ValorantKit.pct(p.winRate), p.winRate), kit.lp(kit.dp(STAT)))
        } else {
            line.addView(kit.rank(p.tier, 22))
            line.addView(stat(kit, ValorantKit.kd(p.kd), p.kd), kit.lp(kit.dp(STAT)))
        }
        line.setPadding(0, 0, kit.dp(6), 0)
        if (p.me) kit.mine(line)
        line.contentDescription = kit.describe(p)
        return line
    }

    private fun stat(kit: ValorantKit, text: String, value: Any?) =
        kit.text(text, 14f, if (value == null) R.attr.c_text_faint else R.attr.c_text).apply { gravity = Gravity.END }

    override fun end(end: ValorantEnd, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        return sides(kit, board(kit, R.string.valorant_allies, end.allies, full), board(kit, R.string.valorant_enemies, end.enemies, full), full)
    }

    private fun board(kit: ValorantKit, title: Int, players: List<ValorantEndPlayer>, full: Boolean) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        val head = kit.row(kit.caption(title))
        head.getChildAt(0).layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        val cols = if (full) {
            listOf(R.string.valorant_col_acs to STAT, R.string.valorant_col_kda to 84, R.string.valorant_col_hs to STAT, R.string.valorant_col_adr to STAT, R.string.valorant_col_fk to 38)
        } else {
            listOf(R.string.valorant_col_acs to STAT, R.string.valorant_col_kda to 84)
        }
        for ((c, w) in cols) head.addView(kit.caption(c, Gravity.END), kit.lp(kit.dp(w)))
        addView(head, kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(2) })
        for (p in players) addView(scoreLine(kit, p, full), kit.lp(MATCH_PARENT, 0, 1f))
    }

    private fun scoreLine(kit: ValorantKit, p: ValorantEndPlayer, full: Boolean): View {
        val line = kit.row(kit.agent(p.agentId, if (full) 28 else 22), kit.space(8))
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 15f else 13f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular)
        line.addView(name, kit.lp(0, WRAP_CONTENT, 1f))
        if (p.mvp) line.addView(mvp(kit), kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(6) })
        line.addView(kit.text(p.acs.toString(), 15f, R.attr.c_text, R.attr.c_font_medium).apply { gravity = Gravity.END }, kit.lp(kit.dp(STAT)))
        line.addView(kit.text(kit.string(R.string.valorant_kda, p.kills, p.deaths, p.assists), 14f, R.attr.c_text_dim).apply { gravity = Gravity.END }, kit.lp(kit.dp(84)))
        if (full) {
            line.addView(stat(kit, ValorantKit.pct(p.hs), p.hs), kit.lp(kit.dp(STAT)))
            line.addView(stat(kit, p.adr.toString(), p.adr), kit.lp(kit.dp(STAT)))
            line.addView(stat(kit, p.firstKills.toString(), p.firstKills), kit.lp(kit.dp(38)))
        }
        line.setPadding(0, 0, kit.dp(6), 0)
        if (p.me) kit.mine(line)
        line.contentDescription = kit.describe(p)
        return line
    }

    /** Pastille « MVP » : texte sur l'accent (pas une étoile seule : le mot se lit). */
    fun mvp(kit: ValorantKit) = kit.text(kit.string(R.string.valorant_mvp), 12f, R.attr.c_on_accent, R.attr.c_font_medium).apply {
        background = GradientDrawable().apply {
            cornerRadius = kit.dp(4).toFloat()
            setColor(kit.color(R.attr.c_accent))
        }
        setPadding(kit.dp(5), kit.dp(1), kit.dp(5), kit.dp(1))
    }

    override fun menu(v: Valorant, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
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
            orientation = if (full) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            if (full) {
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
        line.addView(kit.text(score, 14f, R.attr.c_text, R.attr.c_font_medium), kit.lp(kit.dp(64)))
        line.addView(kit.agent(m.agentId, 24))
        line.addView(kit.text(m.map ?: ValorantKit.DASH, 14f, R.attr.c_text_dim), kit.lp(0, WRAP_CONTENT, 1f).apply { marginStart = kit.dp(8) })
        if (full) {
            val kda = if (m.kills != null && m.deaths != null && m.assists != null) kit.string(R.string.valorant_kda, m.kills, m.deaths, m.assists) else ValorantKit.DASH
            line.addView(kit.text(kda, 14f, R.attr.c_text_dim).apply { gravity = Gravity.END }, kit.lp(kit.dp(84)))
            line.addView(kit.text(ValorantKit.number(m.acs), 14f).apply { gravity = Gravity.END }, kit.lp(kit.dp(STAT)))
        }
        line.addView(kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(m.rr)), 14f, rrHue(m.rr), R.attr.c_font_medium).apply { gravity = Gravity.END }, kit.lp(kit.dp(70)))
        line.contentDescription = kit.describe(m)
        return line
    }
}
