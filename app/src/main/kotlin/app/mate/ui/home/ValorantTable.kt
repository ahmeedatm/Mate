package app.mate.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndPlayer
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMatch
import app.mate.net.ValorantMe
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantScore
import app.mate.net.ValorantState

/**
 * Mise en forme du widget Valorant, style « Chaleur » (d'après tracker.gg, choisi par Ahmed le 05/11 au 2e tour) : une
 * ligne par joueur, icône d'agent et logo du rang, nom en capitales, et les stats dans des pastilles teintées selon la
 * valeur (vert bon, jaune moyen, rouge faible, toujours avec le chiffre). Équipes aux couleurs du jeu : turquoise pour la
 * mienne, rouge en face. Pastille, ligne et colonne : seulement mon rang et le score ([summary]).
 */
object ValorantTable {
    /** Largeurs en dp des pastilles et colonnes (une pastille garde sa taille, le texte grossi a l'air qu'il faut). */
    private const val CHIP = 52
    private const val ACS = 60
    private const val KDA = 84
    private const val FIRST = 40
    private const val PEAK = 30

    /** Largeur du mini bandeau de la carte en ligne (2 × 1 à 4 × 1). */
    private const val BAND_WIDTH = 230

    /** Hauteur d'une ligne quand elles ne s'étirent pas (grands widgets). */
    private const val ROW_DP = 52

    /** Taille de texte (réglage d'Accessibilité) au-delà de laquelle le tableau de fin perd ses trois dernières colonnes. */
    private const val LARGE_TEXT = 1.15f

    /** [fit] : ce qui tient dans la hauteur réelle ([ValorantFit]) ; sans l'équipe d'en face, ton équipe seule. */
    fun match(v: Valorant, kit: ValorantKit, shape: ValorantShape, fit: ValorantFit): View {
        val later = if (v.state == ValorantState.PREGAME) R.string.valorant_enemies_later else null
        val allies = team(kit, R.string.valorant_allies, true, v.allies, null, shape, fit)
        if (!fit.enemies) return allies
        val enemies = team(kit, R.string.valorant_enemies, false, v.enemies, later, shape, fit)
        return sides(kit, allies, enemies, shape.side)
    }

