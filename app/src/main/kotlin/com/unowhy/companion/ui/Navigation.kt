package com.unowhy.companion.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.unowhy.companion.Mode
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.net.Link

/** Ce que MainActivity demande à la navigation entre modes : barre latérale (thèmes d'origine) ou menu caché (Cadran). */
interface Navigation {
    /** Commandes vocales. */
    val talk: View

    /** Ordinateur piloté et pastille de connexion ; toucher : changer d'ordinateur. */
    val status: View

    fun select(mode: Mode, changed: Boolean)

    /** Temps restant du Pomodoro à la place du libellé Focus, visible depuis n'importe quel écran. */
    fun showFocus(state: FocusTimer.State)

    /** Nombre de tâches non faites. */
    fun setPendingTasks(count: Int)

    fun showLink(link: Link)
}

/** Libellé du mode Focus : le temps restant tant qu'un Pomodoro est lancé ou entamé. */
internal fun focusLabel(activity: Activity, state: FocusTimer.State): String {
    val active = state.running || state.remainingMs < state.durationMs
    return if (active) FocusTimer.format(state.remainingMs) else activity.getString(R.string.mode_focus)
}

/** Pastille de connexion : couleur selon l'état, nom de l'ordinateur actif. */
internal fun renderLink(activity: Activity, dot: View, name: TextView, status: View, link: Link) {
    val color = when (link.state) {
        Link.State.ONLINE -> R.attr.c_ok
        Link.State.UNAUTHORIZED -> R.attr.c_warn
        else -> R.attr.c_ko
    }
    dot.background.mutate().setTint(themeColor(activity, color))
    name.text = link.computer?.name ?: activity.getString(R.string.no_computer_short)
    val state = when (link.state) {
        Link.State.ONLINE -> R.string.online
        Link.State.UNAUTHORIZED -> R.string.unauthorized
        Link.State.OFFLINE -> R.string.offline
        Link.State.NONE -> R.string.no_computer
    }
    status.contentDescription = activity.getString(state)
}
