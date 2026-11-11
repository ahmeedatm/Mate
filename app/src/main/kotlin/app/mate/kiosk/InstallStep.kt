package app.mate.kiosk

import android.content.pm.PackageInstaller

/**
 * Suite à donner à une réponse d'Android pendant une installation ou une désinstallation ([Updater]). Hors kiosque,
 * il répond d'abord « confirmation demandée » avec son écran. Android 10 bloque en silence un écran ouvert pendant que
 * Mate n'est pas affiché (ordre venu de l'ordinateur, UnoMate devant) : on s'arrête plutôt que d'attendre pour rien.
 */
enum class InstallStep {
    DONE,
    SHOW_CONFIRMATION,
    NOT_VISIBLE,
    ;

    companion object {
        fun of(status: Int, hasConfirmation: Boolean, foreground: Boolean): InstallStep = when {
            status != PackageInstaller.STATUS_PENDING_USER_ACTION || !hasConfirmation -> DONE
            foreground -> SHOW_CONFIRMATION
            else -> NOT_VISIBLE
        }
    }
}
