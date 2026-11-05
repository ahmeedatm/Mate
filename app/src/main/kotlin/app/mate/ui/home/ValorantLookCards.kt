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
 * Variante B, « Cartes » (d'après l'écran de chargement de Valorant) : un joueur = une case, avec une grande icône
 * d'agent et son rang posé dessus, et un seul gros chiffre. Une équipe par rangée. Visuel, peu de chiffres.
 */
object ValorantLookCards : ValorantLook {
    override fun match(v: Valorant, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        return LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.caption(R.string.valorant_allies))
            addView(tiles(kit, v.allies.map { tile(kit, it, full) }), kit.lp(MATCH_PARENT, 0, 1f))
            addView(kit.caption(R.string.valorant_enemies), kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(4) })
            if (v.state == ValorantState.PREGAME) {
                addView(kit.text(kit.string(R.string.valorant_enemies_later), 14f, R.attr.c_text_faint), kit.lp(MATCH_PARENT, 0, 1f))
            } else {
                addView(tiles(kit, v.enemies.map { tile(kit, it, full) }), kit.lp(MATCH_PARENT, 0, 1f))
            }
        }
    }

    /** Rangée de cinq cases de même largeur (moins de joueurs : les cases gardent leur largeur). */
    private fun tiles(kit: ValorantKit, views: List<View>) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        weightSum = maxOf(5, views.size).toFloat()
        views.forEachIndexed { i, view -> addView(view, kit.lp(0, MATCH_PARENT, 1f).apply { if (i > 0) marginStart = kit.dp(6) }) }
    }

    /** Trait du groupe en haut de la case, la case à moi sur le fond de l'accent. */
    private fun frame(kit: ValorantKit, party: Int?, me: Boolean, content: View, description: String) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        val strip = View(kit.context).apply {
            background = ValorantKit.partyHue(party)?.let { GradientDrawable().apply { cornerRadius = kit.dp(2).toFloat(); setColor(kit.color(it)) } }
        }
        addView(strip, kit.lp(MATCH_PARENT, kit.dp(3)).apply { bottomMargin = kit.dp(4) })
        addView(content, kit.lp(MATCH_PARENT, 0, 1f))
        setPadding(kit.dp(6), kit.dp(2), kit.dp(6), kit.dp(4))
        if (me) kit.mine(this)
        contentDescription = description
    }

    private fun tile(kit: ValorantKit, p: ValorantPlayer, full: Boolean): View {
        val icon = if (full) 54 else 38
        val badge = if (full) 26 else 20
        val face = kit.overlay(kit.agent(p.agentId, icon, dim = !p.locked), kit.rank(p.tier, badge), Gravity.BOTTOM or Gravity.END, icon, badge)
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 14f else 12f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular)
        val kd = kit.row(
            kit.text(ValorantKit.kd(p.kd), if (full) 24f else 15f, if (p.kd == null) R.attr.c_text_faint else R.attr.c_text, if (full) R.attr.c_font_light else R.attr.c_font_medium),
            kit.space(4),
            kit.caption(R.string.valorant_col_kd),
            gravity = Gravity.BOTTOM,
        )
        val content = if (full) {
            val info = kit.column(name)
            p.level?.let { info.addView(kit.text(kit.string(R.string.valorant_level_short, it), 12f, R.attr.c_text_faint)) }
            info.addView(kd)
            info.addView(kit.text("${ValorantKit.pct(p.hs)} ${kit.string(R.string.valorant_col_hs).lowercase()} · ${ValorantKit.pct(p.winRate)} V", 12f, R.attr.c_text_dim))
            kit.row(face, kit.space(6)).apply { addView(info, kit.lp(0, WRAP_CONTENT, 1f)) }
        } else {
            kit.column(face, name, kd, gravity = Gravity.CENTER_HORIZONTAL)
        }
        return frame(kit, p.party, p.me, content, kit.describe(p))
    }

    override fun end(end: ValorantEnd, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        val score = end.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: ""
        val hero = kit.column(
            kit.text(kit.string(resultText(end.won)), if (full) 24f else 18f, resultHue(end.won), R.attr.c_font_medium),
            kit.text(score, if (full) 44f else 28f, R.attr.c_text, R.attr.c_font_display),
            kit.text(listOfNotNull(end.map, end.queue).joinToString(" · "), 13f, R.attr.c_text_dim),
        )
        val teams = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tiles(kit, end.allies.map { scoreTile(kit, it, full) }), kit.lp(MATCH_PARENT, 0, 1f))
            addView(tiles(kit, end.enemies.map { scoreTile(kit, it, full) }), kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(6) })
        }
        return LinearLayout(kit.context).apply {
            orientation = if (full) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            addView(hero, if (full) kit.lp(kit.dp(170), MATCH_PARENT) else kit.lp(MATCH_PARENT))
            hero.gravity = if (full) Gravity.CENTER_VERTICAL else Gravity.START
            addView(teams, if (full) kit.lp(0, MATCH_PARENT, 1f) else kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(6) })
        }
    }

    private fun scoreTile(kit: ValorantKit, p: ValorantEndPlayer, full: Boolean): View {
        val icon = if (full) 44 else 32
        val face = kit.agent(p.agentId, icon).apply {
            if (p.mvp) (background as? GradientDrawable)?.setStroke(kit.dp(2), kit.color(R.attr.c_accent))
            setPadding(if (p.mvp) kit.dp(2) else 0, if (p.mvp) kit.dp(2) else 0, if (p.mvp) kit.dp(2) else 0, if (p.mvp) kit.dp(2) else 0)
        }
        val acs = kit.row(
            kit.text(p.acs.toString(), if (full) 22f else 15f, R.attr.c_text, if (full) R.attr.c_font_light else R.attr.c_font_medium),
            kit.space(4),
            kit.caption(if (p.mvp) R.string.valorant_mvp else R.string.valorant_col_acs).apply { if (p.mvp) setTextColor(kit.color(R.attr.c_accent)) },
            gravity = Gravity.BOTTOM,
        )
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 13f else 12f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular)
        val content = if (full) {
            val info = kit.column(name, acs, kit.text("${kit.string(R.string.valorant_kda, p.kills, p.deaths, p.assists)} · ${ValorantKit.pct(p.hs)}", 12f, R.attr.c_text_dim))
            info.addView(kit.text("${p.adr} ${kit.string(R.string.valorant_col_adr)} · ${p.firstKills} ${kit.string(R.string.valorant_col_fk)}", 12f, R.attr.c_text_faint))
            kit.row(face, kit.space(6)).apply { addView(info, kit.lp(0, WRAP_CONTENT, 1f)) }
        } else {
            kit.column(face, acs, gravity = Gravity.CENTER_HORIZONTAL)
        }
        return frame(kit, null, p.me, content, kit.describe(p))
    }

    override fun menu(v: Valorant, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        val me = v.me ?: return kit.text(kit.string(R.string.valorant_off_hint), 16f, R.attr.c_text_dim)
        val info = kit.column(kit.text(me.rank, if (full) 30f else 22f, R.attr.c_text, R.attr.c_font_light))
        val progress = ValorantKit.rrProgress(me.tier, me.rr)
        me.rr?.let { info.addView(kit.text(if (progress != null) kit.string(R.string.valorant_rr_progress, it) else kit.string(R.string.valorant_rr, it), 14f, R.attr.c_text_dim)) }
        progress?.let { info.addView(kit.rrBar(it, 8), kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6); bottomMargin = kit.dp(6) }) }
        info.addView(kit.text(listOfNotNull(me.level?.let { kit.string(R.string.valorant_level_short, it) }, me.name.ifEmpty { null }).joinToString(" · "), 13f, R.attr.c_text_faint))
        me.peakTier?.let { peak ->
            info.addView(kit.row(kit.rank(peak, 20), kit.space(4), kit.text(kit.string(R.string.valorant_peak, kit.rankName(peak)), 12f, R.attr.c_text_faint)), kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(4) })
        }
        val hero = kit.row(kit.rank(me.tier, if (full) 128 else 72), kit.space(12))
        hero.addView(info, kit.lp(0, WRAP_CONTENT, 1f))
        val games = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.caption(R.string.valorant_last_matches))
            if (v.history.isEmpty()) addView(kit.text(kit.string(R.string.valorant_no_history), 14f, R.attr.c_text_faint))
            addView(tiles(kit, v.history.take(if (full) 5 else 3).map { game(kit, it, full) }).apply { weightSum = if (full) 5f else 3f; gravity = Gravity.CENTER_VERTICAL }, kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(4) })
        }
        return LinearLayout(kit.context).apply {
            orientation = if (full) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hero, if (full) kit.lp(0, WRAP_CONTENT, 0.9f) else kit.lp(MATCH_PARENT))
            addView(games, if (full) kit.lp(0, MATCH_PARENT, 1.1f).apply { marginStart = kit.dp(16) } else kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(8) })
        }
    }

    private fun game(kit: ValorantKit, m: ValorantMatch, full: Boolean): View {
        val icon = if (full) 48 else 36
        val band = View(kit.context).apply {
            background = GradientDrawable().apply { cornerRadius = kit.dp(2).toFloat(); setColor(kit.color(resultHue(m.won))) }
        }
        val box = kit.column(kit.agent(m.agentId, icon), gravity = Gravity.CENTER_HORIZONTAL)
        box.addView(band, kit.lp(kit.dp(icon), kit.dp(4)).apply { topMargin = kit.dp(3); bottomMargin = kit.dp(4) })
        box.addView(kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(m.rr)), if (full) 18f else 15f, rrHue(m.rr), R.attr.c_font_medium))
        box.addView(kit.text(m.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: kit.string(resultText(m.won)), 13f, R.attr.c_text))
        if (full) {
            box.addView(kit.text(m.map ?: ValorantKit.DASH, 12f, R.attr.c_text_faint))
            if (m.kills != null && m.deaths != null && m.assists != null) box.addView(kit.text(kit.string(R.string.valorant_kda, m.kills, m.deaths, m.assists), 12f, R.attr.c_text_dim))
        }
        box.contentDescription = kit.describe(m)
        return box
    }
}
