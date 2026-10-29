package com.unowhy.companion.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.unowhy.companion.Mode
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.net.Link
import com.unowhy.companion.ui.home.HomeLayout

/** Ce que MainActivity demande à la navigation : la barre fixe ([ProfileRail]). */
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

    /** Profils de la barre (noms, rôles, Réunion pendant un appel) et profil affiché. */
    fun showProfiles(layout: HomeLayout, page: Int, inCall: Boolean)

    /** Sens du dernier changement dans la barre ([RailModel.direction]) : l'écran suivant arrive de ce côté. */
    val lastMove: Int

    /** Repos (personne ne touche la tablette) : la barre s'efface à moitié, sans disparaître. */
    fun setResting(resting: Boolean)

    /** Réglages de la barre changés (noms, outils affichés) : elle se redessine. */
    fun applySettings()

    /** Vue que la visite guidée éclaire pour une étape (null : bulle au centre). */
    fun tourAnchor(step: TourModel.Step): View?
}

/** Temps restant du Pomodoro tant qu'il est lancé ou entamé, sinon null. */
internal fun focusRemaining(state: FocusTimer.State): String? =
    if (state.running || state.remainingMs < state.durationMs) FocusTimer.format(state.remainingMs) else null

/** Libellé du mode Focus : le temps restant tant qu'un Pomodoro est lancé ou entamé. */
internal fun focusLabel(activity: Activity, state: FocusTimer.State): String =
    focusRemaining(state) ?: activity.getString(R.string.mode_focus)

/** Pastille de connexion : couleur selon l'état (`offline` : celle de l'injoignable), nom de l'ordinateur actif. */
internal fun renderLink(activity: Activity, dot: View, name: TextView, status: View, link: Link, offline: Int = R.attr.c_ko) {
    val color = when (link.state) {
        Link.State.ONLINE -> R.attr.c_ok
        Link.State.UNAUTHORIZED -> R.attr.c_warn
        else -> offline
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
