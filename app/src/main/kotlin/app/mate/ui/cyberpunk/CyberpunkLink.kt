package app.mate.ui.cyberpunk

/**
 * Ce que la page montre quand un sondage de l'état rate (réseau) ou ne dit plus « en partie » (menu passager) : le dernier
 * état en partie reste affiché tant que les sondages ratés de suite sont moins de [AWAY_AFTER] ; un bandeau discret
 * s'ajoute à partir de [BANNER_AFTER]. Au-delà, la page n'est vraiment plus en partie.
 */
object CyberpunkLink {
    enum class Mode(val shown: Boolean) {
        /** Rail, HUD, tiroirs et carte tactile. */
        LIVE(true),

        /** Comme LIVE, avec le bandeau « connexion perdue ». */
        DEGRADED(true),

        /** Message d'état, carte non tactile. */
        AWAY(false),
    }

    const val BANNER_AFTER = 2
    const val AWAY_AFTER = 3

    /** `hasLive` : un état en partie a déjà été lu ; `misses` : sondages de suite sans état en partie. */
    fun decide(hasLive: Boolean, misses: Int): Mode = when {
        !hasLive || misses >= AWAY_AFTER -> Mode.AWAY
        misses >= BANNER_AFTER -> Mode.DEGRADED
        else -> Mode.LIVE
    }
}
