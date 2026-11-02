package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Valorant
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
 * écrit à côté : la couleur ne fait que le redire.
 */
fun rankHue(tier: Int?): Int = when {
    tier == null || tier < 3 -> R.attr.c_text_faint
    tier >= 27 -> R.attr.c_accent
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

/**
 * Valorant, lu par le compagnon dans le client Riot de l'ordinateur (Windows ; le jeton Riot n'en sort pas). En partie :
 * une ligne par joueur (agent, nom, rang et RR), ton équipe puis l'équipe d'en face, côte à côte quand le widget est
 * large ; pendant la sélection, ton équipe seulement (Riot ne donne l'autre qu'au début de la partie). Hors partie : ton
 * rang. Relu toutes les 5 s pendant une file ou une partie, toutes les 15 s sinon, seulement quand le widget est affiché.
 */
class ValorantWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val status = label(14f, R.attr.c_text_dim).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardTitle(R.string.widget_valorant), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        status.text = error ?: statusLine(v)
        if (drawn == v to wide) return
        drawn = v to wide
        body.removeAllViews()
        when (v?.state) {
            null, ValorantState.OFF -> body.addView(center(context.getString(R.string.valorant_off_hint)))
            ValorantState.MENU, ValorantState.QUEUE -> body.addView(myRank(v))
            ValorantState.PREGAME, ValorantState.INGAME -> teams(v)
        }
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
        val score = v.score?.let { context.getString(R.string.valorant_score, it.ally, it.enemy) }
        return listOfNotNull(state, v.queue, v.map, score).joinToString(" · ")
    }

    private fun center(text: String) = label(16f, R.attr.c_text_dim).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, 0)
    }

    /** Hors partie : pastille et nom du rang en grand, RR et niveau dessous, puis le Riot ID. */
    private fun myRank(v: Valorant): View {
        val me = v.me ?: return center(context.getString(R.string.valorant_off_hint))
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot(me.tier, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(12) })
            addView(label(32f, light = true).apply { text = me.rank; maxLines = 1 })
        }
        box.addView(line)
        val details = listOfNotNull(
            me.rr?.let { context.getString(R.string.valorant_rr, it) },
            me.level?.let { context.getString(R.string.valorant_level, it) },
        ).joinToString(" · ")
        if (details.isNotEmpty()) box.addView(label(16f, R.attr.c_text_dim).apply { text = details })
        if (me.name.isNotEmpty()) {
            box.addView(
                label(14f, R.attr.c_text_faint).apply {
                    text = me.name
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        return box
    }

    private fun teams(v: Valorant) {
        val allies = team(R.string.valorant_allies, v.allies, null)
        val enemies = team(
            R.string.valorant_enemies,
            v.enemies,
            if (v.state == ValorantState.PREGAME) R.string.valorant_enemies_later else null,
        )
        if (wide) {
            body.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(allies, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
                    addView(enemies, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                },
            )
        } else {
            body.addView(allies)
            body.addView(enemies, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    /** `empty` : phrase quand l'équipe n'est pas encore connue. */
    private fun team(title: Int, players: List<ValorantPlayer>, empty: Int?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            label(12f, R.attr.c_text_dim).apply {
                setText(title)
                isAllCaps = true
                letterSpacing = 0.08f
            },
        )
        if (players.isEmpty() && empty != null) addView(label(14f, R.attr.c_text_faint).apply { setText(empty) })
        for (p in players) addView(row(p), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)))
    }

    /** Pastille du rang, agent, nom, rang et RR à droite ; ta ligne sur le fond de l'accent. */
    private fun row(p: ValorantPlayer) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        if (p.me) {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(color(R.attr.c_accent_soft))
            }
        }
        addView(dot(p.tier, 10), LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        val agent = p.agent ?: context.getString(R.string.valorant_no_agent).takeIf { !p.locked }
        addView(
            label(14f, if (p.locked && p.agent != null) R.attr.c_text_dim else R.attr.c_text_faint).apply {
                text = agent ?: "—"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(dp(AGENT_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) },
        )
        val name = p.name ?: context.getString(R.string.valorant_hidden)
        addView(
            label(15f, if (p.name == null) R.attr.c_text_faint else R.attr.c_text).apply {
                text = name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (p.me) typeface = themeFont(context, R.attr.c_font_medium)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val rank = listOfNotNull(p.rank, p.rr?.let { context.getString(R.string.valorant_rr, it) }).joinToString(" · ")
        addView(label(14f).apply { text = rank; maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        contentDescription = listOfNotNull(name, agent, rank.ifEmpty { null }).joinToString(", ")
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dot(tier: Int?, size: Int) = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(rankHue(tier)))
            setSize(dp(size), dp(size))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private companion object {
        const val BUSY_MS = 5_000L
        const val IDLE_MS = 15_000L
        const val ROW_DP = 34
        const val AGENT_DP = 76
    }
}
