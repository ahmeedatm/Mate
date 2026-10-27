package com.unowhy.companion.ui.home

import android.content.Context
import android.view.View

/** Ligne du jour de l'Accueil (DA Cadran) : [DayLineView] sans fond, qui avance d'un battement chaque minute. */
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

    /** Arrivée de l'écran, appelée par l'Accueil après chaque retour sur la page. */
    fun arrive() = line.arrive()
}
