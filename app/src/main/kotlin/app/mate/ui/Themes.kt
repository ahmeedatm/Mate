package app.mate.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import app.mate.Prefs
import app.mate.R

/**
 * Thèmes de la DA Console douce (docs/ui-ux/da), choisis dans Réglages : Console (nuit, par défaut), Console jour,
 * Contraste élevé (Console jour en lisibilité maximale, grandes touches) et Hush (nuit noire, reflets rose et violet
 * animés derrière les écrans, tuiles en verre : ui/HushBackdrop) et Spider-Verse (son logo en fond fixe,
 * c_wallpaper, contenu rangé autour : c_spine). Un thème s'applique aussi par code :
 * `Prefs.setTheme(context, AppTheme.CONTRASTE.name)` puis `recreate()` (ou l'ordre `settings` de la page de
 * l'ordinateur, `{"theme": "CONTRASTE"}`). Les couleurs et polices sont des attributs (`?attr/c_…`,
 * res/values/palettes.xml) : changer de thème recrée l'activité. Les thèmes clairs ont leur propre base AppCompat
 * (dialogues et champs clairs) ; la palette de Console et de Console jour est celle de leur base, Contraste élevé et
 * Hush en posent une par-dessus (une palette à parent AppCompat écraserait colorAccent et colorControlActivated de la base).
 * Console est le thème par défaut : sans choix, ou avec un thème enregistré qui n'existe plus (Clair, Sombre,
 * Cadran, Rétro, Nuit…).
 */
enum class AppTheme(val label: Int, val base: Int, val palette: Int? = null) {
    CONSOLE(R.string.theme_console, R.style.Theme_Companion),
    CONSOLE_JOUR(R.string.theme_console_jour, R.style.Theme_CompanionLight),
    CONTRASTE(R.string.theme_contraste, R.style.Theme_CompanionLight, R.style.Palette_Contraste),
    HUSH(R.string.theme_hush, R.style.Theme_Companion, R.style.Palette_Hush),
    SPIDERMAN(R.string.theme_spiderman, R.style.Theme_Companion, R.style.Palette_SpiderMan),
    ;

    companion object {
        fun current(context: Context): AppTheme = entries.firstOrNull { it.name == Prefs.theme(context) } ?: CONSOLE

        /** À appeler avant super.onCreate. Police lisible (Réglages > Accessibilité) par-dessus la palette. */
        fun apply(activity: Activity) {
            val theme = current(activity)
            activity.setTheme(theme.base)
            theme.palette?.let { activity.theme.applyStyle(it, true) }
            ReadingAids.applyFont(activity)
        }
    }
}

/** Couleur du thème courant : `themeColor(context, R.attr.c_accent)`. */
fun themeColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    return value.data
}

fun themeColorList(context: Context, attr: Int): ColorStateList = ColorStateList.valueOf(themeColor(context, attr))

/** Booléen du thème (absent = faux) : `themeBool(context, R.attr.c_big_keys)`. */
fun themeBool(context: Context, attr: Int): Boolean {
    val value = TypedValue()
    return context.theme.resolveAttribute(attr, value, true) && value.data != 0
}

/** Ressource (drawable) pointée par un attribut référence : `themeRes(context, R.attr.c_card_bg)`. */
fun themeRes(context: Context, attr: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    return value.resourceId
}

/** Dimension du thème en pixels : `themePx(context, R.attr.c_card_pad)`. */
fun themePx(context: Context, attr: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    return value.getDimension(context.resources.displayMetrics).toInt()
}

/** Police du thème : nom de famille système ou ressource `@font/…`. */
fun themeFont(context: Context, attr: Int): Typeface {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) ResourcesCompat.getFont(context, value.resourceId) ?: Typeface.DEFAULT
    else Typeface.create(value.string.toString(), Typeface.NORMAL)
}
