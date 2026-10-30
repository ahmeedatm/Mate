package com.unowhy.unomate.focus

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Minuteur Pomodoro, indépendant des écrans : il continue quand on change de mode
 * et la barre de modes peut afficher le temps restant.
 */
object FocusTimer {

    enum class Phase { FOCUS, BREAK }

    data class State(val phase: Phase, val durationMs: Long, val remainingMs: Long, val running: Boolean, val completed: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(State) -> Unit>()

    var focusMinutes = 25
        private set
    var breakMinutes = 5
        private set

    private var phase = Phase.FOCUS
    private var durationMs = focusMinutes * 60_000L
    private var remainingWhenPaused = durationMs
    private var endAt = 0L // SystemClock.elapsedRealtime() de fin, si en cours
    private var completed = 0

    val state: State
        get() = State(phase, durationMs, remaining(), endAt != 0L, completed)

    private fun remaining() = if (endAt != 0L) (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else remainingWhenPaused

    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun unobserve(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun setPreset(focus: Int, pause: Int) {
        focusMinutes = focus
        breakMinutes = pause
        reset()
    }

    private var restored = false

    /** Durées choisies sur l'écran Focus (Prefs), reprises une fois par processus : une activité recréée garde le chrono. */
    fun restore(focus: Int, pause: Int) {
        if (restored) return
        restored = true
        if (focus != focusMinutes || pause != breakMinutes) setPreset(focus, pause)
    }

    fun toggle() {
        if (endAt != 0L) {
            remainingWhenPaused = remaining()
            endAt = 0L
            handler.removeCallbacks(tick)
        } else {
            endAt = SystemClock.elapsedRealtime() + remainingWhenPaused
            handler.post(tick)
        }
        notifyListeners()
    }

    fun reset() {
        handler.removeCallbacks(tick)
        endAt = 0L
        phase = Phase.FOCUS
        durationMs = focusMinutes * 60_000L
        remainingWhenPaused = durationMs
        notifyListeners()
    }

    /** Arrêt (bouton, voix, Claude) : chrono remis à zéro et ambiance coupée. */
    fun stop() {
        reset()
        NoisePlayer.stop()
    }

    /** Passe directement à la phase suivante (ex. sauter la pause). */
    fun skip() {
        val wasRunning = endAt != 0L
        handler.removeCallbacks(tick)
        switchPhase()
        if (wasRunning) {
            endAt = SystemClock.elapsedRealtime() + durationMs
            handler.post(tick)
        }
        notifyListeners()
    }

    private fun switchPhase() {
        phase = if (phase == Phase.FOCUS) Phase.BREAK else Phase.FOCUS
        durationMs = (if (phase == Phase.FOCUS) focusMinutes else breakMinutes) * 60_000L
        remainingWhenPaused = durationMs
        endAt = 0L
    }

    private val tick = object : Runnable {
        override fun run() {
            if (endAt == 0L) return
            if (remaining() <= 0) {
                if (phase == Phase.FOCUS) completed++
                beep()
                switchPhase() // la phase suivante attend un appui sur "Démarrer"
                notifyListeners()
                return
            }
            notifyListeners()
            // Réveil aligné sur la seconde suivante : une mise à jour par seconde, pas plus.
            handler.postDelayed(this, remaining() % 1_000 + 1)
        }
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1_500)
            handler.postDelayed({ tone.release() }, 2_000)
        }
    }

    private var wasRunning = false

    private fun notifyListeners() {
        val s = state
        // L'ambiance suit le chrono : en pause (ou entre deux phases), elle se tait ; elle reprend avec lui.
        if (s.running != wasRunning) {
            wasRunning = s.running
            NoisePlayer.followTimer(s.running)
        }
        listeners.toList().forEach { it(s) }
    }

    fun format(ms: Long): String {
        val total = (ms + 999) / 1_000
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
