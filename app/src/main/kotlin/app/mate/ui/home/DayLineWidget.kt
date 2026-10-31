package app.mate.ui.home

import android.content.Context
import android.view.View
import app.mate.AnimFamily
import app.mate.ui.Look

/** Ligne du jour de l'Accueil (DA Console douce) : [DayLineView] sans fond, qui avance d'un battement chaque minute. */
class DayLineWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val line = DayLineView(context)
    override val view: View = line
    private var active = false

    override fun onCalendar() = line.show(host.events, System.currentTimeMillis(), animate = false)

    /** Le battement : le point avance sur son ressort, sauf page cachée (rien ne se voit, rien ne bouge). */
    override fun onMinute() = line.show(host.events, System.currentTimeMillis(), animate = active)

    override fun setActive(active: Boolean) {
        this.active = active
        if (active) line.show(host.events, System.currentTimeMillis(), animate = false)
    }

    /** Pas de voile : la ligne atténue elle-même ses rendez-vous, l'heure et le point « maintenant » restent justes. */
    override fun setUnavailable(on: Boolean) {
        line.stale = on
    }

    /** Arrivée de l'écran, appelée par l'Accueil après chaque retour sur la page. */
    /** Arrivée de l'Accueil : la ligne se dessine, sauf si l'arrivée des widgets est coupée (Réglages > Animations). */
    fun arrive() {
        if (Look.on(line.context, AnimFamily.HOME)) line.arrive()
    }
}
