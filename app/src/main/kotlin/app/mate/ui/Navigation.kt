package app.mate.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.mate.Mode
import app.mate.Prefs
import app.mate.R
import app.mate.focus.FocusTimer
import app.mate.net.Link
import app.mate.ui.home.HomeLayout

/** Ce que MainActivity demande à la navigation : la barre fixe ([ProfileRail]). */
interface Navigation {
    /** Commandes vocales. */
    val talk: View

    /** Ordinateur piloté et pastille de connexion ; toucher : changer d'ordinateur. */
    val status: View

    fun select(mode: Mode, changed: Boolean)

    /** Depuis l'ordi montre ses notifications : la cloche est l'élément choisi, plus « Depuis l'ordi ». */
    fun showNotifications(shown: Boolean) {}

    /** Balayage vertical : ouvre l'entrée voisine de la barre (+1 suivante, -1 précédente) ; faux s'il n'y en a pas. */
    fun step(delta: Int): Boolean = false

    /** Une entrée attend dans ce sens : l'écran suit le doigt (sinon il résiste, voir [SwipeFollow]). */
    fun canStep(delta: Int): Boolean = false

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

/** Forme de la pastille de connexion : l'état se lit sans la couleur (vert et rouge ont la même luminance). */
internal enum class LinkShape(@DrawableRes val drawable: Int) {
    /** Pleine : l'ordinateur répond. */
    FILLED(R.drawable.dot),

    /** Anneau : il répond mais refuse le jeton, ou sa session est verrouillée. */
    RING(R.drawable.dot_ring),

    /** Croix : injoignable, ou aucun ordinateur. */
    CROSS(R.drawable.dot_cross),
}

/**
 * Ce que montre la pastille d'un état : sa forme, l'état écrit à côté du nom (« en ligne »… ; null sans ordinateur :
 * le nom dit déjà « Aucun ») et la phrase lue par TalkBack.
 */
internal data class LinkMark(val shape: LinkShape, @StringRes val label: Int?, @StringRes val spoken: Int)

internal fun linkMark(state: Link.State, locked: Boolean = false): LinkMark = when (state) {
    // Il répond mais sa session est verrouillée (Prefs.computerLocked) : anneau, comme un refus.
    Link.State.ONLINE -> if (locked) LinkMark(LinkShape.RING, R.string.link_state_locked, R.string.computer_locked_spoken) else LinkMark(LinkShape.FILLED, R.string.link_state_online, R.string.online)
    Link.State.UNAUTHORIZED -> LinkMark(LinkShape.RING, R.string.link_state_unauthorized, R.string.unauthorized)
    Link.State.OFFLINE -> LinkMark(LinkShape.CROSS, R.string.link_state_offline, R.string.offline)
    Link.State.NONE -> LinkMark(LinkShape.CROSS, null, R.string.no_computer)
}

/**
 * Pastille de connexion : forme et couleur selon l'état (`offline` : couleur de l'injoignable), nom de l'ordinateur
 * actif et, dans `stateText`, l'état écrit (« verrouillé » quand sa session l'est).
 */
internal fun renderLink(activity: Activity, dot: View, name: TextView, stateText: TextView, status: View, link: Link, offline: Int = R.attr.c_ko) {
    val locked = Prefs.computerLocked(activity)
    val color = when (link.state) {
        Link.State.ONLINE -> if (locked) R.attr.c_warn else R.attr.c_ok
        Link.State.UNAUTHORIZED -> R.attr.c_warn
        else -> offline
    }
    val mark = linkMark(link.state, locked)
    dot.setBackgroundResource(mark.shape.drawable)
    dot.background.mutate().setTint(themeColor(activity, color))
    name.text = link.computer?.name ?: activity.getString(R.string.no_computer_short)
    stateText.text = mark.label?.let { activity.getString(it) }
    stateText.visibility = if (mark.label == null) View.GONE else View.VISIBLE
    val spoken = activity.getString(mark.spoken)
    status.contentDescription = link.computer?.name?.let { "$it, $spoken" } ?: spoken
}
