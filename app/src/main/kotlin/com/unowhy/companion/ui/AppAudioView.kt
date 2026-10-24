package com.unowhy.companion.ui

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
import com.unowhy.companion.R
import com.unowhy.companion.net.AppAudio
import com.unowhy.companion.net.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
                if (fromUser) value.text = context.getString(R.string.percent, progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                change { setAppAudio(it, volume = bar.progress) }
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
        name.text = app?.let { context.getString(R.string.app_audio_title, it) }.orEmpty()
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
                    render(BackendClient(context).appAudio(current))
                } catch (e: BackendClient.HttpException) {
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

    private fun change(call: suspend BackendClient.(String) -> AppAudio) {
        val current = app ?: return
        scope.launch { runCatching { BackendClient(context).call(current) }.getOrNull()?.let { render(it) } }
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
        stateText.setTextColor(ContextCompat.getColor(context, if (talking) R.color.ok else R.color.text_dim))
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(ContextCompat.getColor(context, if (talking) R.color.ok else R.color.text_faint)))

        volume.isEnabled = running
        mute.isEnabled = running
        if (!dragging) volume.progress = audio?.volume ?: 0
        value.text = when {
            !running -> ""
            audio!!.muted -> context.getString(R.string.muted)
            else -> context.getString(R.string.percent, audio.volume ?: 0)
        }
        val muted = running && audio!!.muted
        mute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        ImageViewCompat.setImageTintList(mute, ColorStateList.valueOf(ContextCompat.getColor(context, if (muted) R.color.ko else R.color.text)))
        ViewCompat.setBackgroundTintList(mute, if (muted) ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ko_soft)) else null)
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
