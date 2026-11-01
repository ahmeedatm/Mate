package app.mate.focus

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Minuteur de pause musique : à l'échéance, `onDue` met en pause ce qui joue sur l'ordinateur. Indépendant des écrans
 * (comme [FocusTimer]) ; un seul à la fois, un nouveau choix remplace le précédent.
 */
object SleepTimer {

    val CHOICES = listOf(15, 30, 60)

    /** `minutes` : durée choisie, `null` au repos. */
    data class State(val minutes: Int?, val remainingMs: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(State) -> Unit>()

    internal var clock: () -> Long = SystemClock::elapsedRealtime

    private var minutes: Int? = null
    private var endAt = 0L
    private var onDue: () -> Unit = {}

    val state: State
        get() = State(minutes, if (minutes == null) 0 else (endAt - clock()).coerceAtLeast(0))

    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun unobserve(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun start(chosen: Int, due: () -> Unit) {
        minutes = chosen
        endAt = clock() + chosen * 60_000L
        onDue = due
        handler.removeCallbacks(tick)
        handler.post(tick)
        notifyListeners()
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        minutes = null
        notifyListeners()
    }

    private val tick = Runnable { tick() }

    internal fun tick() {
        if (minutes == null) return
        if (state.remainingMs > 0) {
            notifyListeners()
            // Réveil aligné sur la seconde suivante : une mise à jour par seconde, pas plus.
            handler.postDelayed(tick, state.remainingMs % 1_000 + 1)
            return
        }
        val due = onDue
        cancel()
        due()
    }

    private fun notifyListeners() {
        val s = state
        listeners.toList().forEach { it(s) }
    }
}
