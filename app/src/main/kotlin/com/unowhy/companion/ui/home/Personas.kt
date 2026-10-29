package com.unowhy.companion.ui.home

import android.content.Context
import androidx.annotation.StringRes
import com.unowhy.companion.Mode
import com.unowhy.companion.Prefs
import com.unowhy.companion.R

/** Taille de texte la plus grande de Réglages > Accessibilité (`SettingsFragment.TEXT_SIZES`). */
private const val MAX_TEXT_SCALE = 1.5f

/**
 * Un widget d'un écran du persona : taille et case. `page` : pour le widget Touches, titre de la page de touches nommée
 * (posée par le compagnon, backend/src/demo.ts) dont il montre toutes les touches.
 */
class Slot(val type: WidgetType, val size: WidgetSize, val col: Int, val row: Int, val page: String? = null)

/** Un écran du persona (un profil de la barre) : son nom et ses widgets. */
class Profile(@StringRes val name: Int, val slots: List<Slot>)

/**
 * Personas de la démo produit. `id` est celui de la route `POST /demo/persona` du compagnon, qui pose les touches, pages
 * de touches, applis, plugins, tâches et agenda fictifs. Chaque persona change toute l'expérience de la tablette :
 * - `profiles` : ses écrans, dans l'ordre de la barre (Deck V2) ; le premier est l'Accueil et l'écran d'arrivée. Écran
 *   Réunion standard en plus, hors barre : il ne s'affiche que pendant un appel ;
 * - `textScale` : taille du texte (1 = normale) ; `wakeWord` : mot d'éveil (null : celui d'avant la démo) ;
 * - `theme` : nom d'un [com.unowhy.companion.ui.AppTheme] à appliquer avec le persona (Sam : CONTRASTE).
 */
