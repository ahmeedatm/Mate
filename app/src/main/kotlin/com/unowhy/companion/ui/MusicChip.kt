package com.unowhy.companion.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.MediaSession
import com.unowhy.companion.net.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lecture en cours de l'ordinateur, dans le header en haut à droite, au style du widget Média : pochette, titre et
 * artiste (sinon la provenance : Spotify, YouTube, nom du site), Précédent, Lecture / Pause, Suivant. Titre trop long :
 * il défile ([MARQUEE_REPEAT] passages à chaque morceau, puis s'arrête sur le début, pour ne pas redessiner sans fin).
 * Règles du petit lecteur de COMP-56 ([MiniPlayerLogic]) : ce qui joue d'abord, visible pendant une lecture et dix
 * minutes de pause. Relue toutes les [POLL_MS] (sur Mac, chaque lecture lance un programme), chaque seconde juste après
 * un appui ; pas de relecture tant que [suppressed] (cadre photo, aucun ordinateur).
 */
class MusicChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val thumb: ImageView
    private val title: TextView
    private val previous: ImageButton
    private val play: ImageButton
    private val next: ImageButton
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
        previous = findViewById(R.id.chip_previous)
        play = findViewById(R.id.chip_play)
        next = findViewById(R.id.chip_next)
        previous.setOnClickListener { control("previous") }
        play.setOnClickListener { control("play-pause") }
        next.setOnClickListener { control("next") }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        poll = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            while (true) {
                if (suppressed()) visibility = GONE else refresh()
                delay(if (System.currentTimeMillis() < fastUntilMs) FAST_MS else POLL_MS)
            }
        }
    }

    override fun onDetachedFromWindow() {
        poll?.cancel()
        super.onDetachedFromWindow()
    }

    /** Cachée tout de suite si [suppressed] (mode Modifier, cadre photo), sinon telle qu'à la dernière lecture. */
    fun update() {
        visibility = if (!suppressed() && session != null) VISIBLE else GONE
    }

    /** Relit la lecture en cours ; en erreur (pas de lecture lisible) ou en pause depuis longtemps, la pastille se cache. */
    private suspend fun refresh() {
        val picked = runCatchingCancellable { pick(BackendClient(context).system.media()) }.getOrNull()
        val now = System.currentTimeMillis()
        if (picked?.playing == true) lastPlayingMs = now
        val shown = picked?.takeIf { MiniPlayerLogic.visible(it, lastPlayingMs, now) }
        session = shown
        visibility = if (shown != null) VISIBLE else GONE
        shown ?: return
        val line = SpannableStringBuilder(shown.title).apply {
            subtitle(shown)?.let {
                val start = length
                append("  ·  ").append(it)
                setSpan(ForegroundColorSpan(themeColor(context, R.attr.c_text_dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Même morceau : on ne touche pas au texte, le défilement en cours continue (et ne repart pas à chaque relecture).
        if (line.toString() != title.text.toString()) title.text = line
        title.contentDescription = listOfNotNull(shown.title, subtitle(shown)).joinToString(", ")
        play.setImageResource(if (shown.playing) R.drawable.ic_pause else R.drawable.ic_play)
        play.visibility = if (shown.canPlayPause) VISIBLE else GONE
        previous.visibility = if (shown.canPrevious) VISIBLE else GONE
        next.visibility = if (shown.canNext) VISIBLE else GONE
        if (shown.thumb != thumbKey) {
            thumbKey = shown.thumb
            val bytes = shown.thumb?.let { runCatching { BackendClient(context).system.mediaThumb(shown.app, it) }.getOrNull() }
            thumb.setImageBitmap(bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) })
        }
    }

    private fun control(action: String) {
        val current = session ?: return
        fastUntilMs = System.currentTimeMillis() + FAST_FOR_MS
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            runCatching { BackendClient(context).system.mediaControl(action, current.app) }
            refresh()
        }
    }

    companion object {
        private const val POLL_MS = 5_000L

        /** Juste après un appui ([FAST_FOR_MS]) : relue chaque seconde, le nouveau morceau arrive vite. */
        private const val FAST_MS = 1_000L
        private const val FAST_FOR_MS = 8_000L

        /** Passages du titre trop long à chaque nouveau morceau. */
        private const val MARQUEE_REPEAT = 3

        /** Session à montrer, toujours avec un titre : celle qui joue, sinon la courante de l'ordinateur ([MiniPlayerLogic.pick]). */
        fun pick(sessions: List<MediaSession>): MediaSession? = MiniPlayerLogic.pick(sessions.filter { it.title.isNotBlank() })

        /** Provenance : l'appli ou le site, sauf vide ou identique au titre. */
        fun source(session: MediaSession): String? = session.name.trim().takeIf { it.isNotEmpty() && !it.equals(session.title.trim(), ignoreCase = true) }

        /** Après le titre : l'artiste, sinon la provenance. */
        fun subtitle(session: MediaSession): String? = session.artist.trim().takeIf { it.isNotEmpty() } ?: source(session)
    }
}
