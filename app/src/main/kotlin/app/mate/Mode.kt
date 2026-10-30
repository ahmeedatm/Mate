package app.mate

import java.util.Locale

/**
 * Écrans de l'appli, un Fragment chacun (Deck V2 : le Deck est un profil de l'Accueil, plus un mode).
 * `key` : nom échangé avec l'ordinateur (modes de backend/src/tablet.ts) ; en majuscules, c'est aussi le tag du
 * Fragment et la valeur gardée dans l'état de l'appli. Depuis l'ordi garde la clé historique "docs".
 * Les tâches sont sur l'Accueil : l'ancien mode "tasks" (ordinateur, extension Claude) y mène.
 * `connect` : sans ordinateur, le mode est remplacé par « Aucun ordinateur connecté » et ce texte.
 */
enum class Mode(val key: String, val icon: Int, val label: Int, val connect: Int? = null) {
    HOME("home", R.drawable.ic_dashboard, R.string.mode_home),
    COMPUTER("docs", R.drawable.ic_folder, R.string.mode_computer, R.string.connect_computer),
    FOCUS("focus", R.drawable.ic_timer, R.string.mode_focus),
    CAMERA("camera", R.drawable.ic_videocam, R.string.mode_camera, R.string.connect_camera),
    SETTINGS("settings", R.drawable.ic_settings, R.string.mode_settings);

    val tag: String get() = key.uppercase(Locale.ROOT)

    companion object {
        /** Ancien mode Deck (canal de l'ordinateur, voix) : c'est maintenant le profil Deck de l'Accueil. */
        const val DECK_KEY = "deck"

        /** Mode d'après sa clé (casse indifférente) ; null si inconnu (« tasks », ancien « chat »). */
        fun of(key: String): Mode? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}