    private fun sides(kit: ValorantKit, left: View, right: View, side: Boolean) = LinearLayout(kit.context).apply {
        orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (side) {
            addView(left, kit.lp(0, MATCH_PARENT, 1f).apply { marginEnd = kit.dp(10) })
            addView(right, kit.lp(0, MATCH_PARENT, 1f).apply { marginStart = kit.dp(10) })
        } else {
            addView(left, kit.lp(MATCH_PARENT, 0, 1f))
            addView(right, kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(6) })
        }
    }

    /** Ligne d'un tableau : se partage la hauteur, ou garde la sienne (le tableau se centre alors). */
    private fun rowLp(kit: ValorantKit, stretch: Boolean) =
        if (stretch) kit.lp(MATCH_PARENT, 0, 1f) else kit.lp(MATCH_PARENT, kit.dp(ROW_DP))

    /** Titre de l'équipe à sa couleur, puis les légendes des colonnes. */
    private fun head(kit: ValorantKit, title: Int, ally: Boolean, captions: List<Pair<Int, Int>>) = kit.row(ValorantArt.teamTitle(kit, title, ally)).apply {
        getChildAt(0).layoutParams = kit.lp(0, WRAP_CONTENT, 1f)
        for ((c, w) in captions) addView(kit.caption(c, Gravity.CENTER), kit.lp(kit.dp(w)).apply { marginStart = kit.dp(ValorantArt.GAP) })
        // Même retrait que les lignes : chaque légende reste au-dessus de sa colonne.
        setPadding(kit.dp(4), 0, kit.dp(4), 0)
    }

    private fun team(kit: ValorantKit, title: Int, ally: Boolean, players: List<ValorantPlayer>, empty: Int?, shape: ValorantShape, fit: ValorantFit) = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val captions = if (fit.detail) {
            listOf(R.string.valorant_col_peak to PEAK, R.string.valorant_col_kd to CHIP, R.string.valorant_col_hs to CHIP, R.string.valorant_col_wr to CHIP)
        } else {
            listOf(R.string.valorant_col_kd to CHIP)
        }
        addView(head(kit, title, ally, captions), kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(4) })
        if (players.isEmpty() && empty != null) addView(kit.text(kit.string(empty), 14f, R.attr.c_text_faint))
        for (p in players) addView(player(kit, p, fit.detail, fit.level), rowLp(kit, shape.stretch))
    }

    /** Nom en capitales (le mien en gras, quelle que soit la police du thème) ; `spacing` faux : plus de place pour le tag. */
    private fun name(kit: ValorantKit, text: String?, me: Boolean, size: Float, spacing: Boolean = true) =
        kit.text((text ?: kit.string(R.string.valorant_hidden)).uppercase(), size, if (text == null) R.attr.c_text_faint else R.attr.c_text, R.attr.c_font_medium).apply {
            if (me) setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = if (spacing) 0.04f else 0f
        }

    /** Nom, pastille du groupe à côté (même couleur pour les joueurs venus ensemble), niveau dessous. */
    private fun who(kit: ValorantKit, p: ValorantPlayer, detail: Boolean, level: Boolean): View {
        // Le nom se coupe avant la pastille du groupe (un nom long ne la pousse jamais hors du champ).
        val top = kit.row(name(kit, p.name, p.me, if (detail) 14f else 13f).apply { layoutParams = kit.lp(0, WRAP_CONTENT, 1f) })
        ValorantArt.partyDot(kit, p.party)?.let { top.addView(it) }
        // Pas la place ([ValorantFit]) : le niveau tombe le premier.
        if (!level || p.level == null) return top
        return kit.column(top.apply { layoutParams = kit.lp(MATCH_PARENT) }, kit.text(kit.string(R.string.valorant_level_short, p.level).uppercase(), 12f, R.attr.c_text_faint).apply { letterSpacing = 0.06f })
    }

    private fun player(kit: ValorantKit, p: ValorantPlayer, detail: Boolean, level: Boolean): View {
        val line = kit.row(ValorantArt.face(kit, p.agentId, p.agent, p.tier, if (detail) 32 else 20, dim = !p.locked), kit.space(10))
        line.addView(who(kit, p, detail, level), kit.lp(0, WRAP_CONTENT, 1f))
        if (detail) {
            // Meilleur rang pas encore lu : la place reste vide (pas de logo « Non classé » trompeur, rien à lire à voix haute).
            val peak = if (p.peakTier == null) View(kit.context) else kit.rank(p.peakTier, 24)
            line.addView(peak, kit.lp(kit.dp(PEAK), kit.dp(24)).apply { marginStart = kit.dp(ValorantArt.GAP) })
            line.addView(ValorantArt.chip(kit, ValorantKit.kd(p.kd), ValorantArt.kdTone(p.kd), CHIP))
            line.addView(ValorantArt.chip(kit, ValorantKit.pct(p.hs), ValorantArt.hsTone(p.hs), CHIP))
            line.addView(ValorantArt.chip(kit, ValorantKit.pct(p.winRate), ValorantArt.wrTone(p.winRate), CHIP))
        } else {
            line.addView(ValorantArt.chip(kit, ValorantKit.kd(p.kd), ValorantArt.kdTone(p.kd), CHIP, 13f, tight = true))
        }
        line.setPadding(kit.dp(4), 0, kit.dp(4), 0)
        if (p.me) kit.mine(line)
        line.contentDescription = kit.describe(p)
        return line
    }

    private fun largeText(kit: ValorantKit) = kit.context.resources.configuration.fontScale > LARGE_TEXT

    fun end(end: ValorantEnd, kit: ValorantKit, shape: ValorantShape, fit: ValorantFit): View {
        // Texte grossi (Accessibilité) ou peu de hauteur : sans % tête, ADR et premiers kills, pour que les noms restent lisibles.
        val full = fit.detail && !largeText(kit)
        // Combat à mort : chacun pour soi, une seule liste classée par kills (ordre du compagnon).
        if (end.ffa) return board(kit, R.string.valorant_ffa, true, end.players, end.players, full, shape.stretch)
        if (!fit.enemies) return board(kit, R.string.valorant_allies, true, end.allies, end.players, full, shape.stretch)
        return sides(
            kit,
            board(kit, R.string.valorant_allies, true, end.allies, end.players, full, shape.stretch),
            board(kit, R.string.valorant_enemies, false, end.enemies, end.players, full, shape.stretch),
            shape.side,
        )
    }

    private fun board(kit: ValorantKit, title: Int, ally: Boolean, players: List<ValorantEndPlayer>, lobby: List<ValorantEndPlayer>, full: Boolean, stretch: Boolean) =
        LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val captions = mutableListOf(R.string.valorant_col_acs to ACS, R.string.valorant_col_kda to KDA)
            if (full) captions += listOf(R.string.valorant_col_hs to CHIP, R.string.valorant_col_adr to CHIP, R.string.valorant_col_fk to FIRST)
            addView(head(kit, title, ally, captions), kit.lp(MATCH_PARENT).apply { bottomMargin = kit.dp(4) })
            // Teinte de l'ACS, de l'ADR et des premiers kills : la place du joueur parmi tous ceux de la partie.
            val places = Places(lobby)
            for (p in players) addView(scoreLine(kit, p, places, full), rowLp(kit, stretch))
        }

    private class Places(lobby: List<ValorantEndPlayer>) {
        private val count = lobby.size
        private val acs = lobby.map { it.acs }.sortedDescending()
        private val adr = lobby.map { it.adr }.sortedDescending()
        private val first = lobby.map { it.firstKills }.sortedDescending()

        // À égalité, la meilleure place des ex aequo.
        fun acs(p: ValorantEndPlayer) = ValorantArt.rankTone(acs.indexOf(p.acs), count)
        fun adr(p: ValorantEndPlayer) = ValorantArt.rankTone(adr.indexOf(p.adr), count)
        fun first(p: ValorantEndPlayer) = ValorantArt.rankTone(first.indexOf(p.firstKills), count)
    }

    private fun scoreLine(kit: ValorantKit, p: ValorantEndPlayer, places: Places, full: Boolean): View {
        val line = kit.row(ValorantArt.face(kit, p.agentId, p.agent, p.tier, if (full) 32 else 20), kit.space(10))
        val top = kit.row(name(kit, p.name, p.me, if (full) 14f else 13f, spacing = false).apply { layoutParams = kit.lp(0, WRAP_CONTENT, 1f) })
        if (p.mvp) top.addView(mvp(kit), kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(6) })
        line.addView(top, kit.lp(0, WRAP_CONTENT, 1f))
        line.addView(if (full) ValorantArt.chip(kit, p.acs.toString(), places.acs(p), ACS, 17f) else ValorantArt.chip(kit, p.acs.toString(), places.acs(p), ACS, 13f, tight = true))
        line.addView(kit.text(kit.string(R.string.valorant_kda, p.kills, p.deaths, p.assists), 14f, R.attr.c_text_dim).apply { gravity = Gravity.CENTER }, kit.lp(kit.dp(KDA)).apply { marginStart = kit.dp(ValorantArt.GAP) })
        if (full) {
            line.addView(ValorantArt.chip(kit, ValorantKit.pct(p.hs), ValorantArt.hsTone(p.hs), CHIP))
            line.addView(ValorantArt.chip(kit, p.adr.toString(), places.adr(p), CHIP))
            line.addView(ValorantArt.chip(kit, p.firstKills.toString(), places.first(p), FIRST))
        }
        line.setPadding(kit.dp(4), 0, kit.dp(4), 0)
        if (p.me) kit.mine(line)
        line.contentDescription = kit.describe(p)
        return line
    }

    /** Pastille « MVP » : texte sur l'accent (un mot se lit, une étoile seule non). */
    private fun mvp(kit: ValorantKit) = kit.text(kit.string(R.string.valorant_mvp), 12f, R.attr.c_on_accent, R.attr.c_font_title).apply {
        background = GradientDrawable().apply {
            cornerRadius = kit.dp(3).toFloat()
            setColor(kit.color(R.attr.c_accent))
        }
        setPadding(kit.dp(5), kit.dp(1), kit.dp(5), kit.dp(1))
        letterSpacing = 0.08f
    }

    fun menu(v: Valorant, kit: ValorantKit, shape: ValorantShape): View {
        val me = v.me ?: return kit.text(kit.string(R.string.valorant_off_hint), 16f, R.attr.c_text_dim)
        val big = shape.detail
        val info = kit.column(kit.text(me.rank.uppercase(), if (big) 28f else 22f, R.attr.c_text, R.attr.c_font_title).apply { letterSpacing = 0.06f })
        me.rr?.let { info.addView(kit.text(kit.string(R.string.valorant_rr, it), 16f, R.attr.c_text_dim, R.attr.c_font_medium)) }
        ValorantKit.rrProgress(me.tier, me.rr)?.let { info.addView(kit.rrBar(it, 8), kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(8); bottomMargin = kit.dp(6) }) }
        val sub = listOfNotNull(me.level?.let { kit.string(R.string.valorant_level_short, it) }, me.name.ifEmpty { null }).joinToString(" · ")
        info.addView(kit.text(sub.uppercase(), 12f, R.attr.c_text_faint).apply { letterSpacing = 0.06f })
        val hero = kit.row(kit.rank(me.tier, if (big) 104 else 72), kit.space(14)).apply { addView(info, kit.lp(0, WRAP_CONTENT, 1f)) }
        val count = if (shape.side) 5 else 3
        val games = LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = count.toFloat()
            if (v.history.isEmpty()) addView(kit.text(kit.string(R.string.valorant_no_history), 14f, R.attr.c_text_faint))
            v.history.take(count).forEachIndexed { i, m -> addView(game(kit, m), kit.lp(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = kit.dp(6) }) }
        }
        // Libellé collé aux cases, le bloc centré en hauteur.
        val right = kit.column(kit.caption(R.string.valorant_last_matches)).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(games, kit.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = kit.dp(4) })
        }
        return LinearLayout(kit.context).apply {
            orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            if (shape.side) {
                addView(hero, kit.lp(0, WRAP_CONTENT, 0.85f))
                addView(right, kit.lp(0, MATCH_PARENT, 1.15f).apply { marginStart = kit.dp(16) })
            } else {
                addView(hero, kit.lp(MATCH_PARENT))
                addView(right, kit.lp(MATCH_PARENT, 0, 1f).apply { topMargin = kit.dp(10) })
            }
        }
    }

    /** Une partie : case teintée du résultat (vert gagné, rouge perdu), agent, RR gagnés ou perdus, score, carte. */
    private fun game(kit: ValorantKit, m: ValorantMatch): View = kit.column(gravity = Gravity.CENTER_HORIZONTAL).apply {
        addView(kit.agent(m.agentId, 36, name = m.agent), kit.lp(kit.dp(36), kit.dp(36)))
        val score = m.score?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) } ?: kit.string(resultText(m.won))
        val lines = listOf(
            // Sur la case teintée du résultat : texte du thème (le signe et la case disent déjà gagné ou perdu).
            kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(m.rr)), 18f, R.attr.c_text, R.attr.c_font_title).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            kit.text(score, 13f, R.attr.c_text),
            kit.text((m.map ?: ValorantKit.DASH).uppercase(), 12f, R.attr.c_text_dim).apply { letterSpacing = 0.06f },
        )
        for (t in lines) addView(t.apply { gravity = Gravity.CENTER }, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(2) })
        background = GradientDrawable().apply {
            cornerRadius = kit.dp(8).toFloat()
            setColor(ColorUtils.setAlphaComponent(kit.color(resultHue(m.won)), ValorantArt.TINT))
        }
        setPadding(0, kit.dp(8), 0, kit.dp(8))
        contentDescription = kit.describe(m)
    }

    /**
     * Pastille, ligne, colonne : une info par niveau d'importance, choisie par [ValorantGlance] (le score ou le
     * résultat, mon rang, la carte, puis ma ligne, ma journée ou mes dernières). `band` : le mini bandeau de la carte
     * (score et frise sur la photo). [heightDp] : hauteur mesurée de la place (0 : pas encore mesurée), le logo s'y tient.
     */
    fun summary(v: Valorant, kit: ValorantKit, size: WidgetSize, band: View?, heightDp: Int): View {
        val glance = ValorantGlance.of(v, size)
        val me = v.me
        if (me == null && glance.rank) return kit.text(kit.string(R.string.valorant_off_hint), 14f, R.attr.c_text_dim)
        val family = size.family
        val end = v.end?.takeIf { it.status == ValorantEndStatus.READY }
        val parts = mutableListOf<View>()
        if (glance.score) v.score?.let { parts += score(kit, it, if (family == ShapeFamily.PILL) 30f else 34f) }
        if (glance.score && family == ShapeFamily.COLUMN) {
            parts += ValorantFrieze(kit.context, ValorantBand.cells(v.rounds, live = true), FRIEZE_DP, false, kit.color(R.attr.c_stroke))
        }
        if (glance.result && end != null) parts += result(kit, end, pill = family == ShapeFamily.PILL, word = family != ShapeFamily.PILL || heightDp <= 0 || heightDp >= PILL_WORD_DP)
        if (glance.myLine) end?.players?.firstOrNull { it.me }?.let { parts += kit.text(kit.string(R.string.valorant_acs_value, it.acs) + " · " + kit.string(R.string.valorant_kda, it.kills, it.deaths, it.assists), 13f, R.attr.c_text_dim) }
        val rank = if (glance.rank && me != null) rankBlock(kit, me, family, heightDp) else null
        val today = v.today?.takeIf { glance.today }?.let { day ->
            kit.column(kit.caption(R.string.valorant_band_today), kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(day.rr)), 20f, rrHue(day.rr), R.attr.c_font_title), gravity = Gravity.END).apply {
                addView(kit.text(kit.string(R.string.valorant_band_wl, day.wins, day.losses), 13f, R.attr.c_text_dim))
            }
        }
        val history = v.history.takeIf { glance.history }?.let { dots(kit, it) }
        val shown = if (glance.band) band else null
        val view = when (family) {
            ShapeFamily.ROW -> kit.row().apply {
                rank?.let { addView(it, kit.lp(0, WRAP_CONTENT, 1f)) }
                parts.forEachIndexed { i, it -> addView(it, kit.lp(WRAP_CONTENT).apply { if (i > 0 || rank != null) marginStart = kit.dp(24) }) }
                // En partie sans rang (2 × 1) : le bandeau prend toute la ligne.
                shown?.let { addView(it, kit.lp(if (rank == null) 0 else kit.dp(BAND_WIDTH), kit.dp(ValorantBand.Size.MINI.heightDp), if (rank == null) 1f else 0f).apply { if (rank != null) marginStart = kit.dp(12) }) }
                today?.let { addView(it, kit.lp(WRAP_CONTENT).apply { marginStart = kit.dp(12) }) }
            }
            else -> kit.column(gravity = Gravity.CENTER).apply {
                rank?.let { addView(it, kit.lp(MATCH_PARENT)) }
                parts.forEachIndexed { i, it ->
                    val frieze = it is ValorantFrieze
                    addView(it, kit.lp(if (frieze) MATCH_PARENT else WRAP_CONTENT).apply { if (i > 0 || rank != null) topMargin = kit.dp(if (frieze) 10 else 6) })
                }
                shown?.let { addView(it, kit.lp(MATCH_PARENT, kit.dp(ValorantBand.Size.MINI.heightDp)).apply { if (rank != null) topMargin = kit.dp(10) }) }
                history?.let { addView(it, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(12) }) }
            }
        }
        val words = listOfNotNull(
            me?.rank?.takeIf { glance.rank },
            me?.rr?.takeIf { glance.rank }?.let { kit.string(R.string.valorant_rr, it) },
            v.score?.takeIf { glance.score }?.let { kit.string(R.string.valorant_score, it.ally, it.enemy) },
            end?.takeIf { glance.result }?.let { kit.string(resultText(it.won)) },
            end?.rr?.takeIf { glance.result }?.let { kit.string(R.string.valorant_rr_change, ValorantKit.signed(it)) },
        )
        if (words.isNotEmpty()) view.contentDescription = words.joinToString(", ")
        return view
    }

    /** Score en grand, teinté de l'équipe qui mène (le chiffre le dit aussi). */
    private fun score(kit: ValorantKit, s: ValorantScore, size: Float): View {
        val hue = when {
            s.ally > s.enemy -> R.attr.c_hue_cyan
            s.ally < s.enemy -> R.attr.c_hue_red
            else -> R.attr.c_text
        }
        return kit.text(kit.string(R.string.valorant_score, s.ally, s.enemy), size, hue, R.attr.c_font_display).apply { gravity = Gravity.CENTER }
    }

    /** Partie finie : le mot (Victoire…) et les RR gagnés en grand (le mot dessous en pastille) ; sans RR encore comptés, le score. */
    private fun result(kit: ValorantKit, end: ValorantEnd, pill: Boolean, word: Boolean): View {
        val big = end.rr?.let { kit.text(kit.string(R.string.valorant_rr_change, ValorantKit.signed(it)), 26f, rrHue(it), R.attr.c_font_title) }
            ?: end.score?.let { kit.text(kit.string(R.string.valorant_score, it.ally, it.enemy), 26f, resultHue(end.won), R.attr.c_font_title) }
        val label = kit.text(kit.string(resultText(end.won)), 14f, resultHue(end.won), R.attr.c_font_medium)
        return kit.column(gravity = Gravity.CENTER).apply {
            if (!pill && word) addView(label)
            big?.let { addView(it) }
            // Pastille basse : le chiffre seul, sa couleur dit gagné ou perdu (et la description le lit).
            if (pill && (word || big == null)) addView(label)
        }
    }

    /** Mon rang : logo à la taille de la place, nom, RR et barre (pastille : logo et RR seulement). */
    private fun rankBlock(kit: ValorantKit, me: ValorantMe, family: ShapeFamily, heightDp: Int): View {
        val rr = me.rr?.let { kit.text(kit.string(R.string.valorant_rr, it), 13f, R.attr.c_text_dim) }
        if (family == ShapeFamily.PILL) {
            // Texte et marge sous le logo : ~30 dp ; une rangée basse rapetisse le logo plutôt que de le rogner.
            val logo = if (heightDp > 0) (heightDp - PILL_TEXT_DP).coerceIn(PILL_LOGO_MIN, PILL_LOGO) else PILL_LOGO
            return kit.column(kit.rank(me.tier, logo), gravity = Gravity.CENTER).apply { rr?.let { addView(it, kit.lp(WRAP_CONTENT).apply { topMargin = kit.dp(4) }) } }
        }
        val bar = ValorantKit.rrProgress(me.tier, me.rr)?.let { kit.rrBar(it) }
        val name = kit.text(me.rank.uppercase(), 18f, R.attr.c_text, R.attr.c_font_title).apply { letterSpacing = 0.06f }
        if (family == ShapeFamily.ROW) {
            val logo = if (heightDp > 0) heightDp.coerceIn(PILL_LOGO_MIN, ROW_LOGO) else ROW_LOGO
            val info = kit.column(name)
            rr?.let { info.addView(it) }
            bar?.let { info.addView(it, kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6) }) }
            return kit.row(kit.rank(me.tier, logo), kit.space(12)).apply { addView(info, kit.lp(0, WRAP_CONTENT, 1f)) }
        }
        return kit.column(kit.rank(me.tier, COLUMN_LOGO), name, gravity = Gravity.CENTER).apply {
            rr?.let { addView(it) }
            bar?.let { addView(it, kit.lp(MATCH_PARENT).apply { topMargin = kit.dp(6) }) }
        }
    }

    /** Mes dernières classées en pastilles, de la plus ancienne à la plus récente : pleine gagnée, creuse perdue. */
    private fun dots(kit: ValorantKit, games: List<ValorantMatch>): View = kit.row().apply {
        games.take(DOTS).reversed().forEachIndexed { i, m ->
            addView(
                View(kit.context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (m.won == true) setColor(kit.color(resultHue(m.won))) else setStroke(kit.dp(2), kit.color(resultHue(m.won)))
                    }
                },
                kit.lp(kit.dp(DOT_DP), kit.dp(DOT_DP)).apply { if (i > 0) marginStart = kit.dp(6) },
            )
        }
        contentDescription = games.take(DOTS).joinToString(", ") { kit.string(resultText(it.won)) }
    }

    private const val FRIEZE_DP = 8
    private const val PILL_LOGO = 56
    private const val PILL_LOGO_MIN = 24
    private const val PILL_TEXT_DP = 30

    /** Hauteur sous laquelle la pastille ne garde que les RR, sans le mot du résultat. */
    private const val PILL_WORD_DP = 64
    private const val ROW_LOGO = 64
    private const val COLUMN_LOGO = 72
    private const val DOTS = 5
    private const val DOT_DP = 12
}
