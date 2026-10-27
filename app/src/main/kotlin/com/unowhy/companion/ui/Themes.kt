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
 * Thèmes prédéfinis, choisis dans Réglages. Les couleurs sont des attributs (`?attr/c_…`, res/values/palettes.xml) :
 * changer de thème recrée l'activité. Clair a sa propre base AppCompat (dialogues et champs clairs).
 */
enum class AppTheme(val label: Int, val base: Int, val palette: Int?, val swatch: Int) {
    NUIT(R.string.theme_nuit, R.style.Theme_Companion, null, 0xFF6CB4FF.toInt()),
    CLAIR(R.string.theme_clair, R.style.Theme_CompanionLight, null, 0xFF1F6FEB.toInt()),
    NEON(R.string.theme_neon, R.style.Theme_Companion, R.style.Palette_Neon, 0xFFD05CFF.toInt()),
    CHAUD(R.string.theme_chaud, R.style.Theme_Companion, R.style.Palette_Chaud, 0xFFFFA94D.toInt()),
    CADRAN(R.string.theme_cadran, R.style.Theme_CompanionLight, R.style.Palette_Cadran, 0xFFCA0538.toInt()),
    CADRAN_NUIT(R.string.theme_cadran_nuit, R.style.Theme_Companion, R.style.Palette_CadranNuit, 0xFFFF5165.toInt());

    companion object {
        fun current(context: Context): AppTheme =
            entries.firstOrNull { it.name == Prefs.theme(context) } ?: NUIT

        /** À appeler avant super.onCreate. */
        fun apply(activity: Activity) {
            val theme = current(activity)
            activity.setTheme(theme.base)
            theme.palette?.let { activity.theme.applyStyle(it, true) }
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
