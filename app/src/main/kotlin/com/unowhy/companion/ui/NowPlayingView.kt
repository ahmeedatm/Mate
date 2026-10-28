package com.unowhy.companion.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.net.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lecture en cours sur l'ordinateur (page Média du deck) : vignette, titre, chaîne ou artiste, avancement,
 * précédent / lecture-pause / suivant. Relue chaque seconde tant que la page est affichée ; masquée si
 * l'ordinateur ne sait pas la lire (Mac).
 */
class NowPlayingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val thumb: ImageView
    private val sessionsRow: LinearLayout
    private val source: TextView
    private val title: TextView
    private val artist: TextView
    private val timeline: LinearLayout
    private val progress: SeekBar
    private val time: TextView
    private val previous: ImageButton
    private val play: ImageButton
    private val next: ImageButton

    private lateinit var scope: CoroutineScope
    private var enabledPage = false
    private var active = false
    private var poll: Job? = null
    private var sessions: List<MediaSession> = emptyList()
    /** Appli choisie par une pastille ; sinon celle que Windows met en avant. */
    private var chosenApp: String? = null
    private var thumbKey: String? = null
    private var seeking = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_now_playing, this, true)
        thumb = findViewById(R.id.media_thumb)
        thumb.clipToOutline = true
        sessionsRow = findViewById(R.id.media_sessions)
        source = findViewById(R.id.media_source)
        title = findViewById(R.id.media_title)
        artist = findViewById(R.id.media_artist)
        timeline = findViewById(R.id.media_timeline)
        progress = findViewById(R.id.media_progress)
        time = findViewById(R.id.media_time)
        previous = findViewById(R.id.media_previous)
        play = findViewById(R.id.media_play)
        next = findViewById(R.id.media_next)

        previous.setOnClickListener { control("previous") }
        next.setOnClickListener { control("next") }
        play.setOnClickListener {
            // Icône changée tout de suite : la relecture suit un instant après.
            selected()?.let { play.setImageResource(if (it.playing) R.drawable.ic_play else R.drawable.ic_pause) }
            control("play-pause")
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) time.text = context.getString(R.string.media_time, clock(value * 1000L), clock(bar.max * 1000L))
            }
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                control("seek", bar.progress * 1000L)
            }
        })
        visibility = GONE
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Page affichée : page Média ou non. */
    fun bind(media: Boolean) {
        if (media == enabledPage) return
        enabledPage = media
        visibility = if (media) VISIBLE else GONE
        restart()
    }

    /** Boutons sous la vignette et le titre (grand widget de l'Accueil) au lieu d'à droite : le titre garde la largeur. */
    fun stacked(on: Boolean) {
        orientation = if (on) VERTICAL else HORIZONTAL
        val main = findViewById<LinearLayout>(R.id.media_main)
        main.layoutParams = if (on) LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT) else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        val controls = findViewById<LinearLayout>(R.id.media_controls)
        controls.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (on) topMargin = context.dp(12)
        }
        (previous.layoutParams as MarginLayoutParams).marginStart = context.dp(if (on) 0 else 16)
        gravity = if (on) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
    }

    /** Deck à l'écran ou non : on ne relit que s'il est visible. */
    fun setActive(active: Boolean) {
        this.active = active
        restart()
    }

    private fun restart() {
        poll?.cancel()
        if (!enabledPage || !active) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun refresh() {
        try {
            sessions = BackendClient(context).system.media()
            render()
        } catch (e: HttpException) {
            // 501 : l'ordinateur ne sait pas lire la lecture en cours (Mac).
            if (e.code == 501) { visibility = GONE; poll?.cancel() }
        } catch (e: Exception) {
            // Ordinateur injoignable un instant : on garde l'affichage.
        }
    }

    private fun selected(): MediaSession? =
        // Ce qui joue avant ce que Windows met en avant : sa session « en cours » est parfois une appli muette.
        sessions.firstOrNull { it.app == chosenApp } ?: sessions.firstOrNull { it.playing } ?: sessions.firstOrNull { it.current } ?: sessions.firstOrNull()

    private fun control(action: String, positionMs: Long? = null) {
        val app = selected()?.app ?: return
        scope.launch {
            runCatching { BackendClient(context).system.mediaControl(action, app, positionMs) }
            // Le temps que l'appli change de morceau.
            delay(400)
            refresh()
        }
    }

    private fun render() {
        val s = selected()
        renderSessions(s)
        if (s == null) {
            source.text = ""
            title.setText(R.string.media_empty)
            artist.setText(R.string.media_empty_hint)
            timeline.visibility = GONE
            listOf(previous, play, next).forEach { it.isEnabled = false; it.alpha = 0.4f }
            play.setImageResource(R.drawable.ic_play)
            showThumb(null, null)
            return
        }
        source.text = context.getString(if (s.playing) R.string.media_playing else R.string.media_paused, s.name)
        title.text = s.title.ifEmpty { s.name }
        artist.text = s.artist
        play.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        enable(previous, s.canPrevious)
        enable(play, s.canPlayPause)
        enable(next, s.canNext)

        val duration = s.durationMs
        if (duration != null && duration > 0) {
            timeline.visibility = VISIBLE
            progress.isEnabled = s.canSeek
            if (!seeking) {
                progress.max = (duration / 1000).toInt()
                progress.progress = ((s.positionMs ?: 0) / 1000).toInt()
                time.text = context.getString(R.string.media_time, clock(s.positionMs ?: 0), clock(duration))
            }
        } else {
            // Direct (Twitch) : pas de durée.
            timeline.visibility = GONE
        }
        showThumb(s.app, s.thumb)
    }

    private fun renderSessions(selected: MediaSession?) {
        if (sessions.size < 2) {
            sessionsRow.visibility = GONE
            return
        }
        sessionsRow.visibility = VISIBLE
        sessionsRow.removeAllViews()
        for (session in sessions) {
            val chip = Button(context, null, 0, R.style.Chip).apply {
                text = session.name
                isSelected = session.app == selected?.app
                setOnClickListener {
                    chosenApp = session.app
                    render()
                }
            }
            sessionsRow.addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
        }
    }

    private fun enable(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    /** Vignette rechargée seulement quand le morceau ou la vidéo change. */
    private fun showThumb(app: String?, key: String?) {
        if (key == thumbKey) return
        thumbKey = key
        thumb.setImageBitmap(null)
        if (app == null || key == null) return
        scope.launch {
            val bytes = runCatching { BackendClient(context).system.mediaThumb(app, key) }.getOrNull()
            val bitmap = bytes?.let { withContext(Dispatchers.Default) { decode(it) } }
            if (thumbKey != key) return@launch
            // Échec (ordinateur occupé, helper relancé) : réessayé à l'actualisation suivante.
            if (bitmap == null) thumbKey = null else thumb.setImageBitmap(bitmap)
        }
    }

    /** Réduite à la taille affichée : une vignette en 1280×720 pèse lourd pour la tablette. */
    private fun decode(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(192)) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}
