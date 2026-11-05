package app.mate.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import app.mate.Prefs
import app.mate.R

/** Groupes du choix des Réglages, dans cet ordre (celui des entrées d'[AppTheme]). */
enum class ThemeGroup(val label: Int) {
    CHALEUR(R.string.theme_group_chaleur),
    CONSOLE(R.string.theme_group_console),
    SOBRE(R.string.theme_group_sober),
    RETRO(R.string.theme_group_retro),
    AMBIANCES(R.string.theme_group_ambiances),
    ACCESSIBILITE(R.string.theme_group_a11y),
}

/**
 * Thèmes de Mate (docs/ui-ux/da) et ceux d'UnoMate, choisis dans Réglages en six groupes ([ThemeGroup]) : Chaleur (par
 * défaut : crème, terre cuite et serif de livre, d'après claude.ai) ; Console (nuit, et Console jour) ; Sobres (Clair et Sombre : la DA Cadran en blanc et noir francs) ; Rétro
 * (Cadran, Cadran nuit et Rétro : grain par-dessus l'écran et tube qui coule à droite, c_flow, c_grain_*) ; Ambiances (Hush :
 * nuit noire, reflets rose et violet animés derrière les écrans, tuiles en verre, ui/HushBackdrop ; Spider-Verse : son logo
 * en fond fixe, c_wallpaper, contenu rangé autour, c_spine ; Registre, Studio, Maison, Tableau et Terminal : chacun sa police,
 * ses couleurs et son motif, c_pattern) ; Accessibilité (Contraste élevé : Console jour en lisibilité maximale, grandes
 * touches). Un thème s'applique aussi par code : `Prefs.setTheme(context, AppTheme.CONTRASTE.name)` puis `recreate()` (ou
 * l'ordre `settings` de la page de l'ordinateur, `{"theme": "CONTRASTE"}`). Les couleurs et polices sont des attributs
 * (`?attr/c_…`, res/values/palettes.xml) : changer de thème recrée l'activité. Les thèmes clairs ont leur propre base AppCompat
 * (dialogues et champs clairs) ; la palette de Console et de Console jour est celle de leur base, les autres en posent une
 * par-dessus (une palette à parent AppCompat écraserait colorAccent et colorControlActivated de la base).
 * Chaleur est le thème par défaut : sans choix, ou avec un thème enregistré qui n'existe plus (Nuit, Néon, Chaud…).
 */
enum class AppTheme(val label: Int, val group: ThemeGroup, val base: Int, val palette: Int? = null) {
    CHALEUR(R.string.theme_chaleur, ThemeGroup.CHALEUR, R.style.Theme_CompanionLight, R.style.Palette_Chaleur),
    CONSOLE(R.string.theme_console, ThemeGroup.CONSOLE, R.style.Theme_Companion),
    CONSOLE_JOUR(R.string.theme_console_jour, ThemeGroup.CONSOLE, R.style.Theme_CompanionLight),
    CLAIR(R.string.theme_clair, ThemeGroup.SOBRE, R.style.Theme_CompanionLight, R.style.Palette_CadranClair),
    SOMBRE(R.string.theme_sombre, ThemeGroup.SOBRE, R.style.Theme_Companion, R.style.Palette_CadranSombre),
    CADRAN(R.string.theme_cadran, ThemeGroup.RETRO, R.style.Theme_CompanionLight, R.style.Palette_Cadran),
    CADRAN_NUIT(R.string.theme_cadran_nuit, ThemeGroup.RETRO, R.style.Theme_Companion, R.style.Palette_CadranNuit),
    RETRO(R.string.theme_retro, ThemeGroup.RETRO, R.style.Theme_CompanionLight, R.style.Palette_Retro),
    HUSH(R.string.theme_hush, ThemeGroup.AMBIANCES, R.style.Theme_Companion, R.style.Palette_Hush),
    SPIDERMAN(R.string.theme_spiderman, ThemeGroup.AMBIANCES, R.style.Theme_Companion, R.style.Palette_SpiderMan),
    REGISTRE(R.string.theme_registre, ThemeGroup.AMBIANCES, R.style.Theme_CompanionLight, R.style.Palette_Registre),
    STUDIO(R.string.theme_studio, ThemeGroup.AMBIANCES, R.style.Theme_CompanionLight, R.style.Palette_Studio),
    MAISON(R.string.theme_maison, ThemeGroup.AMBIANCES, R.style.Theme_CompanionLight, R.style.Palette_Maison),
    TABLEAU(R.string.theme_tableau, ThemeGroup.AMBIANCES, R.style.Theme_CompanionLight, R.style.Palette_Tableau),
    TERMINAL(R.string.theme_terminal, ThemeGroup.AMBIANCES, R.style.Theme_Companion, R.style.Palette_Terminal),
    CONTRASTE(R.string.theme_contraste, ThemeGroup.ACCESSIBILITE, R.style.Theme_CompanionLight, R.style.Palette_Contraste),
    ;

    companion object {
        fun current(context: Context): AppTheme = entries.firstOrNull { it.name == Prefs.theme(context) } ?: CHALEUR

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

/** Valeur entière ou énumérée du thème (absent = 0) : `themeInt(context, R.attr.c_pattern)`. */
fun themeInt(context: Context, attr: Int): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(attr, value, true)) value.data else 0
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
