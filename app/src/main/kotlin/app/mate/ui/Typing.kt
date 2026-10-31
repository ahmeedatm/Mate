package app.mate.ui

/**
 * Saisie en cours : une boîte de saisie ouverte ([showForTyping], dans sa propre fenêtre que `currentFocus` de
 * l'activité ne voit pas) ou un champ de l'écran qui a le focus. Un appel qui commence ne change pas d'écran pendant ce temps.
 */
object Typing {
    private var dialogs = 0

    fun opened() {
        dialogs++
    }

    fun closed() {
        dialogs = (dialogs - 1).coerceAtLeast(0)
    }

    fun active(focusIsEditText: Boolean): Boolean = focusIsEditText || dialogs > 0

    /** Tests seulement. */
    internal fun reset() {
        dialogs = 0
    }
}
