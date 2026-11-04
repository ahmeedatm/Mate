package app.mate.installers

/** Ce que la carte « Sur l'ordinateur » de Bienvenue montre, sans Android (testé dans app/src/test). */
sealed class InstallerCard {
    /** Pas de WiFi : il faut d'abord l'étape 1. */
    object NoWifi : InstallerCard()

    /** Installeur pas encore sur la tablette ; `percent` du fichier en cours, null tant que rien n'a démarré. */
    data class Preparing(val percent: Int?) : InstallerCard()

    /** Adresse à ouvrir sur l'ordinateur (code compris). */
    data class Ready(val link: String) : InstallerCard()

    companion object {
        fun of(wifi: Boolean, ready: Boolean, percent: Int?, link: String?): InstallerCard = when {
            !wifi -> NoWifi
            ready && link != null -> Ready(link)
            else -> Preparing(percent)
        }
    }
}
