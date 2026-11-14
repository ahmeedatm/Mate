package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMapStats
import app.mate.net.ValorantMe
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantRound
import app.mate.net.ValorantScore
import app.mate.net.ValorantState
import app.mate.net.ValorantToday
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Aperçu du catalogue : une partie en cours, sans réseau ni images (cases vides à la place des logos). */
class ValorantSample(val valorant: Valorant) : WidgetSample

/**
 * Valorant, lu par le compagnon dans le client Riot de l'ordinateur (Windows ; le jeton Riot n'en sort pas). En partie :
 * une ligne par joueur (agent, nom, niveau, rang et RR, meilleur rang, K/D, % tête, % de victoires, groupe), ton équipe
 * puis l'équipe d'en face, côte à côte quand le widget est large ; pendant la sélection, ton équipe seulement (Riot ne
 * donne l'autre qu'au début de la partie). Après la partie : le tableau des scores, 2 min. Au menu : ton rang et tes
 * dernières classées. Pastille, ligne et colonne : l'essentiel selon le moment et la place ([ValorantGlance]). Le
 * tableau ne garde que ce qui tient dans la hauteur réelle ([ValorantFit]). Bandeau de la carte ([ValorantBand]) : mini à
 * la place du score, fin à côté du titre, moyen ou plein au-dessus du tableau. Agrandi ([zoomed]) : la vue Face-à-face
 * ([ValorantDuelView]) sur un fond opaque. Relu toutes les 5 s pendant une file ou une partie, toutes les 15 s sinon,
 * seulement quand le widget est affiché.
 */
class ValorantWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_valorant)
    private val status = label(15f).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        // Peu de joueurs dans un grand widget : les lignes s'étirent jusqu'en bas plutôt que de laisser un vide.
        isFillViewport = true
        addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Bandeau fin de la carte, à droite du titre et de la ligne d'état (blocs et grands widgets). */
    private val slot = FrameLayout(context).apply { visibility = View.GONE }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(slot, LinearLayout.LayoutParams(0, dp(ValorantBand.Size.THIN.heightDp), 1f).apply { marginStart = dp(12) })
        }
        addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    /** Aperçu du catalogue : aucune image demandée à l'ordinateur. */
    private var preview = false
    private var last: Valorant? = null
    private var error: String? = null
    private var job: Job? = null

    /** Dernier état dessiné, avec la forme et la hauteur : rien n'est refait tant que rien ne change (la plupart des relevés). */
    private var drawn: Triple<Valorant?, WidgetSize, Int>? = null

    /** Hauteur mesurée de la place sous le titre, en dp (0 : pas encore mesurée) ; elle change avec le thème et le profil de rangées. */
    private var bodyDp = 0

    init {
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = ((bottom - top) / context.resources.displayMetrics.density).toInt()
            if (height != bodyDp) {
                bodyDp = height
                // Après la mise en page en cours : ce qui tient dans la nouvelle hauteur.
                scroll.post { render() }
            }
        }
    }

    override fun applySize() {
        // Pastille : ni titre ni ligne d'état, le logo et le chiffre prennent toute la case.
        val pill = family == ShapeFamily.PILL
        title.visibility = if (pill) View.GONE else View.VISIBLE
        status.visibility = if (pill) View.GONE else View.VISIBLE
        // Agrandi : un fond opaque, la grille ne se devine plus derrière les chiffres.
        if (zoomed) view.background = ValorantDuelView.sheet(context)
        drawn = null
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = (sample as? ValorantSample)?.valorant ?: return
        preview = true
        val kit = ValorantKit(context, host.scope, online = false)
        last = example.copy(me = example.me?.copy(rank = kit.rankName(example.me.tier)), players = example.players.map { it.copy(rank = kit.rankName(it.tier)) })
        error = null
        drawn = null
        render()
    }

    override fun setActive(active: Boolean) {
        if (!active) {
            job?.cancel()
            job = null
        } else if (job?.isActive != true) {
            job = host.scope.launch {
                while (isActive) {
                    load()
                    val busy = last?.state.let { it == ValorantState.QUEUE || it == ValorantState.PREGAME || it == ValorantState.INGAME }
                    delay(if (busy) BUSY_MS else IDLE_MS)
                }
            }
        }
    }

    override suspend fun load() {
        runCatchingCancellable { BackendClient(context).valorant.state() }
            .onSuccess { last = it; error = null }
            .onFailure { error = context.errorText(it) }
        render()
    }

    private fun render() {
        val v = last
        val shape = ValorantShape.of(placed.size)
        val size = if (zoomed) ValorantBand.Size.NONE else ValorantBand.Size.of(placed.size)
        // Forme résumée : le bandeau seulement là où il porte l'info ([ValorantGlance]).
        val glance = v?.takeIf { shape.summary }?.let { ValorantGlance.of(it, placed.size) }
        val showsBand = v != null && ValorantBand.shows(v, size) && (glance == null || glance.band)
        // Le score ou le résultat déjà écrit en grand (bandeau, forme résumée) : la ligne d'état garde le moment.
        val line = error ?: statusLine(v, showsBand || glance?.score == true || glance?.result == true)
        if (status.text.toString() != line) status.text = line
        // Ordinateur muet : la dernière partie reste, estompée, pour ne pas passer pour l'état présent.
        body.alpha = if (error != null) STALE_ALPHA else 1f
        val key = Triple(v, placed.size, bodyDp)
        if (drawn == key) return
        drawn = key
        body.removeAllViews()
        slot.removeAllViews()
        // Un kit par rendu : ses images partent vers l'ordinateur actif du moment, avec un seul client.
        val kit = ValorantKit(context, host.scope, online = !preview)
        val fill = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val end = v?.end?.takeIf { it.status == ValorantEndStatus.READY }
        val band = v?.takeIf { showsBand }?.let { ValorantBand.view(it, kit, size) }
        slot.visibility = if (size == ValorantBand.Size.THIN && band != null) View.VISIBLE else View.GONE
        if (size == ValorantBand.Size.THIN && band != null) slot.addView(band, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val big = band != null && (size == ValorantBand.Size.MEDIUM || size == ValorantBand.Size.FULL)
        // Place du tableau : celle du corps, moins le bandeau et la légende posés au-dessus et dessous.
        val legend = big && end != null && size == ValorantBand.Size.FULL
        val tableDp = if (bodyDp <= 0 || v == null || !big) bodyDp else bodyDp - ValorantBand.heightDp(v, size) - BAND_GAP_DP - if (legend) LEGEND_DP else 0
        val fit = ValorantFit.of(shape, tableDp, context.resources.configuration.fontScale)
        val main = when {
            v == null || v.state == ValorantState.OFF -> center(context.getString(R.string.valorant_off_hint))
            zoomed -> ValorantDuelView.view(v, kit, bodyDp) ?: table(v, end, kit, shape, fit)
            shape.summary -> ValorantTable.summary(v, kit, placed.size, band.takeIf { size == ValorantBand.Size.MINI }, bodyDp)
            else -> table(v, end, kit, shape, fit)
        }
        if (!big) {
            body.addView(main, fill)
            return
        }
        // Moyen et plein : le bandeau en haut, le tableau dessous (centré dans la place qui reste), la légende de la frise à la fin.
        body.addView(band, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ValorantBand.heightDp(v!!, size))))
        body.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(BAND_GAP_DP) })
        if (legend) body.addView(ValorantBand.legend(kit), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    private fun table(v: Valorant, end: ValorantEnd?, kit: ValorantKit, shape: ValorantShape, fit: ValorantFit): View = when {
        end != null -> ValorantTable.end(end, kit, shape, fit)
        v.state == ValorantState.MENU || v.state == ValorantState.QUEUE -> ValorantTable.menu(v, kit, shape)
        else -> ValorantTable.match(v, kit, shape, fit)
    }

    private fun statusLine(v: Valorant?, said: Boolean): String {
        if (v == null || v.state == ValorantState.OFF) return context.getString(R.string.valorant_off)
        val full = fullLine(v)
        // Bandeau, chiffre en grand ou vue agrandie : la carte et le score y sont déjà ; la ligne d'état garde le moment.
        return if (said || zoomed) full.substringBefore(" · ") else full
    }

    private fun fullLine(v: Valorant): String {
        v.end?.let { end ->
            return when (end.status) {
                ValorantEndStatus.READY -> listOfNotNull(
                    context.getString(if (end.ffa) R.string.valorant_ffa else resultText(end.won)),
                    end.score?.let { context.getString(R.string.valorant_score, it.ally, it.enemy) },
                    end.queue.takeIf { placed.size.w >= 3 },
                    end.map.takeIf { placed.size.w >= 3 },
                ).joinToString(" · ")
                ValorantEndStatus.LOADING -> context.getString(R.string.valorant_end_loading)
                ValorantEndStatus.MISSING -> context.getString(R.string.valorant_end_missing)
            }
        }
        val state = context.getString(
            when (v.state) {
                ValorantState.QUEUE -> R.string.valorant_queue
                ValorantState.PREGAME -> R.string.valorant_pregame
                ValorantState.INGAME -> R.string.valorant_ingame
                else -> R.string.valorant_menu
            },
        )
        val score = v.score?.let { context.getString(R.string.valorant_score, it.ally, it.enemy) }
        // Moins de 3 cases de large : l'état et le score seulement, pour que le score ne soit pas coupé.
        if (placed.size.w < 3) return listOfNotNull(state, score).joinToString(" · ")
        return listOfNotNull(state, v.queue, v.map, score).joinToString(" · ")
    }

    private fun center(text: String) = label(16f, R.attr.c_text_dim).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(0, dp(if (family == ShapeFamily.PILL) 0 else 24), 0, 0)
    }

    companion object {
        private const val BUSY_MS = 5_000L
        private const val IDLE_MS = 15_000L
        private const val STALE_ALPHA = 0.4f
        private const val BAND_GAP_DP = 14

        /** Légende des fins de round sous le tableau de fin, avec sa marge. */
        private const val LEGEND_DP = 30

        private fun player(name: String?, tier: Int, rr: Int, ally: Boolean, level: Int, peak: Int, kd: Double, hs: Int, wr: Int, party: Int? = null, me: Boolean = false) =
            ValorantPlayer(name, null, true, tier, null, rr, ally, me, null, level, peak, wr, kd, hs, party)

        /** Exemple du catalogue : une partie classée à 7 – 5 ; rangs nommés au dessin ([ValorantWidget.showPreview]). */
        val SAMPLE = ValorantSample(
            Valorant(
                ValorantState.INGAME,
                null,
                "Ascent",
                ValorantScore(7, 5),
                ValorantMe("Eliot#EUW", 14, "", 42, 214, 17),
                listOf(
                    player("Nox#7781", 19, 12, true, 36, 24, 1.62, 31, 82),
                    player("Kinj#EUW", 15, 61, true, 187, 16, 0.87, 18, 59, party = 1),
                    player("Eliot#EUW", 14, 42, true, 214, 17, 1.13, 24, 56, party = 1, me = true),
                    player("Mélodie#FR1", 13, 88, true, 402, 14, 0.98, 21, 49),
                    player(null, 11, 30, true, 77, 12, 0.80, 15, 44),
                    player("Lune#2402", 21, 7, false, 22, 25, 1.96, 34, 92),
                    player("Coco#EUW", 17, 33, false, 260, 18, 0.91, 19, 53),
                    player("Bastos#EUW", 16, 70, false, 156, 17, 1.20, 22, 55, party = 2),
                    player("Gigi#Ok", 15, 44, false, 98, 15, 0.73, 16, 44, party = 2),
                    player("PasDeChance#404", 12, 95, false, 311, 14, 1.02, 23, 52),
                ),
                rounds = "WWLWLLWWWLWL".map { ValorantRound(it == 'W', null) },
                today = ValorantToday(6, 2, 2, listOf(true, false, true, false)),
                mapStats = ValorantMapStats(5, 8, 40, listOf(true, true, false, true, false, true, false, true)),
            ),
        )
    }
}
