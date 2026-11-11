package app.mate.ui

import android.content.Context
import app.mate.Prefs
import app.mate.R

/**
 * Visite guidée du premier lancement (après l'appairage), sans vue : six bulles le long de la barre de gauche, puis
 * plus jamais (passée ou finie). Montrée par [TourOverlay].
 */
object TourModel {
    enum class Step { HOME, SCREENS, SWIPE, TALK, NOTIFICATIONS, CUSTOMIZE }

    val STEPS = Step.entries.toList()

    /** Étapes à montrer : sans Parler quand son bouton est caché dans les Réglages. */
    fun steps(talkShown: Boolean): List<Step> = if (talkShown) STEPS else STEPS - Step.TALK

    /** Titre et texte (ressources) d'une étape ; [dock] : thème à rangée de touches, dont l'appui long sur 01 remplace le « + » de la barre. */
    fun texts(step: Step, dock: Boolean): Pair<Int, Int> = when (step) {
        Step.HOME -> R.string.tour_home_title to R.string.tour_home_body
        Step.SCREENS -> R.string.tour_screens_title to if (dock) R.string.tour_screens_body_dock else R.string.tour_screens_body
        Step.SWIPE -> R.string.tour_swipe_title to R.string.tour_swipe_body
        Step.TALK -> R.string.tour_talk_title to R.string.tour_talk_body
        Step.NOTIFICATIONS -> R.string.tour_notifications_title to R.string.tour_notifications_body
        Step.CUSTOMIZE -> R.string.tour_customize_title to if (dock) R.string.tour_customize_body_dock else R.string.tour_customize_body
    }

    /** Une fois, et seulement quand un ordinateur est appairé : avant, la bienvenue s'en charge. */
    fun shouldShow(context: Context, hasComputer: Boolean): Boolean = hasComputer && !Prefs.tourDone(context)

    /** Gauche de la bulle : à droite de la cible, à gauche si elle sortirait de l'écran (cloche du bandeau), toujours dans l'écran. */
    fun bubbleX(targetLeft: Float, targetRight: Float, bubbleWidth: Float, width: Float, gap: Float, margin: Float): Float {
        val right = targetRight + gap
        val x = if (right + bubbleWidth <= width - margin) right else targetLeft - gap - bubbleWidth
        return x.coerceIn(margin, (width - bubbleWidth - margin).coerceAtLeast(margin))
    }
}
