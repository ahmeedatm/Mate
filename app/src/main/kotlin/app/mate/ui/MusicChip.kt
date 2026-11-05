package app.mate.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.MediaSession
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lecture en cours de l'ordinateur, dans le header en haut à droite : pochette et titre seulement, estompés en pause.
 * Largeur fixe (le header ne bouge pas d'un morceau à l'autre) ; titre trop long : il défile [MARQUEE_REPEAT] fois à
 * chaque morceau, puis s'arrête sur le début, pour ne pas redessiner sans fin sur la tablette. Un toucher ouvre l'îlot ([MusicIsland]) :
 * titre et artiste en entier, avancement, boutons.
 * Règles du petit lecteur de COMP-56 ([MiniPlayerLogic]) : ce qui joue d'abord, visible pendant une lecture et dix
 * minutes de pause. Relue toutes les [POLL_MS] (sur Mac, chaque lecture lance un programme), chaque seconde tant que
 * l'îlot est ouvert et juste après un appui ; pas de relecture tant que [suppressed] (cadre photo, aucun ordinateur).
 */
class MusicChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val thumb: ImageView
    private val title: TextView

    /** Îlot créé au premier toucher seulement : rien à gonfler au démarrage ni à chaque recreate quand rien ne joue. */
    private var island: MusicIsland? = null
    private var session: MediaSession? = null
    private var thumbKey: String? = null
    private var poll: Job? = null
    private var lastPlayingMs: Long? = null
    private var fastUntilMs = 0L

    /** Rien à relire ni à montrer (cadre photo par-dessus, aucun ordinateur) : posé par MainActivity. */
    var suppressed: () -> Boolean = { Computers.all(context).isEmpty() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_music_chip, this, true)
        thumb = findViewById(R.id.chip_thumb)
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.dp(6).toFloat())
        }
        thumb.clipToOutline = true
        title = findViewById(R.id.chip_title)
        title.apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = MARQUEE_REPEAT
            // Le défilement ne part que sur une vue « choisie ».
            isSelected = true
        }
        setOnClickListener {
            val shown = session ?: return@setOnClickListener
            val opened = island ?: MusicIsland(this, ::control, ::update).also { island = it }
            opened.open(shown, thumb.drawable)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        poll = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            while (true) {
                if (suppressed()) {
                    island?.dismiss()
                    visibility = GONE
                } else {
                    refresh()
                }
                delay(if (islandOpen() || System.currentTimeMillis() < fastUntilMs) FAST_MS else POLL_MS)
            }
        }
    }

    override fun onDetachedFromWindow() {
        poll?.cancel()
        island?.release()
        super.onDetachedFromWindow()
    }

    /** Cachée tout de suite si [suppressed] (mode Modifier, cadre photo), sinon telle qu'à la dernière lecture. */
    fun update() {
        val shown = !suppressed() && session != null
        if (!shown) island?.dismiss()
        visibility = when {
            !shown -> GONE
            // Cachée sous l'îlot ouvert, qui en est sorti.
            islandOpen() -> INVISIBLE
            else -> VISIBLE
        }
    }

    /** Relit la lecture en cours ; en erreur (pas de lecture lisible) ou en pause depuis longtemps, la pastille se cache. */
    private suspend fun refresh() {
        val picked = runCatchingCancellable { pick(BackendClient(context).system.media()) }.getOrNull()
        val now = System.currentTimeMillis()
        if (picked?.playing == true) lastPlayingMs = now
        val shown = picked?.takeIf { MiniPlayerLogic.visible(it, lastPlayingMs, now) }
        session = shown
        if (shown == null) island?.dismiss()
        // Îlot ouvert : la pastille reste cachée dessous jusqu'à la fermeture.
        if (!islandOpen()) visibility = if (shown != null) VISIBLE else GONE
        shown ?: return
        // Même morceau : on ne touche pas au texte, le défilement en cours continue (et ne repart pas à chaque relecture).
        if (shown.title != title.text.toString()) title.text = shown.title
        val label = listOfNotNull(shown.title, subtitle(shown)).joinToString(", ")
        contentDescription = context.getString(if (shown.playing) R.string.media_island_open else R.string.media_island_open_paused, label)
        // En pause : pochette et titre estompés, la pastille dit son état sans bouton.
        title.setTextColor(themeColor(context, if (shown.playing) R.attr.c_text else R.attr.c_text_dim))
        thumb.alpha = if (shown.playing) 1f else PAUSED_THUMB_ALPHA
        if (shown.thumb != thumbKey) {
            thumbKey = shown.thumb
            val bytes = shown.thumb?.let { runCatching { BackendClient(context).system.mediaThumb(shown.app, it) }.getOrNull() }
            thumb.setImageBitmap(bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) })
        }
        if (islandOpen()) island?.render(shown, thumb.drawable)
    }

    private fun islandOpen() = island?.isOpen == true

    private fun control(action: String, positionMs: Long?) {
        val current = session ?: return
        fastUntilMs = System.currentTimeMillis() + FAST_FOR_MS
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            runCatching { BackendClient(context).system.mediaControl(action, current.app, positionMs) }
            refresh()
        }
    }

    companion object {
        private const val POLL_MS = 5_000L

        /** Îlot ouvert, ou juste après un appui ([FAST_FOR_MS]) : relue chaque seconde (avancement, nouveau morceau). */
        private const val FAST_MS = 1_000L
        private const val FAST_FOR_MS = 8_000L

        /** Pochette d'une lecture en pause, estompée comme le titre (passé en c_text_dim). */
        private const val PAUSED_THUMB_ALPHA = 0.5f

        /** Passages du titre trop long à chaque nouveau morceau. */
        private const val MARQUEE_REPEAT = 3

        /** Session à montrer, toujours avec un titre : celle qui joue, sinon la courante de l'ordinateur ([MiniPlayerLogic.pick]). */
        fun pick(sessions: List<MediaSession>): MediaSession? = MiniPlayerLogic.pick(sessions.filter { it.title.isNotBlank() })

        /** Provenance : l'appli ou le site, sauf vide ou identique au titre. */
        fun source(session: MediaSession): String? = session.name.trim().takeIf { it.isNotEmpty() && !it.equals(session.title.trim(), ignoreCase = true) }

        /** Sous le titre, dans l'îlot : l'artiste, sinon la provenance. */
        fun subtitle(session: MediaSession): String? = session.artist.trim().takeIf { it.isNotEmpty() } ?: source(session)
    }
}
