package app.mate.ui.home

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import app.mate.ui.themeFont
import kotlin.math.abs

/**
 * Variante C, « Face-à-face » (d'après les habillages télé de l'esport) : les deux équipes en miroir de part et d'autre
 * d'un axe, le joueur n de chaque équipe sur la même ligne, les rangs l'un contre l'autre au milieu, une barre de K/D
 * (ou d'ACS) qui pousse vers l'extérieur. Au menu, les RR des dernières parties en barres.
 */
object ValorantLookVersus : ValorantLook {
    private const val SPINE = 96
    private const val BAR = 64

    override fun match(v: Valorant, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        val pregame = v.state == ValorantState.PREGAME
        val head = header(
            kit,
            left = kit.caption(R.string.valorant_allies, Gravity.END),
            middle = kit.text(v.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: "", if (full) 26f else 18f, R.attr.c_text, R.attr.c_font_display).apply { gravity = Gravity.CENTER },
            right = kit.caption(if (pregame) R.string.valorant_enemies_later else R.string.valorant_enemies, Gravity.START),
        )
        val allies = v.allies
        val enemies = v.enemies
        return LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(head, kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(2) })
            for (i in 0 until maxOf(allies.size, enemies.size)) {
                val a = allies.getOrNull(i)
                val e = enemies.getOrNull(i)
                val middle = kit.row(
                    a?.let { kit.rank(it.tier, if (full) 28 else 20) } ?: kit.space(if (full) 28 else 20),
                    kit.space(if (full) 14 else 8),
                    e?.let { kit.rank(it.tier, if (full) 28 else 20) } ?: kit.space(if (full) 28 else 20),
                    gravity = Gravity.CENTER,
                )
                addView(line(kit, a?.let { side(kit, it, left = true, full) }, middle, e?.let { side(kit, it, left = false, full) }, full), kit.lp(MATCH_PARENT, 0, 1f))
            }
        }
    }

    private fun header(kit: ValorantKit, left: View, middle: View, right: View) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left, kit.lp(0, WRAP_CONTENT, 1f).apply { marginEnd = kit.dp(8) })
        addView(middle, kit.lp(kit.dp(SPINE + 40)))
        addView(right, kit.lp(0, WRAP_CONTENT, 1f))
    }

    private fun line(kit: ValorantKit, left: View?, middle: View, right: View?, full: Boolean, spine: Int = if (full) SPINE else SPINE - 40) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left ?: View(kit.context), kit.lp(0, MATCH_PARENT, 1f))
        addView(middle, kit.lp(kit.dp(spine), MATCH_PARENT))
        addView(right ?: View(kit.context), kit.lp(0, MATCH_PARENT, 1f))
    }

    /** Barre qui pousse vers l'extérieur : depuis l'axe (à droite pour mon équipe, à gauche pour l'autre). */
    private fun bar(kit: ValorantKit, ratio: Float?, left: Boolean, hue: Int): View = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        val fill = View(kit.context).apply { setBackgroundColor(kit.color(hue)) }
        val r = (ratio ?: 0f).coerceIn(0f, 1f)
        val empty = View(kit.context)
        if (left) {
            addView(empty, kit.lp(0, MATCH_PARENT, 1f - r))
            addView(fill, kit.lp(0, MATCH_PARENT, r))
        } else {
            addView(fill, kit.lp(0, MATCH_PARENT, r))
            addView(empty, kit.lp(0, MATCH_PARENT, 1f - r))
        }
        background = android.graphics.drawable.GradientDrawable().apply { setColor(kit.color(R.attr.c_surface_raised)) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Une moitié de ligne : de l'extérieur vers l'axe, nom, stats, agent, trait du groupe (ordre inversé pour l'autre équipe). */
    private fun side(kit: ValorantKit, p: ValorantPlayer, left: Boolean, full: Boolean): View {
        val align = if (left) Gravity.END else Gravity.START
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 15f else 12f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular).apply { gravity = align }
        val who = kit.column(name, gravity = align)
        if (full) {
            val sub = listOfNotNull(p.level?.let { kit.string(R.string.valorant_level_short, it) }, p.hs?.let { kit.string(R.string.valorant_hs_value, it) }, p.winRate?.let { "$it % V" }).joinToString(" · ")
            who.addView(kit.text(sub, 12f, R.attr.c_text_faint).apply { gravity = align })
        }
        val kd = kit.column(kit.text(ValorantKit.kd(p.kd), if (full) 16f else 13f, if (p.kd == null) R.attr.c_text_faint else R.attr.c_text, R.attr.c_font_medium).apply { gravity = align }, gravity = align)
        if (full) kd.addView(bar(kit, p.kd?.let { (it / 2.5).toFloat() }, left, if (left) R.attr.c_accent else R.attr.c_text_dim), kit.lp(kit.dp(BAR), kit.dp(4)).apply { topMargin = kit.dp(3) })
        val icon = kit.agent(p.agentId, if (full) 34 else 22, dim = !p.locked)
        val parts = listOf(who, kit.space(8), kd, kit.space(8), icon, kit.space(6), kit.partyBar(p.party))
        val row = kit.row(gravity = Gravity.CENTER_VERTICAL)
        (if (left) parts else parts.reversed()).forEach { row.addView(it) }
        who.layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        kd.layoutParams = kit.lp(kit.dp(if (full) BAR else 40))
        row.setPadding(kit.dp(6), 0, kit.dp(6), 0)
        if (p.me) kit.mine(row)
        row.contentDescription = kit.describe(p)
        return row
    }

    override fun end(end: ValorantEnd, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        val head = header(
            kit,
            left = kit.text(kit.string(resultText(end.won)), if (full) 18f else 14f, resultHue(end.won), R.attr.c_font_medium).apply { gravity = Gravity.END },
            middle = kit.column(
                kit.text(end.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: "", if (full) 26f else 18f, R.attr.c_text, R.attr.c_font_display),
                kit.caption(R.string.valorant_col_acs, Gravity.CENTER),
                gravity = Gravity.CENTER_HORIZONTAL,
            ),
            right = kit.text(listOfNotNull(end.map, end.queue).joinToString(" · "), 13f, R.attr.c_text_dim),
        )
        val best = end.players.maxOfOrNull { it.acs }?.coerceAtLeast(1) ?: 1
        val allies = end.allies
        val enemies = end.enemies
        return LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(head, kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(2) })
            for (i in 0 until maxOf(allies.size, enemies.size)) {
                val a = allies.getOrNull(i)
                val e = enemies.getOrNull(i)
                val middle = kit.row(gravity = Gravity.CENTER).apply {
                    addView(kit.text(a?.acs?.toString() ?: "", if (full) 17f else 13f, R.attr.c_text, R.attr.c_font_medium).apply { gravity = Gravity.END }, kit.lp(0, WRAP_CONTENT, 1f))
                    addView(kit.space(if (full) 16 else 10))
                    addView(kit.text(e?.acs?.toString() ?: "", if (full) 17f else 13f, R.attr.c_text, R.attr.c_font_medium), kit.lp(0, WRAP_CONTENT, 1f))
                }
                addView(line(kit, a?.let { scoreSide(kit, it, best, left = true, full) }, middle, e?.let { scoreSide(kit, it, best, left = false, full) }, full, if (full) SPINE else 84), kit.lp(MATCH_PARENT, 0, 1f))
            }
        }
    }

    private fun scoreSide(kit: ValorantKit, p: ValorantEndPlayer, best: Int, left: Boolean, full: Boolean): View {
        val align = if (left) Gravity.END else Gravity.START
        val name = kit.text(p.name ?: kit.string(R.string.valorant_hidden), if (full) 15f else 12f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text, if (p.me) R.attr.c_font_medium else R.attr.c_font_regular).apply { gravity = align }
        val top = kit.row(gravity = Gravity.CENTER_VERTICAL)
        val mvp = if (p.mvp) ValorantLookTable.mvp(kit) else null
        (if (left) listOfNotNull(name, mvp?.let { kit.space(6) }, mvp) else listOfNotNull(mvp, mvp?.let { kit.space(6) }, name)).forEach { top.addView(it) }
        name.layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        val who = kit.column(top, gravity = align)
        top.layoutParams = kit.lp(MATCH_PARENT)
        if (full) {
            val sub = "${kit.string(R.string.valorant_kda, p.kills, p.deaths, p.assists)} · ${ValorantKit.pct(p.hs)} · ${p.adr} ${kit.string(R.string.valorant_col_adr)} · ${p.firstKills} ${kit.string(R.string.valorant_col_fk)}"
            who.addView(kit.text(sub, 12f, R.attr.c_text_dim).apply { gravity = align }, kit.lp(MATCH_PARENT))
            who.addView(bar(kit, p.acs / best.toFloat(), left, if (left) R.attr.c_accent else R.attr.c_text_dim), kit.lp(MATCH_PARENT, kit.dp(3)).apply { topMargin = kit.dp(3) })
        }
        val icon = kit.agent(p.agentId, if (full) 32 else 22)
        val parts = listOf(who, kit.space(8), icon)
        val row = kit.row(gravity = Gravity.CENTER_VERTICAL)
        (if (left) parts else parts.reversed()).forEach { row.addView(it) }
        who.layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        row.setPadding(kit.dp(6), 0, kit.dp(6), 0)
        if (p.me) kit.mine(row)
        row.contentDescription = kit.describe(p)
        return row
    }

    override fun menu(v: Valorant, kit: ValorantKit, fit: ValorantFit): View {
        val full = fit == ValorantFit.FULL
        val me = v.me ?: return kit.text(kit.string(R.string.valorant_off_hint), 16f, R.attr.c_text_dim)
        val info = kit.column(kit.text(me.rank, if (full) 24f else 20f, R.attr.c_text, R.attr.c_font_light))
        me.rr?.let { info.addView(kit.text(kit.string(R.string.valorant_rr, it), 14f, R.attr.c_text_dim)) }
        ValorantKit.rrProgress(me.tier, me.rr)?.let { info.addView(kit.rrBar(it), kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6); bottomMargin = kit.dp(6) }) }
        info.addView(kit.text(listOfNotNull(me.level?.let { kit.string(R.string.valorant_level_short, it) }, me.name.ifEmpty { null }).joinToString(" · "), 13f, R.attr.c_text_faint))
        val hero = kit.row(kit.rank(me.tier, if (full) 96 else 64), kit.space(12))
        hero.addView(info, kit.lp(0, WRAP_CONTENT, 1f))
        val games = v.history.take(if (full) 5 else 3)
        val chart = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.caption(R.string.valorant_last_matches))
            if (games.isEmpty()) addView(kit.text(kit.string(R.string.valorant_no_history), 14f, R.attr.c_text_faint))
            addView(RrBars(kit, games.map { it.rr }), kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(4) })
            val labels = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
            games.forEach { labels.addView(label(kit, it, full), kit.lp(0, WRAP_CONTENT, 1f)) }
            addView(labels, kit.lp(MATCH_PARENT))
        }
        return LinearLayout(kit.context).apply {
            orientation = if (full) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hero, if (full) kit.lp(kit.dp(320)) else kit.lp(MATCH_PARENT))
            addView(chart, if (full) kit.lp(0, MATCH_PARENT, 1f).apply { marginStart = kit.dp(16) } else kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(8) })
        }
    }

    private fun label(kit: ValorantKit, m: ValorantMatch, full: Boolean): View {
        val box = kit.row(kit.agent(m.agentId, if (full) 26 else 22), kit.space(6), gravity = Gravity.CENTER)
        val text = kit.column(kit.text(m.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: kit.string(resultText(m.won)), 13f, resultHue(m.won), R.attr.c_font_medium))
        if (full) text.addView(kit.text(m.map ?: ValorantKit.DASH, 12f, R.attr.c_text_faint))
        box.addView(text)
        box.contentDescription = kit.describe(m)
        return box
    }

    /** RR gagnés (vers le haut, vert) ou perdus (vers le bas, rouge) de chaque partie, autour d'une ligne de base ; valeur écrite au bout. */
    private class RrBars(private val kit: ValorantKit, private val values: List<Int>) : View(kit.context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val base = Paint().apply { color = kit.color(R.attr.c_stroke); strokeWidth = kit.dp(1).toFloat() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = kit.dp(14).toFloat() * resources.configuration.fontScale
            typeface = themeFont(kit.context, R.attr.c_font_medium)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (values.isEmpty()) return
            val mid = height / 2f
            val room = mid - text.textSize - kit.dp(4)
            val max = values.maxOf { abs(it) }.coerceAtLeast(1)
            val slot = width / values.size.toFloat()
            canvas.drawLine(0f, mid, width.toFloat(), mid, base)
            values.forEachIndexed { i, rr ->
                val hue = rrHue(rr)
                fill.color = kit.color(hue)
                text.color = kit.color(hue)
                val cx = slot * i + slot / 2
                val h = room * abs(rr) / max
                val w = kit.dp(22).toFloat()
                if (rr >= 0) rect.set(cx - w / 2, mid - h, cx + w / 2, mid) else rect.set(cx - w / 2, mid, cx + w / 2, mid + h)
                canvas.drawRoundRect(rect, kit.dp(3).toFloat(), kit.dp(3).toFloat(), fill)
                val label = ValorantKit.signed(rr)
                if (rr >= 0) canvas.drawText(label, cx, mid - h - kit.dp(4), text) else canvas.drawText(label, cx, mid + h + text.textSize + kit.dp(2), text)
            }
        }
    }
}
