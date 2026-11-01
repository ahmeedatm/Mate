package app.mate.ui

/**
 * Page affichée à cause d'une appli de l'ordinateur, sans vue : l'ordinateur dit quelle appli est au premier plan
 * (ordre `foreground`), chaque page a sa liste d'applis (jeux, Figma, Blender…). Il dit quand basculer sur la page de
 * l'appli et quand en revenir ; MainActivity applique et tient le délai de grâce (quitter l'appli ou changer de fenêtre
 * quelques secondes ne ferme pas la page).
 */
class GameSession {

    /** START : afficher [target] (aussi quand on passe d'une appli à une autre page) ; LEAVE : délai de grâce ; RETURN : l'appli est revenue. */
    enum class Step { NONE, START, LEAVE, RETURN }

    var active = false
        private set

    /** Page de l'appli en cours, -1 sans session. */
    var target = -1
        private set

    /** Appli quittée, délai de grâce en cours. */
    private var leaving = false

    /**
     * Une appli au premier plan : `name`, `bundleId` (Windows : nom de l'exe) et `file` (Mac : nom du .app, celui du
     * catalogue même quand le système est dans une autre langue) se comparent aux applis de chaque page de `pages`
     * (index de page → applis), sans casse.
     */
    fun onForeground(name: String, bundleId: String, file: String, pages: Map<Int, List<String>>): Step {
        val page = pageOf(name, bundleId, file, pages)
        return when {
            page != null && (!active || page != target) -> Step.START.also { active = true; leaving = false; target = page }
            page != null && leaving -> Step.RETURN.also { leaving = false }
            page == null && active && !leaving -> Step.LEAVE.also { leaving = true }
            else -> Step.NONE
        }
    }

    /** Délai de grâce écoulé : vrai si la session vient de finir (l'appli n'est pas revenue entre-temps). */
    fun endIfLeft(): Boolean {
        if (!active || !leaving) return false
        reset()
        return true
    }

    fun reset() {
        active = false
        leaving = false
        target = -1
    }

    /** Passer sur la page de l'appli ? Jamais pendant une saisie, dans un écran plein (Caméra, mode Modifier) ni en appel. */
    fun shouldSwitch(typing: Boolean, fullScreen: Boolean, inCall: Boolean): Boolean = !typing && !fullScreen && !inCall

    /** Fin de session : page où revenir (celle d'avant) si l'écran montre encore l'écran de jeu, null s'il est parti ailleurs. */
    fun returnTo(currentPage: Int?, gamePage: Int, previousPage: Int): Int? = previousPage.takeIf { currentPage == gamePage }

    private fun pageOf(name: String, bundleId: String, file: String, pages: Map<Int, List<String>>): Int? {
        val front = setOf(stem(name), stem(bundleId), stem(file)) - ""
        return pages.entries.firstOrNull { (_, apps) -> apps.any { stem(it) in front } }?.key
    }

    private fun stem(text: String) = text.trim().lowercase().removeSuffix(".exe")
}
