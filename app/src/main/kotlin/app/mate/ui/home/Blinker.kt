package app.mate.ui.home

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Clignotement de l'afficheur de quai : toutes les 500 ms, l'alpha des seules étiquettes de [targets] bascule. Une
 * minuterie qui ne tourne que widget actif, jamais avec « réduire les animations » ([reduceMotion]).
 */
class Blinker(private val targets: () -> List<View>, private val reduceMotion: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lit = true
    private val tick = object : Runnable {
        override fun run() {
            lit = !lit
            applyAlpha()
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    /** À rappeler après chaque rendu et chaque [HomeWidget.setActive] : démarre ou arrête selon [shouldRun]. */
    fun sync(active: Boolean) {
        if (shouldRun(active, reduceMotion(), targets().size)) {
            if (!running) {
                running = true
                handler.postDelayed(tick, PERIOD_MS)
            }
            applyAlpha()
        } else {
            handler.removeCallbacks(tick)
            running = false
            lit = true
            applyAlpha()
        }
    }

    private fun applyAlpha() = targets().forEach { it.alpha = if (lit) 1f else DIM }

    companion object {
        private const val PERIOD_MS = 500L
        private const val DIM = 0.15f

        fun shouldRun(active: Boolean, reduceMotion: Boolean, blinking: Int) = active && !reduceMotion && blinking > 0
    }
}
