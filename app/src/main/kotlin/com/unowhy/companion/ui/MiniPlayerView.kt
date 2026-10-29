package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.net.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Petit lecteur en haut à droite de chaque écran (COMP-56), symétrique de la cloche : piloter la musique de
 * l'ordinateur sans quitter l'écran affiché. Visible pendant une lecture et dix minutes de pause (MiniPlayerLogic),
 * jamais par-dessus la page Média ni le cadre photo ([suppressed]). Relu toutes les 5 s seulement (sur Mac, chaque
 * lecture lance un programme), chaque seconde quelques instants après un appui. [onSpace] : place à réserver au bout
 * de la ligne de titre des écrans, 0 quand il est caché.
 */
class MiniPlayerView(
    context: Context,
    private val suppressed: () -> Boolean,
    private val onOpen: () -> Unit,
    private val onSpace: (Int) -> Unit,
) : LinearLayout(context) {

    private val thumb: ImageView
    private val title: TextView
    private val source: TextView
    private val play: ImageButton
    private val previous: ImageButton
    private val next: ImageButton
    private var session: MediaSession? = null
    private var lastPlayingMs: Long? = null
    private var thumbKey: String? = null
    private var fastUntilMs = 0L
    private var space = -1
    private var scope: CoroutineScope? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(themeRes(context, R.attr.c_card_bg))
        setPadding(dp(4), 0, dp(4), 0)
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this)
        thumb = findViewById(R.id.mini_thumb)
        thumb.clipToOutline = true
        title = findViewById(R.id.mini_title)
        source = findViewById(R.id.mini_source)
        play = findViewById(R.id.mini_play)
        previous = findViewById(R.id.mini_previous)
        next = findViewById(R.id.mini_next)
        findViewById<View>(R.id.mini_open).setOnClickListener { onOpen() }
        play.setOnClickListener {
            // Tout de suite à l'écran : l'ordinateur confirme à la relecture.
            session?.let { play.setImageResource(if (it.playing) R.drawable.ic_play else R.drawable.ic_pause) }
            control("play-pause")
        }
        previous.setOnClickListener { control("previous") }
        next.setOnClickListener { control("next") }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportSpace() }
    }

    /** Relecture tant que l'appli est au premier plan. */
    fun attach(owner: LifecycleOwner) {
        scope = owner.lifecycleScope
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!suppressed()) refresh() else render()
                    delay(if (System.currentTimeMillis() < fastUntilMs) FAST_MS else POLL_MS)
                }
            }
        }
    }

    /** Écran changé (page Média, cadre photo) : caché ou montré tout de suite, sans attendre la relecture. */
    fun update() = render()

    private suspend fun refresh() {
        try {
            val sessions = BackendClient(context).system.media()
            session = MiniPlayerLogic.pick(sessions)
            if (session?.playing == true) lastPlayingMs = System.currentTimeMillis()
        } catch (e: HttpException) {
            // 501 : l'ordinateur ne sait pas lire la lecture en cours.
            if (e.code == 501) session = null
        } catch (e: Exception) {
            // Ordinateur injoignable un instant : on garde l'affichage.
        }
        render()
    }

    private fun control(action: String) {
        val app = session?.app ?: return
        fastUntilMs = System.currentTimeMillis() + FAST_FOR_MS
        scope?.launch {
            runCatching { BackendClient(context).system.mediaControl(action, app) }
            // Le temps que l'appli change de morceau.
            delay(400)
            refresh()
        }
    }

    private fun render() {
        val s = session
        val show = !suppressed() && MiniPlayerLogic.visible(s, lastPlayingMs, System.currentTimeMillis())
        visibility = if (show) VISIBLE else GONE
        reportSpace()
        if (!show || s == null) return
        title.text = s.title.ifEmpty { s.name }
        source.text = context.getString(if (s.playing) R.string.media_playing else R.string.media_paused, s.name)
        play.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        enable(play, s.canPlayPause)
        enable(previous, s.canPrevious)
        enable(next, s.canNext)
        showThumb(s.app, s.thumb)
    }

    private fun enable(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun reportSpace() {
        val wanted = if (visibility == VISIBLE && width > 0) width + dp(SPACE_GAP_DP) else 0
        if (wanted == space) return
        space = wanted
        onSpace(wanted)
    }

    /** Vignette rechargée seulement quand le morceau change. */
    private fun showThumb(app: String, key: String?) {
        if (key == thumbKey) return
        thumbKey = key
        thumb.setImageBitmap(null)
        if (key == null) return
        scope?.launch {
            val bytes = runCatching { BackendClient(context).system.mediaThumb(app, key) }.getOrNull()
            val bitmap = bytes?.let { withContext(Dispatchers.Default) { decode(it) } }
            if (thumbKey != key) return@launch
            if (bitmap == null) thumbKey = null else thumb.setImageBitmap(bitmap)
        }
    }

    /** Réduite à la taille affichée (36 dp) : une vignette en 1280×720 pèse lourd pour la tablette. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(72)) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val FAST_MS = 1_000L
        const val FAST_FOR_MS = 8_000L

        /** Écart entre le bout de la ligne de titre et le lecteur. */
        const val SPACE_GAP_DP = 12
    }
}
