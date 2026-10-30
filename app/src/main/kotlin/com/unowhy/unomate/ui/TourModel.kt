package com.unowhy.unomate.ui

import android.content.Context
import com.unowhy.unomate.Prefs

/**
 * Visite guidée du premier lancement (après l'appairage), sans vue : six bulles le long de la barre de gauche, puis
 * plus jamais (passée ou finie). Montrée par [TourOverlay].
 */
object TourModel {
    enum class Step { HOME, SCREENS, SWIPE, TALK, NOTIFICATIONS, CUSTOMIZE }

    val STEPS = Step.entries.toList()

    /** Une fois, et seulement quand un ordinateur est appairé : avant, la bienvenue s'en charge. */
    fun shouldShow(context: Context, hasComputer: Boolean): Boolean = hasComputer && !Prefs.tourDone(context)

    /** Gauche de la bulle : à droite de la cible, à gauche si elle sortirait de l'écran (cloche du bandeau), toujours dans l'écran. */
    fun bubbleX(targetLeft: Float, targetRight: Float, bubbleWidth: Float, width: Float, gap: Float, margin: Float): Float {
        val right = targetRight + gap
        val x = if (right + bubbleWidth <= width - margin) right else targetLeft - gap - bubbleWidth
        return x.coerceIn(margin, (width - bubbleWidth - margin).coerceAtLeast(margin))
    }
}
