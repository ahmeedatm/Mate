package com.unowhy.companion.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.unowhy.companion.Prefs
import com.unowhy.companion.R

/** Réglages > Accessibilité : taille du texte, multipliée à celle choisie dans Android. */
enum class TextSize(val label: Int, val factor: Float) {
    NORMAL(R.string.a11y_text_normal, 1f),
    LARGE(R.string.a11y_text_large, 1.15f),
    HUGE(R.string.a11y_text_huge, 1.3f),
    ;

    companion object {
        /** Nom enregistré inconnu ou absent : normale. */
        fun of(name: String?): TextSize = entries.firstOrNull { it.name == name } ?: NORMAL

        fun current(context: Context): TextSize = of(Prefs.textSize(context))

        /** Échelle des polices de l'appli : celle d'Android multipliée par la taille choisie. */
        fun fontScale(system: Float, size: TextSize): Float = system * size.factor
    }
}

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

/** Application des réglages d'accessibilité à l'activité (taille du texte, police lisible). */
object Accessibility {
    /** À appeler dans attachBaseContext : contexte dont l'échelle des polices est multipliée (rien si la taille est normale). */
    fun wrap(base: Context): Context {
        val size = TextSize.current(base)
        if (size == TextSize.NORMAL) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = TextSize.fontScale(config.fontScale, size)
        return base.createConfigurationContext(config)
    }

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
