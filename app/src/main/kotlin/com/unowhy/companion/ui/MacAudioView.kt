package com.unowhy.companion.ui

import android.content.Context
import android.content.res.ColorStateList
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
import com.unowhy.companion.net.AudioState
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.SystemApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Son et micro du Mac : bouton muet, curseur de volume, bouton micro. Utilisé par l'Accueil et le Deck. */
class MacAudioView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val mute: ImageButton
    private val volume: SeekBar
    private val value: TextView
    private val mic: ImageButton

    private lateinit var scope: CoroutineScope

    /** Dans le Deck : nom de la barre, pour la distinguer de celle d'une appli. */
    fun showLabel() {
        findViewById<View>(R.id.audio_label).visibility = VISIBLE
    }
    private var state: AudioState? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_mac_audio, this, true)
        mute = findViewById(R.id.audio_mute)
        volume = findViewById(R.id.audio_volume)
        value = findViewById(R.id.audio_value)
        mic = findViewById(R.id.audio_mic)

        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) value.text = context.getString(R.string.percent, progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = change { setAudio(output = bar.progress) }
        })
        mute.setOnClickListener { change { setAudio(muted = !(state?.muted ?: false)) } }
        mic.setOnClickListener { change { setAudio(micMuted = !(state?.micMuted ?: false)) } }
        render(null)
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Relit l'état : le son a pu changer depuis le Mac. */
    fun refresh() = change { audio() }

    /** Boutons de volume de la tablette. */
    fun nudge(up: Boolean) = change { setAudio(outputDelta = if (up) 6 else -6) }

    private fun change(call: suspend SystemApi.() -> AudioState) {
        scope.launch {
            render(runCatching { BackendClient(context).system.call() }.getOrNull())
        }
    }

    private fun render(audio: AudioState?) {
        state = audio
        volume.isEnabled = audio?.output != null
        volume.progress = audio?.output ?: 0
        value.text = when {
            audio == null -> "–"
            audio.muted -> context.getString(R.string.muted)
            audio.output == null -> ""
            else -> context.getString(R.string.percent, audio.output)
        }
        mute.isEnabled = audio != null
        mic.isEnabled = audio != null
        style(mute, if (audio?.muted == true) R.drawable.ic_volume_off else R.drawable.ic_volume, alert = audio?.muted == true)
        style(mic, if (audio?.micMuted == true) R.drawable.ic_mic_off else R.drawable.ic_mic, alert = audio?.micMuted == true)
        mic.contentDescription = context.getString(if (audio?.micMuted == true) R.string.mic_off else R.string.mic_on)
    }

    /** Coupé = icône rouge sur fond rouge pâle, pour le voir de loin. */
    private fun style(button: ImageButton, icon: Int, alert: Boolean) {
        button.setImageResource(icon)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(themeColor(context, if (alert) R.attr.c_ko else R.attr.c_text)))
        ViewCompat.setBackgroundTintList(button, if (alert) ColorStateList.valueOf(themeColor(context, R.attr.c_ko_soft)) else null)
    }
}
