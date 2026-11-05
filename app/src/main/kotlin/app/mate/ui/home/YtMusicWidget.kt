package app.mate.ui.home

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import app.mate.R
import app.mate.net.BackendClient
import app.mate.ui.YtMusicView

/**
 * YouTube Music qui joue sur l'ordinateur, piloté d'ici ; une mise en page par famille de formes. Pastille : la
 * pochette remplit la case, lecture / pause dessus ; Colonne : l'affiche, titre et boutons sur la pochette ; Ligne :
 * vignette, titre et boutons ([PlayingShapes], choisies par Ahmed, #182). Bloc et plus grand : le lecteur, et la file
 * d'attente (ou les paroles) à côté dès 3 cases de large. Relu seulement quand l'écran est affiché.
 */
class YtMusicWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val music = YtMusicView(context).apply { attach(host.scope) }
    private val player = music.asCard(20)

    /** Conteneur stable : le lecteur ou une forme de [PlayingShapes], selon la famille. */
    override val view = FrameLayout(context)
    private var link: PlayingLink? = null
    private var shape: PlayingShapes.Shape? = null
    private var shownFamily: ShapeFamily? = null
    private var active = false
    private var preview = false

    override fun applySize() {
        music.showQueue = placed.size.w >= QUEUE_MIN_W
        if (family == shownFamily) return
        shownFamily = family
        setActive(false)
        view.removeAllViews()
        link = null
        shape = null
        when (family) {
            ShapeFamily.PILL -> showShape { it.pill() }
            ShapeFamily.COLUMN -> showShape { it.column() }
            ShapeFamily.ROW -> showShape { it.row() }
            else -> view.addView(player, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (preview) showPreview(YtMusicSample)
        setActive(active)
    }

    /** Pastille, Colonne et Ligne : une forme de [PlayingShapes], relue par un [PlayingLink]. */
    private fun showShape(make: (PlayingShapes) -> PlayingShapes.Shape) {
        // La forme est tenue par ce lien : une réponse en retard après un changement de taille vise l'ancienne vue.
        var made: PlayingShapes.Shape? = null
        val api = BackendClient(context).ytmusic
        val playingLink = PlayingLink(
            host.scope,
            shown = { view.isShown },
            read = { NowTrack.ofYtMusic(api.state()) },
            cover = { track -> track.coverKey?.let { api.cover(it) } },
            send = { _, op -> api.action(op) },
            coverPx = dp(COVER_DP),
        ) { state -> made?.render?.invoke(state) }
        val built = make(PlayingShapes(context, R.string.ytmusic_empty) { op -> playingLink.action(op) })
        made = built
        link = playingLink
        shape = built
        val bleed = family != ShapeFamily.ROW
        view.addView(
            FrameLayout(context).asCard(if (bleed) 0 else 16).apply {
                clipToOutline = true
                addView(built.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        built.render(null)
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is YtMusicSample) return
        preview = true
        val art = PlayingShapes.sampleCover(context)
        shape?.render?.invoke(PlayingSamples.playing(context, art))
        music.showSample(PlayingSamples.ytMusic(context), art)
    }

    override fun setActive(active: Boolean) {
        this.active = active
        // Rien ne part sur le réseau avant l'écran (le catalogue n'appelle pas setActive).
        music.setActive(active && shape == null && !preview)
        link?.setActive(active && !preview)
    }

    private companion object {
        /** La file d'attente se met à côté du lecteur dès 3 cases de large ; plus étroit, le lecteur seul. */
        const val QUEUE_MIN_W = 3

        /** Pochette des petites formes, réduite à la taille d'une colonne. */
        const val COVER_DP = 280
    }
}
