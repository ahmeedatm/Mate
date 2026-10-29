package com.unowhy.unomate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.DeckPage
import com.unowhy.unomate.net.DeckTile
import com.unowhy.unomate.net.VoiceState
import com.unowhy.unomate.net.runCatchingCancellable
import com.unowhy.unomate.ui.AppAudioView
import com.unowhy.unomate.ui.CallSession
import com.unowhy.unomate.ui.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * État de l'appel et touches d'une appli d'appel (page Discord ou Réunion du Deck) sur un profil. Les touches sont
 * celles de la page, montrées selon l'appel (`tileShown`) et allumées quand le micro est coupé (`tileLit`). L'appel est
 * relu toutes les 4 s tant que le profil est affiché, et 700 ms après un toucher. Pas de bouton Décrocher : la carte
 * d'appel plein écran s'en charge. S : une seule touche ; M : titre, état et touches ; L : en plus les participants et
 * le son propre à l'appli.
 */
abstract class CallWidget(
    context: Context,
    host: HomeHost,
    placed: Placed,
    private val select: (DeckPage) -> Boolean,
) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(12).apply { orientation = LinearLayout.VERTICAL }

    private var page: DeckPage? = null

    /** Les pages ont été lues au moins une fois : avant, ou pendant une panne, l'absence de page n'est pas un constat. */
    private var read = false
    private var voice: VoiceState? = null
    private var active = false
    private var poll: Job? = null

    /** Touches dessinées dans la grille, avec leur allumage : la grille n'est refaite que si elles changent. */
    private var drawn: List<Pair<DeckTile, Boolean>> = emptyList()

    private val title = cardTitle(placed.type.title, placed.type.icon)
    private val state = label(14f, R.attr.c_text_dim).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    private val flag = label(14f, R.attr.c_ko).apply { isSingleLine = true }
    private val people = label(14f, R.attr.c_text_dim).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val appAudio = AppAudioView(context).apply { attach(host.scope) }

    private val grid = TileGridView(context, host.scope).apply {
        onTap = { cell, _ -> page?.tiles?.firstOrNull { it.id == cell.key.id }?.let(::run) }
    }

    /** Titre et état. En S, seulement quand il n'y a aucune touche à montrer : sinon la carte resterait vide, sans dire à quoi elle sert. */
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(state, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(flag, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap).apply { topMargin = dp(2) })
    }

    override fun applySize() {
        view.removeAllViews()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val large = placed.size == WidgetSize.L || placed.size == WidgetSize.XL
        view.addView(header, LinearLayout.LayoutParams(match, wrap))
        if (large) view.addView(people, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        view.addView(grid, LinearLayout.LayoutParams(match, 0, 1f))
        if (large) view.addView(appAudio, LinearLayout.LayoutParams(match, wrap))
        appAudio.bind(page?.app.takeIf { large })
        render()
    }

    /** Relit les pages (cache) : la page peut apparaître, changer de touches ou disparaître du Deck. */
    override suspend fun load() {
        if (readPage()) render()
    }

    /** Page à relire à chaque minute, l'appel toutes les 4 s tant que le profil est affiché. */
    override fun setActive(active: Boolean) {
        this.active = active
        appAudio.setActive(active)
        if (!active) {
            poll?.cancel()
            poll = null
            return
        }
        if (poll?.isActive == true) return
        poll = host.scope.launch {
            if (page == null) readPage()
            while (isActive) {
                readVoice()
                delay(POLL_MS)
            }
        }
    }

    /** Page de ce widget parmi celles du deck. */
    protected open fun pickPage(pages: List<DeckPage>): DeckPage? = pages.firstOrNull(select)

    /** Vrai si les pages ont été lues (même sans page pour ce widget : l'état d'appel est alors oublié). */
    private suspend fun readPage(): Boolean {
        val pages = runCatchingCancellable { host.deckPages() }.getOrNull() ?: return false
        read = true
        val next = pickPage(pages)
        if (next?.app != page?.app) voice = null
        page = next
        appAudio.bind(next?.app.takeIf { placed.size == WidgetSize.L })
        return true
    }

    /** Appel relu ; une erreur réseau garde l'état d'avant (pas de clignotement), l'appli fermée donne `null`. */
    private suspend fun readVoice() {
        val app = page?.app
        if (app != null) runCatchingCancellable { BackendClient(context).deck.voice(app) }.onSuccess { voice = it }
        render()
    }

    private fun render() {
        val shown = page?.tiles.orEmpty().filter { tileShown(it, voice) }
        // S : la première touche propre à l'état de l'appel (micro, rejoindre), sinon la première (ouvrir l'appli).
        val tiles = if (placed.size == WidgetSize.S) listOfNotNull(shown.firstOrNull { it.whenState != null } ?: shown.firstOrNull()) else shown
        val next = tiles.map { it to tileLit(it, voice) }
        if (next != drawn) {
            drawn = next
            grid.cells = next.map { (tile, lit) -> TileGridView.Cell(KeyRef.of(tile), lit, type = tile.type) }
        }
        header.visibility = if (placed.size == WidgetSize.S && tiles.isNotEmpty()) View.GONE else View.VISIBLE
        renderState()
    }

    private fun renderState() {
        val v = voice
        val inCall = v?.connected == true
        val page = page
        state.text = when {
            page == null -> if (read) context.getString(R.string.call_no_page) else ""
            inCall -> listOfNotNull(
                context.getString(R.string.deck_voice_channel, v?.channel ?: page.app.orEmpty()),
                // L montre les participants sur leur propre ligne.
                v?.people?.takeIf { it.isNotEmpty() && placed.size != WidgetSize.L }?.joinToString(", "),
            ).joinToString(" · ")
            v?.lobby == true -> context.getString(R.string.call_lobby)
            else -> context.getString(R.string.call_idle)
        }
        // La sourdine coupe aussi le micro côté Discord : on ne dit que l'essentiel.
        val alert = when {
            !inCall -> null
            v?.deafened == true -> R.string.deck_voice_deafened
            v?.muted == true -> R.string.deck_voice_mic_off
            else -> null
        }
        flag.text = alert?.let { context.getString(it) }.orEmpty()
        flag.visibility = if (alert == null) View.GONE else View.VISIBLE
        val names = v?.people?.takeIf { inCall && it.isNotEmpty() }
        people.text = names?.let { context.getString(R.string.deck_voice_with, it.joinToString(", ")) }.orEmpty()
    }

    private fun run(tile: DeckTile) {
        host.scope.launch {
            val result = runTileSpoken(context, tile.id, tile.label)
            result.onFailure {
                Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show()
                return@launch
            }
            result.getOrNull()?.windows?.let { host.overlays.windows(tile, it) }
            // « Quitter l'appel », micro… : l'appel change, on le relit sans attendre la boucle.
            delay(VOICE_AFTER_MS)
            if (active) readVoice()
        }
    }

    private companion object {
        const val POLL_MS = 4_000L
        const val VOICE_AFTER_MS = 700L
    }
}

/** La page Discord du Deck : l'appli sans la page Réunion. */
class DiscordWidget(context: Context, host: HomeHost, placed: Placed) :
    CallWidget(context, host, placed, { it.app.equals("Discord", ignoreCase = true) && !it.meeting })

/** La page Réunion du Deck (Teams) : micro, caméra, partage, quitter ; « Rejoindre maintenant » dans la salle d'attente. */
class MeetingWidget(context: Context, host: HomeHost, placed: Placed) :
    CallWidget(context, host, placed, { it.meeting })

/**
 * Appel en cours, quelle que soit l'appli (profil Réunion, Deck V2) : la page Réunion pour Teams, celle de Discord, et
 * pour une appli sans page (Meet, Zoom, Webex) la touche « Couper le micro » du deck. Hors appel : l'aperçu vide.
 */
class AnyCallWidget(context: Context, host: HomeHost, placed: Placed) : CallWidget(context, host, placed, { false }) {
    override fun pickPage(pages: List<DeckPage>): DeckPage? {
        val app = host.main?.callApp ?: return null
        return CallSession.pageFor(app, pages) ?: CallSession.fallbackPage(pages)
    }
}
