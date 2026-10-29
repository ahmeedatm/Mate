package com.unowhy.companion.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.unowhy.companion.R
import com.unowhy.companion.focus.Breathing
import com.unowhy.companion.focus.FocusTimer

/**
 * Respiration de Focus, à la place du Pomodoro (qui continue derrière) ; l'ambiance reste réglable en dessous : rythme,
 * durée, puis la consigne suit les ondes. Arrêtée en quittant l'écran Focus ou en revenant au Pomodoro.
 */
class BreathingPanel(private val root: View) {

    private val context = root.context
    private val waves = root.findViewById<BreathingView>(R.id.breath_waves)
    private val step = root.findViewById<TextView>(R.id.breath_step)
    private val seconds = root.findViewById<TextView>(R.id.breath_seconds)
    private val start = root.findViewById<Button>(R.id.breath_start)
    private val patternChips = mutableMapOf<Breathing.Pattern, Button>()
    private val durationChips = mutableMapOf<Int, Button>()
    private var pattern = Breathing.Pattern.COHERENCE
    private var minutes = 5
    private var done = false

    init {
        val patterns = root.findViewById<LinearLayout>(R.id.breath_patterns)
        for (p in Breathing.Pattern.entries) {
            patternChips[p] = chip(patterns, context.getString(p.label)) { pattern = p; done = false; render() }
        }
        val durations = root.findViewById<LinearLayout>(R.id.breath_durations)
        for (d in Breathing.DURATIONS) {
            durationChips[d] = chip(durations, context.getString(R.string.breath_minutes, d)) { minutes = d; done = false; render() }
        }
        start.setOnClickListener { if (waves.running) stop() else begin() }
        waves.onMoment = { m ->
            step.setText(m.step.label)
            seconds.text = context.getString(R.string.breath_seconds_left, m.secondsLeft, FocusTimer.format(waves.remainingMs()))
        }
        waves.onEnd = { finished ->
            done = finished
            render()
            onEnd?.invoke()
            onEnd = null
        }
        render()
    }

    /** Fin de l'exercice lancé par [startWith] (au bout ou arrêté), une seule fois. */
    private var onEnd: (() -> Unit)? = null

    /** Exercice proposé par l'humeur : rythme et durée choisis, lancé tout de suite. */
    fun startWith(pattern: Breathing.Pattern, minutes: Int, onEnd: () -> Unit) {
        stop()
        this.pattern = pattern
        this.minutes = minutes
        this.onEnd = onEnd
        begin()
    }

    private fun begin() {
        // Le chrono du Pomodoro n'est pas touché : on revient où on en était.
        done = false
        waves.start(pattern, minutes)
        render()
    }

    /** Arrêt avant la fin (bouton, retour, écran quitté) : pas de « Terminé ». */
    fun stop() = waves.stop()

    private fun render() {
        val running = waves.running
        patternChips.forEach { (p, chip) -> chip.isSelected = p == pattern; chip.isEnabled = !running }
        durationChips.forEach { (d, chip) -> chip.isSelected = d == minutes; chip.isEnabled = !running }
        start.setText(if (running) R.string.breath_stop else R.string.breath_start)
        if (running) return
        step.setText(if (done) R.string.breath_done else R.string.breath_ready)
        seconds.setText(pattern.hint)
    }

    private fun chip(row: LinearLayout, label: String, onClick: () -> Unit) =
        Button(context, null, 0, R.style.Chip).apply {
            text = label
            setOnClickListener { onClick() }
            row.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48)).apply {
                    marginStart = context.dp(4)
                    marginEnd = context.dp(4)
                },
            )
        }

    private val Breathing.Step.label
        get() = when (this) {
            Breathing.Step.INHALE -> R.string.breath_inhale
            Breathing.Step.EXHALE -> R.string.breath_exhale
            Breathing.Step.HOLD_IN, Breathing.Step.HOLD_OUT -> R.string.breath_hold
        }

    private val Breathing.Pattern.hint
        get() = when (this) {
            Breathing.Pattern.COHERENCE -> R.string.breath_coherence_hint
            Breathing.Pattern.BOX -> R.string.breath_box_hint
            Breathing.Pattern.RELAX -> R.string.breath_relax_hint
        }
}

/** Nom court d'un rythme (pastilles de la respiration, proposition de l'humeur). */
@get:StringRes
val Breathing.Pattern.label: Int
    get() = when (this) {
        Breathing.Pattern.COHERENCE -> R.string.breath_coherence
        Breathing.Pattern.BOX -> R.string.breath_box
        Breathing.Pattern.RELAX -> R.string.breath_relax
    }
