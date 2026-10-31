package app.mate.ui

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
import app.mate.R
import app.mate.net.AudioState
import app.mate.net.BackendClient
import app.mate.net.SystemApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Son et micro de l'ordinateur : bouton muet, curseur de volume, bouton micro. Utilisé par l'Accueil et le Deck.
 * Relu chaque seconde tant qu'il est à l'écran (volume changé sur l'ordinateur) ; le volume part pendant le glisser.
 */
class ComputerAudioView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val mute: ImageButton
    private val volume: SeekBar
    private val value: TextView
    private val mic: ImageButton

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var dragging = false
    private val sender by lazy { LatestSender<Int>(scope) { render(BackendClient(context).system.setAudio(output = it)) } }

    private var state: AudioState? = null
    private var compacted = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_computer_audio, this, true)
        mute = findViewById(R.id.audio_mute)
        volume = findViewById(R.id.audio_volume)
        value = findViewById(R.id.audio_value)
        mic = findViewById(R.id.audio_mic)

        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = context.getString(R.string.percent, progress)
                sender.offer(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                sender.offer(bar.progress)
            }
        })
        mute.setOnClickListener { change { setAudio(muted = !(state?.muted ?: false)) } }
        mic.setOnClickListener { change { setAudio(micMuted = !(state?.micMuted ?: false)) } }
        render(null)
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        if (isAttachedToWindow) startPolling()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (::scope.isInitialized) startPolling()
    }

    override fun onDetachedFromWindow() {
        poll?.cancel()
        poll = null
        super.onDetachedFromWindow()
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                // Écran caché (autre mode) ou doigt sur le curseur : rien à relire.
                if (!isShown || dragging) continue
                val audio = runCatching { BackendClient(context).system.audio() }.getOrNull() ?: continue
                if (!dragging) render(audio)
            }
        }
    }

    /**
     * Petit widget de l'Accueil : muet, −, volume, + (sans curseur ni micro, qui ne tiennent pas dans une case).
     * Les boutons − et + font comme ceux de la tablette.
     */
    fun compact() {
        if (compacted) return
        compacted = true
        volume.visibility = GONE
        mic.visibility = GONE
        value.gravity = Gravity.CENTER
        value.textSize = 20f
        value.setTextColor(themeColor(context, R.attr.c_text))
        fun step(icon: Int, label: Int, up: Boolean) = ImageButton(context, null, 0, R.style.IconButton).apply {
            setImageResource(icon)
            contentDescription = context.getString(label)
            setOnClickListener { nudge(up) }
        }
        addView(step(R.drawable.ic_remove, R.string.volume_down, false), indexOfChild(value))
        addView(step(R.drawable.ic_add, R.string.volume_up, true), indexOfChild(value) + 1)
        value.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
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
        if (!dragging) volume.progress = audio?.output ?: 0
        if (!dragging) value.text = when {
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

    private companion object {
        const val POLL_MS = 1_000L
    }
}
