package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndPlayer
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMatch
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantRound
import app.mate.net.ValorantScore
import app.mate.net.ValorantState
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Vue agrandie du widget Valorant (deux doigts posés), variante « C · Face-à-face » choisie par Ahmed le 14/11/2026 :
 * le score en grand entre les deux équipes, la frise des rounds, les moyennes d'équipe tirées l'une contre l'autre, puis
 * les joueurs par paires (même place dans le classement) avec une barre qui pousse vers le centre : K/D en partie, ACS
 * après. Au menu : mon rang dans un cadran de RR, ma journée, mes dernières classées en barres montantes ou
 * descendantes. Combat à mort : null, le widget garde son tableau plein écran. Calculs : [ValorantDuel].
 */
object ValorantDuelView {
    private const val FACE = 44
    private const val WHO = 210
    private const val VS = 40

    /** Une barre garde la place de son chiffre, même pour une petite valeur. */
    private const val LABEL_MIN = 0.22f

    /** [bodyDp] : hauteur mesurée de la place, 0 si pas encore mesurée ([ValorantDuel.lines]). */
    fun view(v: Valorant, kit: ValorantKit, bodyDp: Int): View? {
        val end = v.end?.takeIf { it.status == ValorantEndStatus.READY }
        return when {
            end != null -> if (end.ffa) null else end(end, kit, bodyDp)
            v.state == ValorantState.INGAME || v.state == ValorantState.PREGAME -> if (v.allies.isEmpty()) null else match(v, kit, bodyDp)
            else -> menu(v, kit, bodyDp)
        }
    }

    // --- En partie

