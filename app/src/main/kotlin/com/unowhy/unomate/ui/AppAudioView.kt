package com.unowhy.unomate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.net.AppAudio
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.HttpException
import com.unowhy.unomate.net.SystemApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Nom affiché d'une appli dans « Son de … » : sans l'éditeur (« Microsoft Teams » → « Teams »), qui ne tient pas. */
fun shortAppName(app: String): String = app.removePrefix("Microsoft ").ifBlank { app }

/**
 * Son d'une appli de l'ordinateur (page Discord du deck) : témoin « ça parle », sourdine et volume propres à l'appli.
 * Relu en boucle tant que la page est affichée ; masqué si l'ordinateur ne sait pas (Mac : pas de volume par appli).
 */
class AppAudioView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val dot: View
    private val name: TextView
    private val stateText: TextView
    private val mute: ImageButton
    private val volume: SeekBar
    private val value: TextView

    private lateinit var scope: CoroutineScope
    private var app: String? = null
    private var active = false
    private var poll: Job? = null
    private var state: AppAudio? = null
    private var dragging = false
    private val sender by lazy {
        LatestSender<Pair<String, Int>>(scope) { (app, level) -> render(BackendClient(context).system.setAppAudio(app, volume = level)) }
    }
    /** Dernier son entendu : le témoin reste allumé un instant entre deux mots. */
    private var loudAt = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_app_audio, this, true)
        dot = findViewById(R.id.app_audio_dot)
        name = findViewById(R.id.app_audio_name)
        stateText = findViewById(R.id.app_audio_state)
        mute = findViewById(R.id.app_audio_mute)
        volume = findViewById(R.id.app_audio_volume)
        value = findViewById(R.id.app_audio_value)

        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = context.getString(R.string.percent, progress)
                app?.let { sender.offer(it to progress) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                app?.let { sender.offer(it to bar.progress) }
            }
        })
        mute.setOnClickListener { change { setAppAudio(it, muted = !(state?.muted ?: false)) } }
        visibility = GONE
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Appli de la page affichée (null : page sans appli, vue masquée). */
    fun bind(app: String?) {
        if (app == this.app) return
        this.app = app
        state = null
        loudAt = 0
        name.text = app?.let { context.getString(R.string.app_audio_title, shortAppName(it)) }.orEmpty()
        visibility = if (app != null) VISIBLE else GONE
        render(null)
        restart()
    }

    /** Deck à l'écran ou non : on ne relit le son que s'il est visible. */
    fun setActive(active: Boolean) {
        this.active = active
        restart()
    }

    private fun restart() {
        poll?.cancel()
        val current = app ?: return
        if (!active) return
        poll = scope.launch {
            while (isActive && app == current) {
                try {
                    render(BackendClient(context).system.appAudio(current))
                } catch (e: HttpException) {
                    // 501 : l'ordinateur ne pilote pas le volume par appli (Mac).
                    if (e.code == 501) { visibility = GONE; return@launch }
                    render(null)
                } catch (e: Exception) {
                    render(null)
                }
                delay(if (state?.running == true) POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    private fun change(call: suspend SystemApi.(String) -> AppAudio) {
        val current = app ?: return
        scope.launch { runCatching { BackendClient(context).system.call(current) }.getOrNull()?.let { render(it) } }
    }

    private fun render(audio: AppAudio?) {
        state = audio
        val now = SystemClock.uptimeMillis()
        if (audio != null && audio.level >= TALK_LEVEL) loudAt = now
        val running = audio?.running == true
        val talking = running && now - loudAt < TALK_HOLD_MS

        stateText.setText(
            when {
                audio == null -> R.string.app_audio_unknown
                !running -> R.string.app_audio_closed
                talking -> R.string.app_audio_talking
                else -> R.string.app_audio_silent
            },
        )
        // Vert « ça parle » : celui des LED de la console (onglet Son), vif dans chaque thème, Rétro compris.
        stateText.setTextColor(themeColor(context, if (talking) R.attr.c_ok else R.attr.c_text_dim))
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(themeColor(context, if (talking) R.attr.c_mx_led_low else R.attr.c_text_faint)))

        volume.isEnabled = running
        mute.isEnabled = running
        if (!dragging) volume.progress = audio?.volume ?: 0
        if (!dragging) value.text = when {
            !running -> ""
            audio!!.muted -> context.getString(R.string.muted)
            else -> context.getString(R.string.percent, audio.volume ?: 0)
        }
        val muted = running && audio!!.muted
        mute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        ImageViewCompat.setImageTintList(mute, ColorStateList.valueOf(themeColor(context, if (muted) R.attr.c_ko else R.attr.c_text)))
        ViewCompat.setBackgroundTintList(mute, if (muted) ColorStateList.valueOf(themeColor(context, R.attr.c_ko_soft)) else null)
    }

    private companion object {
        const val POLL_MS = 300L
        /** Appli fermée ou ordinateur injoignable : on vérifie moins souvent. */
        const val IDLE_POLL_MS = 2_000L
        /** Niveau (0-100) au-dessus duquel on considère que quelqu'un parle. */
        const val TALK_LEVEL = 3
        const val TALK_HOLD_MS = 700L
    }
}
