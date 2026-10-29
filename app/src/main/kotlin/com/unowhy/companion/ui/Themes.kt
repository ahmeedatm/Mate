package com.unowhy.companion.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.unowhy.companion.Prefs
import com.unowhy.companion.R

/**
 * Thèmes de la DA Cadran (docs/ui-ux/da), choisis dans Réglages : jour, nuit, Rétro (Cadran habillé années 70,
 * grain et bande qui coule) et Contraste élevé (Cadran jour en lisibilité maximale, grandes touches). Un thème
 * s'applique aussi par code : `Prefs.setTheme(context, AppTheme.CONTRASTE.name)` puis `recreate()` (ou l'ordre
 * `settings` de la page de l'ordinateur, `{"theme": "CONTRASTE"}`). Les couleurs et polices sont des attributs (`?attr/c_…`, res/values/palettes.xml) :
 * changer de thème recrée l'activité. Les thèmes clairs ont leur propre base AppCompat (dialogues et champs clairs).
 * Un ancien thème enregistré (Nuit, Clair, Néon, Chaud) retombe sur Cadran.
 */
enum class AppTheme(val label: Int, val base: Int, val palette: Int, val swatch: Int) {
    CADRAN(R.string.theme_cadran, R.style.Theme_CompanionLight, R.style.Palette_Cadran, 0xFFCA0538.toInt()),
    CADRAN_NUIT(R.string.theme_cadran_nuit, R.style.Theme_Companion, R.style.Palette_CadranNuit, 0xFFFF5165.toInt()),
    RETRO(R.string.theme_retro, R.style.Theme_CompanionLight, R.style.Palette_Retro, 0xFFFC9303.toInt()),
    CONTRASTE(R.string.theme_contraste, R.style.Theme_CompanionLight, R.style.Palette_Contraste, 0xFF140F0F.toInt());

    companion object {
        fun current(context: Context): AppTheme =
            entries.firstOrNull { it.name == Prefs.theme(context) } ?: CADRAN

        /** À appeler avant super.onCreate. Police lisible (Réglages > Accessibilité) par-dessus la palette. */
        fun apply(activity: Activity) {
            val theme = current(activity)
            activity.setTheme(theme.base)
            activity.theme.applyStyle(theme.palette, true)
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

/** Booléen du thème (absent = faux) : `themeBool(context, R.attr.c_cadran)`. */
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
