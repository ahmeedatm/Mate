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

    // shortcut: Handler compte en uptimeMillis (arrêté pendant la veille) alors que l'échéance est en elapsedRealtime,
    // et le minuteur meurt avec le processus ; la tablette reste branchée et allumée, revenir ici si ça arrive.
    private var minutes: Int? = null
    private var endAt = 0L
    private var onDue: () -> Unit = {}

    val state: State
        get() = State(minutes, if (minutes == null) 0 else (endAt - clock()).coerceAtLeast(0))

    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
        // Un écran revient : le décompte reprend à la seconde (sans écran, tick dort jusqu'à l'échéance).
        if (minutes != null) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
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
            handler.postDelayed(tick, nextDelay(state.remainingMs, listeners.isNotEmpty()))
            return
        }
        val due = onDue
        cancel()
        due()
    }

    /** Réveil aligné sur la seconde suivante quand un écran affiche le décompte ; sinon un seul, à l'échéance. */
    internal fun nextDelay(remainingMs: Long, watched: Boolean) = if (watched) remainingMs % 1_000 + 1 else remainingMs

    private fun notifyListeners() {
        val s = state
        listeners.toList().forEach { it(s) }
    }
}
