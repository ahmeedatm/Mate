package app.mate.ui

/**
 * Jeu en cours sur l'ordinateur (écran de jeu), sans vue : l'ordinateur dit quelle appli est au premier plan (ordre
 * `foreground`), la liste d'applis de jeu est celle de l'utilisateur. Il dit quand basculer sur l'écran de jeu et quand
 * en revenir ; MainActivity applique et tient le délai de grâce (quitter l'appli ou changer de fenêtre quelques secondes
 * ne finit pas le jeu).
 */
class GameSession {

    enum class Step { NONE, START, LEAVE, RETURN }

    var active = false
        private set

    /** Appli de jeu quittée, délai de grâce en cours. */
    private var leaving = false

    /**
     * Une appli au premier plan : `name`, `bundleId` (Windows : nom de l'exe) et `file` (Mac : nom du .app, celui du
     * catalogue même quand le système est dans une autre langue) se comparent à la liste, sans casse.
     */
    fun onForeground(name: String, bundleId: String, file: String, games: List<String>): Step {
        val isGame = isGame(name, bundleId, file, games)
        return when {
            isGame && !active -> Step.START.also { active = true; leaving = false }
            isGame && leaving -> Step.RETURN.also { leaving = false }
            !isGame && active && !leaving -> Step.LEAVE.also { leaving = true }
            else -> Step.NONE
        }
    }

    /** Délai de grâce écoulé : vrai si le jeu vient de finir (l'appli de jeu n'est pas revenue entre-temps). */
    fun endIfLeft(): Boolean {
        if (!active || !leaving) return false
        reset()
        return true
    }

    fun reset() {
        active = false
        leaving = false
    }

    /** Passer sur l'écran de jeu ? Jamais pendant une saisie, dans un écran plein (Caméra, mode Modifier) ni en appel. */
    fun shouldSwitch(typing: Boolean, fullScreen: Boolean, inCall: Boolean): Boolean = !typing && !fullScreen && !inCall

    /** Fin de jeu : page où revenir (celle d'avant) si l'écran montre encore l'écran de jeu, null s'il est parti ailleurs. */
    fun returnTo(currentPage: Int?, gamePage: Int, previousPage: Int): Int? = previousPage.takeIf { currentPage == gamePage }

    private fun isGame(name: String, bundleId: String, file: String, games: List<String>): Boolean {
        val front = setOf(stem(name), stem(bundleId), stem(file)) - ""
        return games.any { stem(it) in front }
    }

    private fun stem(text: String) = text.trim().lowercase().removeSuffix(".exe")
}