enum class Persona(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val profiles: List<Profile>,
    val textScale: Float = 1f,
    val wakeWord: Boolean? = null,
    val theme: String? = null,
) {
    NOUR("nour", R.string.persona_nour, R.string.persona_nour_desc, listOf(
        Profile(R.string.demo_screen_excel, listOf(
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 0, page = "Excel"),
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 2, page = "Libellés"),
        )),
        Profile(R.string.demo_screen_files, listOf(
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 0, page = "Dossiers clients"),
            Slot(WidgetType.OPEN, WidgetSize.XL, 0, 2),
        )),
        Profile(R.string.profile_first, listOf(
            Slot(WidgetType.TASKS, WidgetSize.T, 0, 0),
            Slot(WidgetType.AGENDA, WidgetSize.L, 2, 0),
            Slot(WidgetType.NEXT, WidgetSize.L, 2, 2),
        )),
    )),
    GWEN("gwen", R.string.persona_gwen, R.string.persona_gwen_desc, listOf(
        Profile(R.string.profile_first, listOf(
            Slot(WidgetType.AGENDA, WidgetSize.L, 0, 0),
            Slot(WidgetType.TASKS, WidgetSize.L, 2, 0),
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 2, page = "Modèles"),
        )),
        Profile(R.string.demo_screen_slides, listOf(
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 0, page = "Diapos"),
            Slot(WidgetType.MEDIA, WidgetSize.L, 0, 2),
        )),
        Profile(R.string.demo_screen_campaigns, listOf(
            Slot(WidgetType.KEYS, WidgetSize.L, 0, 0, page = "Réseaux"),
            Slot(WidgetType.KEYS, WidgetSize.L, 2, 0, page = "Modèles"),
            Slot(WidgetType.APPS, WidgetSize.XL, 0, 2),
        )),
    )),
    CAMILLE("camille", R.string.persona_camille, R.string.persona_camille_desc, listOf(
        Profile(R.string.demo_screen_work, listOf(
            Slot(WidgetType.FOCUS, WidgetSize.L, 0, 0),
            Slot(WidgetType.NEXT, WidgetSize.L, 2, 0),
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 2, page = "Travail"),
        )),
        Profile(R.string.demo_screen_house, listOf(
            Slot(WidgetType.KEYS, WidgetSize.L, 0, 0, page = "Maison"),
            Slot(WidgetType.MEDIA, WidgetSize.L, 2, 0),
            Slot(WidgetType.TASKS, WidgetSize.L, 0, 2),
        )),
    )),
    AIMEE("aimee", R.string.persona_aimee, R.string.persona_aimee_desc, listOf(
        Profile(R.string.profile_first, listOf(
            Slot(WidgetType.NEXT, WidgetSize.L, 0, 0),
            Slot(WidgetType.AGENDA, WidgetSize.L, 0, 2),
            Slot(WidgetType.TASKS, WidgetSize.T, 2, 0),
        )),
        Profile(R.string.demo_screen_projects, listOf(
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 0, page = "Projets"),
            Slot(WidgetType.OPEN, WidgetSize.XL, 0, 2),
        )),
        Profile(R.string.demo_screen_jira, listOf(
            Slot(WidgetType.JIRA, WidgetSize.XL, 0, 0),
        )),
    )),
    SAM("sam", R.string.persona_sam, R.string.persona_sam_desc, listOf(
        Profile(R.string.profile_first, listOf(
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 0, page = "Mes touches"),
            Slot(WidgetType.APPS, WidgetSize.XL, 0, 2),
        )),
    ), textScale = MAX_TEXT_SCALE, wakeWord = true, theme = "CONTRASTE"),
    SACHA("sacha", R.string.persona_sacha, R.string.persona_sacha_desc, listOf(
        Profile(R.string.demo_screen_code, listOf(
            Slot(WidgetType.KEYS, WidgetSize.L, 0, 0, page = "Git"),
            Slot(WidgetType.KEYS, WidgetSize.L, 2, 0, page = "Tests"),
            Slot(WidgetType.KEYS, WidgetSize.L, 0, 2, page = "Déploiement"),
            Slot(WidgetType.AGENTS, WidgetSize.L, 2, 2),
        )),
        Profile(R.string.demo_screen_gitlab, listOf(
            Slot(WidgetType.GITLAB, WidgetSize.XL, 0, 0),
        )),
        Profile(R.string.demo_screen_jira, listOf(
            Slot(WidgetType.JIRA, WidgetSize.XL, 0, 0),
        )),
        Profile(R.string.profile_first, listOf(
            Slot(WidgetType.TASKS, WidgetSize.L, 0, 0),
        )),
    ));

    /**
     * La disposition du persona : un profil par entrée de [profiles], nommé d'après `names` (même ordre), puis l'écran
     * Réunion standard (`callName`, modèle de Réunion : [HomeLayout.ensureRoles]). Le premier profil est l'Accueil, donc
     * l'écran où l'on arrive ; aucun profil Deck. Les widgets Touches reçoivent les touches des pages posées sur
     * l'ordinateur `computerId` (`pages` : titre de la page nommée vers ses touches ; les touches d'un widget sont rangées
     * par ordinateur, voir [KeyRef.putFor]).
     */
    fun layout(names: List<String>, callName: String, pages: Map<String, List<KeyRef>>, computerId: String?): HomeLayout {
        require(names.size == profiles.size)
        val layout = HomeLayout(mutableListOf(), mutableListOf())
        profiles.forEachIndexed { page, profile ->
            val items = profile.slots.map { slot ->
                Placed(slot.type, slot.size, page, slot.col, slot.row).also {
                    if (slot.type == WidgetType.KEYS) KeyRef.putFor(it.config, computerId, pages[slot.page].orEmpty())
                }
            }
            layout.addProfile(names[page], items)
        }
        layout.ensureRoles(callName)
        return layout
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Démo en cours sur la tablette : l'Accueil (tous les profils et leurs rôles), le thème, la taille du texte et le mot
 * d'éveil d'avant sont gardés au premier persona choisi et rendus par [quit]. Changer de persona garde la sauvegarde d'origine.
 */
object Demo {
    private const val FILE = "companion"
    private const val KEY_PERSONA = "demo_persona"
    private const val KEY_SAVED = "demo_saved"
    private const val KEY_LAYOUT = "demo_saved_layout"
    private const val KEY_THEME = "demo_saved_theme"
    private const val KEY_SCALE = "demo_saved_scale"
    private const val KEY_WAKE = "demo_saved_wake"
    private const val KEY_ARRIVAL = "demo_arrival"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun active(context: Context): Persona? = Persona.of(prefs(context).getString(KEY_PERSONA, null))

    /**
     * Pose `layout` comme Accueil, la taille du texte et le mot d'éveil du persona, et son thème (celui d'avant la démo
     * s'il n'en a pas). L'arrivée sur l'Accueil, premier écran du persona, se lit une fois ([takeArrival]). L'activité
     * doit être recréée ensuite.
     */
    fun start(context: Context, persona: Persona, layout: HomeLayout, cadran: Boolean) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SAVED, false)) {
            prefs.edit()
                .putBoolean(KEY_SAVED, true)
                .putString(KEY_LAYOUT, HomeLayout.raw(context, cadran))
                .putString(KEY_THEME, Prefs.theme(context))
                .putFloat(KEY_SCALE, Prefs.textScale(context))
                .putBoolean(KEY_WAKE, Prefs.wakeWord(context))
                .apply()
        }
        prefs.edit()
            .putString(KEY_PERSONA, persona.id)
            .putString(KEY_ARRIVAL, Mode.HOME.key)
            .apply()
        HomeLayout.save(context, layout, cadran)
        // Un persona sans thème rend celui d'avant la démo (le précédent persona a pu en poser un).
        Prefs.setTheme(context, persona.theme ?: prefs.getString(KEY_THEME, null))
        Prefs.setTextScale(context, persona.textScale)
        Prefs.setWakeWord(context, persona.wakeWord ?: prefs.getBoolean(KEY_WAKE, true))
    }

    /** Écran d'arrivée demandé par le dernier choix de persona (l'Accueil), une seule fois ; null s'il n'y en a pas. */
    fun takeArrival(context: Context): Mode? {
        val prefs = prefs(context)
        val key = prefs.getString(KEY_ARRIVAL, null) ?: return null
        prefs.edit().remove(KEY_ARRIVAL).apply()
        return Mode.of(key)
    }

    /** Rend ce qui était réglé avant la démo. Faux s'il n'y avait pas de démo. */
    fun quit(context: Context, cadran: Boolean): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SAVED, false)) return false
        HomeLayout.restore(context, cadran, prefs.getString(KEY_LAYOUT, null))
        Prefs.setTheme(context, prefs.getString(KEY_THEME, null))
        Prefs.setTextScale(context, prefs.getFloat(KEY_SCALE, 1f))
        Prefs.setWakeWord(context, prefs.getBoolean(KEY_WAKE, true))
        prefs.edit().remove(KEY_SAVED).remove(KEY_LAYOUT).remove(KEY_THEME).remove(KEY_SCALE).remove(KEY_WAKE)
            .remove(KEY_PERSONA).remove(KEY_ARRIVAL).apply()
        return true
    }
}
