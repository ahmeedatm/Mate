package com.unowhy.companion.ui.home

import android.content.Context
import androidx.annotation.StringRes
import com.unowhy.companion.Prefs
import com.unowhy.companion.R

/**
 * Personas de la démo produit : un Accueil à un seul profil, nommé d'après le persona. `id` est celui de la route
 * `POST /demo/persona` du compagnon, qui pose les touches, applis, tâches et agenda fictifs ; les touches du widget
 * Touches sont celles qu'il rend. `theme` : nom d'un [com.unowhy.companion.ui.AppTheme] à appliquer avec le persona
 * (aucun pour l'instant, le thème contraste élevé de Karim s'y branchera).
 */
enum class Persona(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    private val items: List<Placed>,
    val theme: String? = null,
) {
    SYLVIE("sylvie", R.string.persona_sylvie, R.string.persona_sylvie_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.APPS, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.KEYS, WidgetSize.L, 0, 2, 2),
    )),
    JULIE("julie", R.string.persona_julie, R.string.persona_julie_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.KEYS, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.MEETING, WidgetSize.M, 0, 2, 2),
        Placed(WidgetType.NEXT, WidgetSize.M, 0, 2, 3),
    )),
    CAMILLE("camille", R.string.persona_camille, R.string.persona_camille_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.MEETING, WidgetSize.M, 0, 0, 2),
        Placed(WidgetType.KEYS, WidgetSize.M, 0, 0, 3),
        Placed(WidgetType.FOCUS, WidgetSize.L, 0, 2, 2),
    )),
    THOMAS("thomas", R.string.persona_thomas, R.string.persona_thomas_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.NEXT, WidgetSize.V, 0, 0, 2),
        Placed(WidgetType.TASKS, WidgetSize.V, 0, 1, 2),
        Placed(WidgetType.OPEN, WidgetSize.L, 0, 2, 2),
    )),
    KARIM("karim", R.string.persona_karim, R.string.persona_karim_desc, listOf(
        Placed(WidgetType.KEYS, WidgetSize.XL, 0, 0, 0),
        Placed(WidgetType.APPS, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.TASKS, WidgetSize.L, 0, 2, 2),
    )),
    LEO("leo", R.string.persona_leo, R.string.persona_leo_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.KEYS, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.AGENTS, WidgetSize.M, 0, 2, 2),
        Placed(WidgetType.TASKS, WidgetSize.M, 0, 2, 3),
    ));

    /**
     * L'Accueil du persona : un profil nommé `name`, ses widgets, et pour le widget Touches les `keys` posées sur
     * l'ordinateur `computerId` (les touches d'un widget sont rangées par ordinateur, voir [KeyRef.putFor]).
     */
    fun layout(name: String, keys: List<KeyRef>, computerId: String?): HomeLayout {
        val widgets = items.map { it.copy() }
        widgets.filter { it.type == WidgetType.KEYS }.forEach { KeyRef.putFor(it.config, computerId, keys) }
        return HomeLayout(widgets.toMutableList(), mutableListOf(name))
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Démo en cours sur la tablette : l'Accueil et le thème d'avant sont gardés au premier persona choisi et rendus par
 * [quit]. Changer de persona garde la sauvegarde d'origine.
 */
object Demo {
    private const val FILE = "companion"
    private const val KEY_PERSONA = "demo_persona"
    private const val KEY_SAVED = "demo_saved"
    private const val KEY_LAYOUT = "demo_saved_layout"
    private const val KEY_THEME = "demo_saved_theme"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun active(context: Context): Persona? = Persona.of(prefs(context).getString(KEY_PERSONA, null))

    /** Pose `layout` comme Accueil, et le thème du persona s'il en a un. L'activité doit être recréée ensuite. */
    fun start(context: Context, persona: Persona, layout: HomeLayout, cadran: Boolean) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SAVED, false)) {
            prefs.edit()
                .putBoolean(KEY_SAVED, true)
                .putString(KEY_LAYOUT, HomeLayout.raw(context, cadran))
                .putString(KEY_THEME, Prefs.theme(context))
                .apply()
        }
        prefs.edit().putString(KEY_PERSONA, persona.id).apply()
        HomeLayout.save(context, layout, cadran)
        persona.theme?.let { Prefs.setTheme(context, it) }
    }

    /** Rend l'Accueil et le thème d'avant la démo. Faux s'il n'y avait pas de démo. */
    fun quit(context: Context, cadran: Boolean): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SAVED, false)) return false
        HomeLayout.restore(context, cadran, prefs.getString(KEY_LAYOUT, null))
        Prefs.setTheme(context, prefs.getString(KEY_THEME, null))
        prefs.edit().remove(KEY_SAVED).remove(KEY_LAYOUT).remove(KEY_THEME).remove(KEY_PERSONA).apply()
        return true
    }
}
