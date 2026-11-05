package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Valorant
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMe
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantScore
import app.mate.net.ValorantState
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
 * dernières classées. Pastille, ligne et colonne : ton rang et le score seulement ([ValorantShape]). Relu toutes les 5 s
 * pendant une file ou une partie, toutes les 15 s sinon, seulement quand le widget est affiché.
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

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    /** Aperçu du catalogue : aucune image demandée à l'ordinateur. */
    private var preview = false
    private var last: Valorant? = null
    private var error: String? = null
    private var job: Job? = null

    /** Dernier état dessiné, avec la forme : rien n'est refait tant que rien ne change (la plupart des relevés). */
    private var drawn: Pair<Valorant?, WidgetSize>? = null

    override fun applySize() {
        // Pastille : ni titre ni ligne d'état, le logo et le chiffre prennent toute la case.
        val pill = family == ShapeFamily.PILL
        title.visibility = if (pill) View.GONE else View.VISIBLE
        status.visibility = if (pill) View.GONE else View.VISIBLE
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
        val line = error ?: statusLine(v)
        if (status.text.toString() != line) status.text = line
        // Ordinateur muet : la dernière partie reste, estompée, pour ne pas passer pour l'état présent.
        body.alpha = if (error != null) STALE_ALPHA else 1f
        if (drawn == v to placed.size) return
        drawn = v to placed.size
        body.removeAllViews()
        val shape = ValorantShape.of(placed.size)
        // Un kit par rendu : ses images partent vers l'ordinateur actif du moment, avec un seul client.
        val kit = ValorantKit(context, host.scope, online = !preview)
        val fill = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val end = v?.end?.takeIf { it.status == ValorantEndStatus.READY }
        body.addView(
            when {
                v == null || v.state == ValorantState.OFF -> center(context.getString(R.string.valorant_off_hint))
                shape.summary -> ValorantTable.summary(v, kit, family)
                end != null -> ValorantTable.end(end, kit, shape)
                v.state == ValorantState.MENU || v.state == ValorantState.QUEUE -> ValorantTable.menu(v, kit, shape)
                else -> ValorantTable.match(v, kit, shape)
            },
            fill,
        )
    }

    private fun statusLine(v: Valorant?): String {
        if (v == null || v.state == ValorantState.OFF) return context.getString(R.string.valorant_off)
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

        private fun player(name: String?, tier: Int, rr: Int, ally: Boolean, level: Int, peak: Int, kd: Double, hs: Int, wr: Int, party: Int? = null, me: Boolean = false) =
            ValorantPlayer(name, null, true, tier, null, rr, ally, me, null, level, peak, wr, kd, hs, party)

        /** Exemple du catalogue : une partie classée à 7 – 5 ; rangs nommés au dessin ([ValorantWidget.showPreview]). */
        val SAMPLE = ValorantSample(
            Valorant(
                ValorantState.INGAME,
                null,
                null,
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
            ),
        )
    }
}
