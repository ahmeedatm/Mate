package app.mate.audio

/** Où vont les boutons de volume de la tablette : à l'écran affiché, au son de l'ordinateur, ou au volume de la tablette. */
object VolumeKeys {
    /** Points de volume par appui, même pas que les boutons + et − de la carte Son de l'ordinateur. */
    const val STEP = 6

    enum class Route { SCREEN, COMPUTER, TABLET }

    fun route(onComputer: Boolean, hasHandler: Boolean, hasComputer: Boolean): Route = when {
        hasHandler -> Route.SCREEN
        onComputer && hasComputer -> Route.COMPUTER
        else -> Route.TABLET
    }

    fun delta(up: Boolean): Int = if (up) STEP else -STEP
}
