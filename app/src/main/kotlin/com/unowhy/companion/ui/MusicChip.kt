package com.unowhy.companion.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lecture en cours de l'ordinateur, en haut à droite à côté de l'heure : vignette, titre, Lecture / Pause. Visible
 * seulement quand une session a un titre ; relue toutes les [POLL_MS] tant qu'elle est à l'écran et qu'un ordinateur
 * est appairé. Toucher le titre : [onOpen] (le profil Deck, où est la lecture en grand).
 */
class MusicChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onOpen: () -> Unit = {}

    private val thumb = ImageView(context)
    private val title = TextView(context)
    private val toggle = ImageButton(context)
    private var session: MediaSession? = null
    private var thumbKey: String? = null
    private var poll: Job? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(themeColor(context, R.attr.c_surface_raised))
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.dp(4).toFloat())
        }
        thumb.clipToOutline = true
        thumb.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(thumb, LayoutParams(dp(20), dp(20)))
        title.apply {
            textSize = 13f
            typeface = themeFont(context, R.attr.c_font_regular)
            setTextColor(themeColor(context, R.attr.c_text_dim))
            maxWidth = dp(220)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { onOpen() }
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        toggle.apply {
            background = null
            setColorFilter(themeColor(context, R.attr.c_text))
            setOnClickListener { playPause() }
        }
        addView(toggle, LayoutParams(dp(32), dp(32)).apply { marginStart = dp(2) })
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
        title.text = shown.title
        title.contentDescription = context.getString(R.string.music_chip_open, shown.title)
        toggle.setImageResource(if (shown.playing) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = context.getString(if (shown.playing) R.string.music_chip_pause else R.string.music_chip_play)
        toggle.visibility = if (shown.canPlayPause) VISIBLE else GONE
        if (shown.thumb != thumbKey) {
            thumbKey = shown.thumb
            val bytes = shown.thumb?.let { runCatching { BackendClient(context).system.mediaThumb(shown.app, it) }.getOrNull() }
            thumb.setImageBitmap(bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) })
        }
    }

    private fun playPause() {
        val current = session ?: return
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            runCatching { BackendClient(context).system.mediaControl("play-pause", current.app) }
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
    }
}