    private fun match(v: Valorant, kit: ValorantKit, bodyDp: Int): View {
        val live = v.state == ValorantState.INGAME
        val sub = listOfNotNull(
            v.map,
            v.queue,
            v.rounds.size.takeIf { live && it > 0 }?.let { kit.string(R.string.valorant_band_round, it + 1) },
            ValorantBand.mapRate(v.mapStats)?.let { kit.string(R.string.valorant_duel_map_rate, it) },
        )
        val box = kit.column(header(kit, v.score.takeIf { live }, v.map, sub, ValorantBand.cells(v.rounds, live = true).takeIf { live }, icons = false))
        val allies = v.allies
        val enemies = v.enemies
        tug(kit, R.string.valorant_duel_kd, ValorantDuel.average(allies.map { it.kd }), ValorantDuel.average(enemies.map { it.kd }), ValorantKit::kd)?.let { box.addView(it, tugLp(kit)) }
        tug(kit, R.string.valorant_duel_wr, ValorantDuel.average(allies.map { it.winRate?.toDouble() }), ValorantDuel.average(enemies.map { it.winRate?.toDouble() })) { ValorantKit.pct(it?.let(Math::round)?.toInt()) }
            ?.let { box.addView(it, tugLp(kit)) }
        val lines = ValorantDuel.lines(bodyDp, maxOf(allies.size, enemies.size), kit.context.resources.configuration.fontScale)
        val later = R.string.valorant_enemies_later.takeIf { v.state == ValorantState.PREGAME && enemies.isEmpty() }
        val pairs = ValorantDuel.pairs(allies, enemies).mapIndexed { i, (a, e) ->
            pair(kit, a?.let { live(kit, it, false, lines) }, e?.let { live(kit, it, true, lines) } ?: later?.takeIf { i == 0 }?.let { kit.text(kit.string(it), 14f, R.attr.c_text_faint).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL } }, a?.me == true)
        }
        box.addView(rows(kit, pairs), kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(10) })
        return box
    }

    private fun live(kit: ValorantKit, p: ValorantPlayer, right: Boolean, lines: Int): View {
        val rank = p.rank ?: kit.rankName(p.tier)
        val meta = p.peakTier?.let { kit.string(R.string.valorant_duel_rank_peak, rank, kit.rankName(it)) } ?: rank
        val dots = if (lines < 3) emptyList() else listOf(
            kit.string(R.string.valorant_duel_hs, ValorantKit.pct(p.hs)) to ValorantArt.hsTone(p.hs),
            kit.string(R.string.valorant_duel_wr_short, ValorantKit.pct(p.winRate)) to ValorantArt.wrTone(p.winRate),
        )
        val who = who(kit, p.name, p.me, right, meta.takeIf { lines >= 2 }, dots, ValorantArt.partyDot(kit, p.party))
        val face = ValorantArt.face(kit, p.agentId, p.agent, p.tier, FACE, dim = !p.locked)
        val bar = meter(kit, ValorantDuel.fraction(p.kd, ValorantDuel.KD_FULL), ValorantKit.kd(p.kd), ValorantArt.kdTone(p.kd), right)
        return side(kit, face, who, bar, right).apply { contentDescription = kit.describe(p) }
    }

    // --- Après la partie

    private fun end(e: ValorantEnd, kit: ValorantKit, bodyDp: Int): View {
        val sub = listOfNotNull(kit.string(resultText(e.won)), e.map, e.queue)
        val box = kit.column(header(kit, e.score, e.map, sub, ValorantBand.cells(e.rounds, live = false), icons = true))
        val allies = e.allies
        val enemies = e.enemies
        tug(kit, R.string.valorant_duel_acs, ValorantDuel.average(allies.map { it.acs.toDouble() }), ValorantDuel.average(enemies.map { it.acs.toDouble() })) { ValorantKit.number(it?.let(Math::round)?.toInt()) }
            ?.let { box.addView(it, tugLp(kit)) }
        e.rr?.let { rr ->
            val text = kit.string(R.string.valorant_duel_match_rr, ValorantKit.signed(rr), kit.string(resultText(e.won)))
            box.addView(kit.text(text, 15f, rrHue(rr), R.attr.c_font_medium).apply { gravity = Gravity.CENTER }, kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6) })
        }
        val lines = ValorantDuel.lines(bodyDp, maxOf(allies.size, enemies.size), kit.context.resources.configuration.fontScale)
        val places = EndPlaces(e.players)
        val pairs = ValorantDuel.pairs(allies, enemies).map { (a, b) ->
            pair(kit, a?.let { scored(kit, it, places, false, lines) }, b?.let { scored(kit, it, places, true, lines) }, a?.me == true)
        }
        box.addView(rows(kit, pairs), kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(10) })
        return box
    }

    /** Place de chacun dans la partie (ACS, premiers kills) : tiers du haut vert, du bas rouge. */
    private class EndPlaces(lobby: List<ValorantEndPlayer>) {
        private val count = lobby.size
        private val acs = lobby.map { it.acs }.sortedDescending()
        private val first = lobby.map { it.firstKills }.sortedDescending()
        val best = acs.firstOrNull() ?: 0

        fun acs(p: ValorantEndPlayer) = ValorantArt.rankTone(acs.indexOf(p.acs), count)
        fun first(p: ValorantEndPlayer) = ValorantArt.rankTone(first.indexOf(p.firstKills), count)
    }

    private fun scored(kit: ValorantKit, p: ValorantEndPlayer, places: EndPlaces, right: Boolean, lines: Int): View {
        val meta = kit.string(R.string.valorant_duel_kda_adr, p.kills, p.deaths, p.assists, p.adr)
        val dots = if (lines < 3) emptyList() else listOf(
            kit.string(R.string.valorant_duel_hs, ValorantKit.pct(p.hs)) to ValorantArt.hsTone(p.hs),
            kit.string(R.string.valorant_duel_first, p.firstKills) to places.first(p),
        )
        val badge = if (p.mvp) mvp(kit) else null
        val who = who(kit, p.name, p.me, right, meta.takeIf { lines >= 2 }, dots, badge)
        val face = ValorantArt.face(kit, p.agentId, p.agent, p.tier, FACE)
        val bar = meter(kit, ValorantDuel.fraction(p.acs.toDouble(), places.best.toDouble()), p.acs.toString(), places.acs(p), right)
        return side(kit, face, who, bar, right).apply { contentDescription = kit.describe(p) }
    }

    private fun mvp(kit: ValorantKit) = kit.text(kit.string(R.string.valorant_mvp), 11f, R.attr.c_on_accent, R.attr.c_font_title).apply {
        background = GradientDrawable().apply {
            cornerRadius = kit.dp(3).toFloat()
            setColor(kit.color(R.attr.c_accent))
        }
        setPadding(kit.dp(5), kit.dp(1), kit.dp(5), kit.dp(1))
        letterSpacing = 0.08f
        layoutParams = kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(6) }
    }

    // --- Morceaux communs

    /** « TON ÉQUIPE  7 – 5  EN FACE », le moment dessous, la frise des rounds. Sans score (sélection) : le nom de la carte. */
    private fun header(kit: ValorantKit, score: ValorantScore?, map: String?, sub: List<String>, rounds: List<ValorantRound?>?, icons: Boolean): View {
        fun team(title: Int, ally: Boolean, gravity: Int) = kit.text(kit.string(title).uppercase(), 15f, ValorantArt.teamHue(ally), R.attr.c_font_title).apply {
            letterSpacing = 0.14f
            this.gravity = gravity or Gravity.CENTER_VERTICAL
        }
        val middle = if (score != null) {
            kit.text(54f, R.attr.c_text, R.attr.c_font_display).apply { text = scoreText(kit, score) }
        } else {
            kit.text(map.orEmpty().uppercase(), 34f, R.attr.c_text, R.attr.c_font_display).apply { letterSpacing = 0.12f }
        }
        val line = kit.row(team(R.string.valorant_allies, true, Gravity.END), middle, team(R.string.valorant_enemies, false, Gravity.START))
        if (score != null) line.contentDescription = kit.string(R.string.valorant_duel_score, score.ally, score.enemy)
        line.getChildAt(0).layoutParams = kit.lp(0, WRAP_CONTENT, 1f).apply { marginEnd = kit.dp(28) }
        line.getChildAt(2).layoutParams = kit.lp(0, WRAP_CONTENT, 1f).apply { marginStart = kit.dp(28) }
        val box = kit.column(line, gravity = Gravity.CENTER_HORIZONTAL)
        line.layoutParams = kit.lp(MATCH_PARENT)
        if (sub.isNotEmpty()) {
            box.addView(kit.text(sub.joinToString(" · ").uppercase(), 12f, R.attr.c_text_faint).apply { letterSpacing = 0.12f }, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(4) })
        }
        if (!rounds.isNullOrEmpty()) {
            val frieze = ValorantFrieze(kit.context, rounds, if (icons) 18 else 10, icons, kit.color(R.attr.c_stroke))
            box.addView(frieze, kit.lp(kit.dp(FRIEZE_DP), WRAP_CONTENT).apply { topMargin = kit.dp(10) })
        }
        return box
    }

    private const val FRIEZE_DP = 620

    /** Score aux couleurs des équipes : mes rounds en turquoise, les leurs en rouge. */
    private fun scoreText(kit: ValorantKit, s: ValorantScore): CharSequence {
        val text = kit.string(R.string.valorant_score, s.ally, s.enemy)
        val mine = s.ally.toString()
        val theirs = s.enemy.toString()
        return SpannableStringBuilder(text).apply {
            setSpan(ForegroundColorSpan(kit.color(R.attr.c_hue_cyan)), 0, mine.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(kit.color(R.attr.c_hue_red)), text.length - theirs.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun tugLp(kit: ValorantKit) = kit.lp(MATCH_PARENT).apply {
        topMargin = kit.dp(8)
        marginStart = kit.dp(60)
        marginEnd = kit.dp(60)
    }

    /** Corde tirée : la moyenne de mon équipe à gauche, la leur à droite, chacune avec son chiffre. Null si l'une manque. */
    private fun tug(kit: ValorantKit, label: Int, mine: Double?, theirs: Double?, format: (Double?) -> String): View? {
        val share = ValorantDuel.share(mine, theirs) ?: return null
        fun track(fraction: Float, ally: Boolean) = LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (ally) Gravity.END else Gravity.START
            weightSum = 1f
            background = GradientDrawable().apply {
                cornerRadius = kit.dp(5).toFloat()
                setColor(kit.color(R.attr.c_surface_raised))
            }
            addView(
                View(kit.context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = kit.dp(5).toFloat()
                        setColor(kit.color(ValorantArt.teamHue(ally)))
                    }
                },
                kit.lp(0, MATCH_PARENT, fraction),
            )
        }
        fun value(text: String, gravity: Int) = kit.text(text, 14f, R.attr.c_text, R.attr.c_font_medium).apply { this.gravity = gravity }
        val name = kit.string(label)
        return kit.row(
            value(format(mine), Gravity.END),
            track(share, true),
            kit.text(name.uppercase(), 12f, R.attr.c_text_faint).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
            },
            track(1f - share, false),
            value(format(theirs), Gravity.START),
        ).apply {
            getChildAt(0).layoutParams = kit.lp(kit.dp(56)).apply { marginEnd = kit.dp(10) }
            getChildAt(1).layoutParams = kit.lp(0, kit.dp(10), 1f)
            getChildAt(2).layoutParams = kit.lp(kit.dp(120))
            getChildAt(3).layoutParams = kit.lp(0, kit.dp(10), 1f)
            getChildAt(4).layoutParams = kit.lp(kit.dp(56)).apply { marginStart = kit.dp(10) }
            contentDescription = kit.string(R.string.valorant_duel_tug, name, format(mine), format(theirs))
        }
    }

    /** Les paires se partagent la hauteur. */
    private fun rows(kit: ValorantKit, pairs: List<View>) = kit.column().apply { pairs.forEach { addView(it, kit.lp(MATCH_PARENT, 0, 1f)) } }

    private fun pair(kit: ValorantKit, left: View?, right: View?, mine: Boolean): View = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left ?: View(kit.context), kit.lp(0, MATCH_PARENT, 1f))
        addView(
            kit.text(kit.string(R.string.valorant_duel_vs), 12f, R.attr.c_text_faint, R.attr.c_font_title).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            kit.lp(kit.dp(VS)),
        )
        addView(right ?: View(kit.context), kit.lp(0, MATCH_PARENT, 1f))
        if (mine) left?.let(::mine)
    }

    /**
     * Ma ligne : un filet d'accent, pas l'aplat de la grille. Sur un aplat de teinte proche (Instrument nuit), la barre
     * perdrait son bord ; mon nom est aussi en gras.
     */
    private fun mine(view: View) {
        view.background = GradientDrawable().apply {
            cornerRadius = view.context.dp(MINE_RADIUS_DP).toFloat()
            setStroke(view.context.dp(MINE_STROKE_DP), themeColor(view.context, R.attr.c_accent))
        }
    }

    private const val MINE_RADIUS_DP = 10
    private const val MINE_STROKE_DP = 2

    /** Un joueur : icône, nom et détail, barre ; à droite, tout en miroir (la barre pousse toujours vers le centre). */
    private fun side(kit: ValorantKit, face: View, who: View, bar: View, right: Boolean): View = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dp(10), kit.dp(4), kit.dp(10), kit.dp(4))
        val parts = listOf(face to kit.lp(WRAP_CONTENT), who to kit.lp(kit.dp(WHO)).apply { marginStart = kit.dp(12); marginEnd = kit.dp(12) }, bar to kit.lp(0, kit.dp(34), 1f))
        for ((view, lp) in if (right) parts.reversed() else parts) addView(view, lp)
    }

    private fun who(kit: ValorantKit, name: String?, me: Boolean, right: Boolean, meta: String?, dots: List<Pair<String, ValorantArt.Tone>>, badge: View?): View {
        val align = if (right) Gravity.END else Gravity.START
        val title = kit.text((name?.substringBefore('#') ?: kit.string(R.string.valorant_hidden)).uppercase(), 16f, if (name == null) R.attr.c_text_faint else R.attr.c_text, R.attr.c_font_medium).apply {
            if (me) setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.03f
        }
        // Le nom se coupe avant la pastille (groupe, MVP) : elle ne sort jamais du champ.
        val top = kit.row(gravity = align or Gravity.CENTER_VERTICAL).apply {
            addView(title, kit.lp(0, WRAP_CONTENT, 1f))
            badge?.let { addView(it) }
            if (right) title.gravity = Gravity.END
        }
        val box = kit.column(top, gravity = align)
        top.layoutParams = kit.lp(MATCH_PARENT)
        meta?.let { box.addView(kit.text(it.uppercase(), 12f, R.attr.c_text_dim).apply { letterSpacing = 0.06f }, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(2) }) }
        if (dots.isNotEmpty()) {
            val line = kit.row()
            dots.forEachIndexed { i, (text, tone) ->
                line.addView(
                    kit.text(text, 12f, R.attr.c_text).apply {
                        background = ValorantArt.tinted(kit, tone.hue, if (tone == ValorantArt.Tone.NONE) ValorantArt.TINT / 3 else ValorantArt.TINT)
                        setPadding(kit.dp(6), kit.dp(1), kit.dp(6), kit.dp(1))
                    },
                    kit.lp(WRAP_CONTENT).apply { if (i > 0) marginStart = kit.dp(6) },
                )
            }
            box.addView(line, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(5) })
        }
        return box
    }

    /** Barre teintée selon la valeur (le chiffre est écrit dedans, côté extérieur), longue selon [fraction] de la place. */
    private fun meter(kit: ValorantKit, fraction: Float, value: String, tone: ValorantArt.Tone, right: Boolean): View = LinearLayout(kit.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (right) Gravity.START else Gravity.END
        weightSum = 1f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val bar = ValorantArt.chip(kit, value, tone, 0, 17f).apply {
            (this as android.widget.TextView).gravity = (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            setPadding(kit.dp(10), 0, kit.dp(10), 0)
        }
        addView(bar, kit.lp(0, MATCH_PARENT, fraction.coerceAtLeast(LABEL_MIN)))
    }

    // --- Au menu

    private fun menu(v: Valorant, kit: ValorantKit, bodyDp: Int): View {
        val me = v.me ?: return kit.text(kit.string(R.string.valorant_off_hint), 18f, R.attr.c_text_dim).apply { gravity = Gravity.CENTER }
        val left = kit.column(gravity = Gravity.CENTER_VERTICAL)
        v.today?.takeIf { it.results.isNotEmpty() }?.let { day ->
            left.addView(kit.caption(R.string.valorant_band_today))
            left.addView(kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(day.rr)), 44f, rrHue(day.rr), R.attr.c_font_title))
            left.addView(kit.text(kit.string(R.string.valorant_band_wl, day.wins, day.losses), 16f, R.attr.c_text_dim))
        }
        me.peakTier?.let { peak ->
            left.addView(kit.text(kit.string(R.string.valorant_duel_peak).uppercase(), 12f, R.attr.c_text_dim).apply { letterSpacing = 0.06f }, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(if (left.childCount > 0) 28 else 0) })
            left.addView(kit.row(kit.rank(peak, 40), kit.space(10), kit.text(kit.rankName(peak), 22f, R.attr.c_text, R.attr.c_font_title)), kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(6) })
        }
        val progress = ValorantKit.rrProgress(me.tier, me.rr)
        val dialDp = ValorantDuel.dialDp(bodyDp)
        val inside = kit.column(kit.rank(me.tier, dialDp * RANK_IN_DIAL / 100), gravity = Gravity.CENTER).apply {
            addView(kit.text(me.rank.uppercase(), 30f, R.attr.c_text, R.attr.c_font_title).apply { letterSpacing = 0.05f }, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(4) })
            me.rr?.let { addView(kit.text(kit.string(if (progress != null) R.string.valorant_duel_rr_of else R.string.valorant_rr, it), 17f, R.attr.c_text_dim)) }
        }
        val dial = FrameLayout(kit.context).apply {
            addView(RrDial(kit.context, progress), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(inside, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
            contentDescription = listOfNotNull(me.rank, me.rr?.let { kit.string(R.string.valorant_rr, it) }).joinToString(", ")
        }
        val games = v.history.take(LAST).reversed()
        val right = kit.column(gravity = Gravity.CENTER_VERTICAL).apply {
            if (games.isEmpty()) {
                addView(kit.text(kit.string(R.string.valorant_no_history), 15f, R.attr.c_text_faint))
            } else {
                addView(kit.text(kit.string(R.string.valorant_duel_last, games.size).uppercase(), 12f, R.attr.c_text_dim).apply { letterSpacing = 0.06f })
                addView(Swings(kit.context, games), kit.lp(MATCH_PARENT, kit.dp(minOf(SWINGS_DP, dialDp))).apply { topMargin = kit.dp(10) })
            }
        }
        return LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, kit.lp(0, WRAP_CONTENT, 1f).apply { marginStart = kit.dp(20) })
            addView(dial, kit.lp(kit.dp(dialDp), kit.dp(dialDp)).apply { marginStart = kit.dp(24); marginEnd = kit.dp(24) })
            addView(right, kit.lp(0, WRAP_CONTENT, 1f).apply { marginEnd = kit.dp(12) })
        }
    }

    private const val LAST = 5

    /** Logo du rang dans le cadran, en % de sa taille. */
    private const val RANK_IN_DIAL = 38
    private const val SWINGS_DP = 260

    /** Cadran de RR : la piste en relief, remplie d'accent de 0 à 100 ; piste seule en Immortel et Radiant. */
    @SuppressLint("ViewConstructor")
    private class RrDial(context: Context, private val progress: Float?) : View(context) {
        private val stroke = context.dp(16).toFloat()
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = themeColor(context, R.attr.c_surface_raised)
        }
        private val fill = Paint(track).apply {
            color = themeColor(context, R.attr.c_accent)
            strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height) - stroke
            oval.set((width - size) / 2f, (height - size) / 2f, (width + size) / 2f, (height + size) / 2f)
            canvas.drawArc(oval, 0f, 360f, false, track)
            progress?.takeIf { it > 0f }?.let { canvas.drawArc(oval, -90f, 360f * it, false, fill) }
        }
    }

    /** Mes dernières classées, de la plus ancienne à la plus récente : barre montante (RR gagnés) ou descendante, chiffre au bout, carte dessous. */
    @SuppressLint("ViewConstructor")
    private class Swings(context: Context, private val games: List<ValorantMatch>) : View(context) {
        private val swings = ValorantDuel.swings(games.map { it.rr })
        private val up = themeColor(context, R.attr.c_hue_cyan)
        private val down = themeColor(context, R.attr.c_hue_red)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val axis = Paint().apply { color = themeColor(context, R.attr.c_stroke) }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColor(context, R.attr.c_text)
            typeface = themeFont(context, R.attr.c_font_title)
            textSize = sp(context, 18f)
            textAlign = Paint.Align.CENTER
        }
        private val map = Paint(number).apply {
            color = themeColor(context, R.attr.c_text_dim)
            typeface = themeFont(context, R.attr.c_font_regular)
            textSize = sp(context, 11f)
            letterSpacing = 0.08f
        }
        private val rect = RectF()
        private val radius = context.dp(6).toFloat()
        private val minBar = context.dp(3).toFloat()
        private val numbers = games.map { ValorantKit.signed(it.rr) }
        private val maps = games.map { (it.map ?: ValorantKit.DASH).uppercase() }

        init {
            contentDescription = context.getString(R.string.valorant_duel_swings, games.joinToString(", ") { ValorantKit.signed(it.rr) })
        }

        override fun onDraw(canvas: Canvas) {
            if (games.isEmpty()) return
            val bottomText = map.textSize * 2
            val mid = (height - bottomText) / 2f
            val reach = mid - number.textSize * 1.6f
            val slot = width / games.size.toFloat()
            canvas.drawRect(0f, mid - 1, width.toFloat(), mid + 1, axis)
            games.forEachIndexed { i, game ->
                val s = swings[i]
                val left = i * slot + slot * 0.18f
                val right = (i + 1) * slot - slot * 0.18f
                val h = maxOf(reach * kotlin.math.abs(s), minBar)
                bar.color = if (game.rr >= 0) up else down
                if (game.rr >= 0) rect.set(left, mid - h, right, mid) else rect.set(left, mid, right, mid + h)
                canvas.drawRoundRect(rect, radius, radius, bar)
                val cx = (left + right) / 2f
                val y = if (game.rr >= 0) mid - h - number.textSize * 0.4f else mid + h + number.textSize
                canvas.drawText(numbers[i], cx, y, number)
                canvas.drawText(maps[i], cx, height - map.textSize * 0.5f, map)
            }
        }

        private companion object {
            fun sp(context: Context, value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
        }
    }

    /** Fond opaque de la vue agrandie : la grille ne se devine plus derrière les chiffres. */
    fun sheet(context: Context) = GradientDrawable().apply {
        cornerRadius = context.dp(SHEET_RADIUS_DP).toFloat()
        setColor(ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_surface), OPAQUE))
    }

    private const val SHEET_RADIUS_DP = 20
    private const val OPAQUE = 0xFF
}
