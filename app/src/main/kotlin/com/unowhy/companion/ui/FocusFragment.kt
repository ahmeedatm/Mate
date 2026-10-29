package com.unowhy.companion.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer

/** Nom court d'un son d'ambiance (pastilles Focus, carte de l'Accueil). */
@get:StringRes
val NoisePlayer.Kind.label: Int
    get() = when (this) {
        NoisePlayer.Kind.BROWN -> R.string.noise_brown
        NoisePlayer.Kind.PINK -> R.string.noise_pink
        NoisePlayer.Kind.WHITE -> R.string.noise_white
    }

/** Focus : Pomodoro + sons d'ambiance. Les deux continuent quand on change de mode. */
class FocusFragment : Fragment() {

    private lateinit var phase: TextView
    private lateinit var time: TextView
    private lateinit var ring: ProgressRing
    private lateinit var count: TextView
    private lateinit var start: ImageButton
    private lateinit var volume: SeekBar
    private val presetButtons = mutableListOf<Pair<Int, Button>>()
    private val noiseButtons = mutableMapOf<NoisePlayer.Kind, Button>()
    private lateinit var mute: ImageButton

    private val listener: (FocusTimer.State) -> Unit = { render(it) }
    private val noiseListener: () -> Unit = { renderNoise() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_focus, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        phase = view.findViewById(R.id.focus_phase)
        time = view.findViewById(R.id.focus_time)
        ring = view.findViewById(R.id.focus_ring)
        count = view.findViewById(R.id.focus_count)
        start = view.findViewById(R.id.focus_start)
        start.setOnClickListener { FocusTimer.toggle() }
        view.findViewById<ImageButton>(R.id.focus_skip).setOnClickListener { FocusTimer.skip() }
        view.findViewById<ImageButton>(R.id.focus_reset).setOnClickListener { FocusTimer.reset() }

        val presets = view.findViewById<LinearLayout>(R.id.focus_presets)
        for ((focus, pause) in listOf(25 to 5, 50 to 10, 15 to 3)) {
            val button = chip(getString(R.string.focus_preset, focus, pause)) { FocusTimer.setPreset(focus, pause) }
            presets.addView(button, chipParams())
            presetButtons += focus to button
        }

        view.findViewById<Button>(R.id.focus_dnd).apply {
            isSelected = Prefs.focusDnd(context)
            setOnClickListener {
                isSelected = !isSelected
                Prefs.setFocusDnd(context, isSelected)
                // Pris en compte tout de suite, même en pleine concentration.
                (activity as? MainActivity)?.followFocusDnd(FocusTimer.state)
            }
        }

        val noises = view.findViewById<LinearLayout>(R.id.focus_noises)
        // Toucher l'ambiance choisie l'arrête ; le haut-parleur la coupe sans la perdre.
        for (kind in listOf(NoisePlayer.Kind.BROWN, NoisePlayer.Kind.PINK, NoisePlayer.Kind.WHITE)) {
            val button = chip(getString(kind.label)) {
                if (NoisePlayer.kind == kind) NoisePlayer.stop() else NoisePlayer.play(kind)
            }
            noises.addView(button, chipParams())
            noiseButtons[kind] = button
        }
        mute = view.findViewById(R.id.focus_mute)
        mute.setOnClickListener { NoisePlayer.setMuted(!NoisePlayer.muted) }
        volume = view.findViewById(R.id.focus_volume)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) NoisePlayer.setVolume(value / 100f)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    /** Ne pas déranger changé depuis la page de l'ordinateur. */
    fun onRemoteSettings() {
        view?.findViewById<Button>(R.id.focus_dnd)?.isSelected = Prefs.focusDnd(requireContext())
    }

    override fun onStart() {
        super.onStart()
        FocusTimer.observe(listener)
        NoisePlayer.observe(noiseListener)
        renderNoise()
    }

    override fun onStop() {
        FocusTimer.unobserve(listener)
        NoisePlayer.unobserve(noiseListener)
        super.onStop()
    }

    private fun chip(label: String, onClick: () -> Unit) =
        Button(requireContext(), null, 0, R.style.Chip).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun chipParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) }

    private fun render(state: FocusTimer.State) {
        val focus = state.phase == FocusTimer.Phase.FOCUS
        val color = themeColor(requireContext(), if (focus) R.attr.c_accent else R.attr.c_ok)
        phase.setText(if (focus) R.string.focus_phase_work else R.string.focus_phase_break)
        phase.setTextColor(color)
        time.text = FocusTimer.format(state.remainingMs)
        ring.color = color
        ring.progress = (state.durationMs - state.remainingMs).toFloat() / state.durationMs.coerceAtLeast(1)
        count.text = getString(R.string.focus_count, state.completed)
        start.setImageResource(if (state.running) R.drawable.ic_pause else R.drawable.ic_play)
        start.contentDescription = getString(if (state.running) R.string.focus_pause else R.string.focus_start)
        presetButtons.forEach { (minutes, button) -> button.isSelected = minutes == FocusTimer.focusMinutes }
    }

    private fun renderNoise() {
        noiseButtons.forEach { (kind, button) -> button.isSelected = kind == NoisePlayer.kind }
        val chosen = NoisePlayer.kind != null
        mute.isEnabled = chosen
        mute.alpha = if (chosen) 1f else 0.4f
        mute.setImageResource(if (NoisePlayer.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        mute.contentDescription = getString(if (NoisePlayer.muted) R.string.noise_unmute else R.string.noise_mute)
        volume.progress = Math.round(NoisePlayer.volume * 100)
    }
}
