package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Valorant
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantPlayer
import app.mate.net.ValorantState
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.themeFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Teinte de la pastille d'un rang (tier Riot : 3 à 26 par divisions de trois, 27 Radiant). Le nom du rang est toujours
 * écrit à côté : la couleur ne fait que le redire. Radiant n'a pas l'accent, qui vaut le vert d'Ascendant dans Console.
 */
fun rankHue(tier: Int?): Int = when {
    rankHollow(tier) -> R.attr.c_text_faint
    tier!! >= 27 -> R.attr.c_hue_pink
    else -> when ((tier - 3) / 3) {
        0 -> R.attr.c_text_dim // Fer
        1 -> R.attr.c_hue_orange // Bronze
        2 -> R.attr.c_text // Argent
        3 -> R.attr.c_hue_yellow // Or
        4 -> R.attr.c_hue_cyan // Platine
        5 -> R.attr.c_hue_violet // Diamant
        6 -> R.attr.c_hue_green // Ascendant
        else -> R.attr.c_hue_red // Immortel
    }
}

/** Non classé : pastille en anneau creux, pour ne pas le confondre avec Fer ou Argent, gris eux aussi. */
fun rankHollow(tier: Int?): Boolean = tier == null || tier < 3

/**
 * Valorant, lu par le compagnon dans le client Riot de l'ordinateur (Windows ; le jeton Riot n'en sort pas). En partie :
 * une ligne par joueur (agent, nom, rang et RR), ton équipe puis l'équipe d'en face, côte à côte quand le widget est
 * large ; pendant la sélection, ton équipe seulement (Riot ne donne l'autre qu'au début de la partie). Hors partie : ton
 * rang. Relu toutes les 5 s pendant une file ou une partie, toutes les 15 s sinon, seulement quand le widget est affiché.
 */
class ValorantWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
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
        addView(cardTitle(R.string.widget_valorant), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    private var last: Valorant? = null
    private var error: String? = null
    private var job: Job? = null

    /** Dernier état dessiné, avec la largeur : rien n'est refait tant que rien ne change (la plupart des relevés). */
    private var drawn: Pair<Valorant?, Boolean>? = null

    private val wide get() = placed.size.w >= 4

    override fun applySize() {
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
        if (drawn == v to wide) return
        drawn = v to wide
        body.removeAllViews()
        val full = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val end = v?.end?.takeIf { it.status == ValorantEndStatus.READY }
        when {
            v == null || v.state == ValorantState.OFF -> body.addView(center(context.getString(R.string.valorant_off_hint)))
            end != null -> body.addView(look.end(end, kit, fit), full)
            v.state == ValorantState.MENU || v.state == ValorantState.QUEUE -> body.addView(look.menu(v, kit, fit), full)
            else -> body.addView(look.match(v, kit, fit), full)
        }
    }

    private val kit = ValorantKit(context, host.scope)
    private val fit get() = when {
        placed.size.w >= 4 -> ValorantFit.FULL
        placed.size.h >= 4 -> ValorantFit.STRIP
        else -> ValorantFit.COMPACT
    }

    // shortcut: variante lue dans une préférence de développement le temps du choix d'Ahmed (05/11), retirée ensuite.
    private val look: ValorantLook get() = when (context.getSharedPreferences("companion", Context.MODE_PRIVATE).getString("valorant_look", "A")) {
        "B" -> ValorantLookCards
        "C" -> ValorantLookVersus
        else -> ValorantLookTable
    }

    private fun statusLine(v: Valorant?): String {
        if (v == null || v.state == ValorantState.OFF) return context.getString(R.string.valorant_off)
        val state = context.getString(
            when (v.state) {
                ValorantState.QUEUE -> R.string.valorant_queue
                ValorantState.PREGAME -> R.string.valorant_pregame
                ValorantState.INGAME -> R.string.valorant_ingame
                else -> R.string.valorant_menu
            },
        )
        v.end?.let { end ->
            return when (end.status) {
                ValorantEndStatus.READY -> listOfNotNull(
                    context.getString(if (end.ffa) R.string.valorant_ffa else resultText(end.won)),
                    end.score?.let { context.getString(R.string.valorant_score, it.ally, it.enemy) },
                    end.queue,
                    end.map,
                ).joinToString(" · ")
                ValorantEndStatus.LOADING -> context.getString(R.string.valorant_end_loading)
                ValorantEndStatus.MISSING -> context.getString(R.string.valorant_end_missing)
            }
        }
        val score = v.score?.let { context.getString(R.string.valorant_score, it.ally, it.enemy) }
        return listOfNotNull(state, v.queue, v.map, score).joinToString(" · ")
    }

    private fun center(text: String) = label(16f, R.attr.c_text_dim).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, 0)
    }

    private fun sp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val BUSY_MS = 5_000L
        const val IDLE_MS = 15_000L
        const val ROW_DP = 34
        const val AGENT_SP = 76
        const val STALE_ALPHA = 0.4f
    }
}
