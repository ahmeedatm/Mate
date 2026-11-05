package app.mate.ui.home

/**
 * Ce que l'Accueil relit sur l'ordinateur à une minute donnée. Les fonctions qu'il offre (catalogue des widgets :
 * Valorant, Jira, GitLab…) se relisent pour tout Accueil ; l'agenda seulement si un widget l'affiche.
 */
object HomeRefresh {
    private const val EVERY_MINUTES = 5

    enum class Fetch { NONE, FEATURES, FEATURES_AND_CALENDAR }

    /** Toutes les 5 minutes, ou chaque minute en attendant un agenda relié sur l'ordinateur. */
    fun due(tick: Int, waitingLink: Boolean) = tick % EVERY_MINUTES == 0 || waitingLink

    fun plan(hasComputer: Boolean, due: Boolean, showsCalendar: Boolean) = when {
        !hasComputer || !due -> Fetch.NONE
        showsCalendar -> Fetch.FEATURES_AND_CALENDAR
        else -> Fetch.FEATURES
    }
}
