package app.mate.ui

import android.app.Activity
import android.content.Context
import app.mate.Prefs
import app.mate.R

/**
 * Réduire les animations. Deux niveaux, parce que le kiosque met l'échelle d'animation d'Android à zéro pour la vitesse
 * (docs/kiosque.md) et non pour l'accessibilité : on ne peut pas distinguer les deux.
 * - [transitions] (Look.reduced) : états finaux posés sans animer, si le réglage est actif ou l'échelle d'Android à zéro
 *   (comme avant ce réglage).
 * - [loops] (Look.still) : plus rien ne bouge en continu (tubes Rétro, roue et voyants des agents, vumètres, corbeille),
 *   et les ressorts qui jouaient malgré l'échelle à zéro (changement d'onglet) se posent aussi ; seulement sur le réglage,
 *   sinon le kiosque figerait tout.
 */
object Motion {
    fun transitions(setting: Boolean, systemScale: Float): Boolean = setting || systemScale == 0f

    fun loops(setting: Boolean): Boolean = setting
}

/**
 * Aides à la lecture de Réglages > Accessibilité (police lisible, bandeaux lus). La taille du texte et TalkBack sont
 * dans MainActivity et app.mate.device.Accessibility (COMP-50).
 */
object ReadingAids {
    /** Police lisible : polices du thème remplacées par Atkinson Hyperlegible, un peu espacée. Après la palette. */
    fun applyFont(activity: Activity) {
        if (Prefs.legibleFont(activity)) activity.theme.applyStyle(R.style.Fonts_Legible, true)
    }

    /** Espacement des lettres d'un texte qui fixe le sien à zéro (titres Cadran) : celui de la police lisible (res/values/fonts.xml). */
    fun letterSpacing(context: Context): Float = if (Prefs.legibleFont(context)) LEGIBLE_LETTER_SPACING else 0f

    private const val LEGIBLE_LETTER_SPACING = 0.03f

    /** Texte d'un bandeau à lire : « App · Titre » et la ligne suivante deviennent des pauses, pas un « point médian ». */
    fun spoken(banner: String): String =
        banner.replace(Regex("\\s*·\\s*"), ", ").replace(Regex("\\s*\\n+\\s*"), ". ").trim()
}
