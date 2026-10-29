package com.unowhy.companion.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lecture en cours de l'ordinateur, en haut à droite à côté de l'heure, dans le style du widget Média : vignette,
 * titre et provenance (Spotify, YouTube, nom du site), Précédent, Lecture / Pause, Suivant. Visible seulement quand une
 * session a un titre ; relue toutes les [POLL_MS] tant qu'elle est à l'écran et qu'un ordinateur est appairé.
 * Toucher le titre : [onOpen] (le profil Deck, où est la lecture en grand).
 */
class MusicChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onOpen: () -> Unit = {}

    private val thumb: ImageView
    private val title: TextView
    private val previous: ImageButton
    private val play: ImageButton
    private val next: ImageButton
    private var session: MediaSession? = null
    private var thumbKey: String? = null
    private var poll: Job? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_music_chip, this, true)
        thumb = findViewById(R.id.chip_thumb)
        thumb.clipToOutline = true
        title = findViewById(R.id.chip_title)
        previous = findViewById(R.id.chip_previous)
        play = findViewById(R.id.chip_play)
        next = findViewById(R.id.chip_next)
        title.setOnClickListener { onOpen() }
        thumb.setOnClickListener { onOpen() }
        previous.setOnClickListener { control("previous") }
        play.setOnClickListener { control("play-pause") }
        next.setOnClickListener { control("next") }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        poll = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            while (true) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    override fun onDetachedFromWindow() {
        poll?.cancel()
        super.onDetachedFromWindow()
    }

    /** Relit la lecture en cours ; sans ordinateur ou en erreur (pas de lecture lisible), la pastille se cache. */
    private suspend fun refresh() {
        val shown = if (Computers.all(context).isEmpty()) null else runCatching { pick(BackendClient(context).system.media()) }.getOrNull()
        session = shown
        visibility = if (shown != null) VISIBLE else GONE
        shown ?: return
        title.text = SpannableStringBuilder(shown.title).apply {
            source(shown)?.let {
                val start = length
                append("  ·  ").append(it)
                setSpan(ForegroundColorSpan(themeColor(context, R.attr.c_text_faint)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        title.contentDescription = context.getString(R.string.music_chip_open, shown.title)
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
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            runCatching { BackendClient(context).system.mediaControl(action, current.app) }
            refresh()
        }
    }

    companion object {
        private const val POLL_MS = 5_000L

        /** Session à montrer : la courante de l'ordinateur, sinon celle qui joue, sinon la première ; toujours avec un titre. */
        fun pick(sessions: List<MediaSession>): MediaSession? {
            val titled = sessions.filter { it.title.isNotBlank() }
            return titled.firstOrNull { it.current } ?: titled.firstOrNull { it.playing } ?: titled.firstOrNull()
        }

        /** Provenance affichée après le titre : l'appli ou le site, sauf vide ou identique au titre. */
        fun source(session: MediaSession): String? = session.name.trim().takeIf { it.isNotEmpty() && !it.equals(session.title.trim(), ignoreCase = true) }
    }
}
