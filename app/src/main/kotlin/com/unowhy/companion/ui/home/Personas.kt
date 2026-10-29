package com.unowhy.companion.ui.home

import android.content.Context
import androidx.annotation.StringRes
import com.unowhy.companion.Mode
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.ui.DeckFragment

private const val PAGE = DeckFragment.PAGE_TAB
private const val OPEN = DeckFragment.OPEN_TAB
private const val AGENTS = DeckFragment.AGENTS_TAB
private const val GITLAB = DeckFragment.GITLAB_TAB
private const val JIRA = DeckFragment.JIRA_TAB

/** Taille de texte la plus grande de Réglages > Accessibilité (`SettingsFragment.TEXT_SIZES`). */
private const val MAX_TEXT_SCALE = 1.5f

/**
 * Un widget d'un profil du persona : taille et case. `pick` : pour le widget Touches, rangs des touches du persona
 * qu'il montre (toutes par défaut).
 */
class Slot(val type: WidgetType, val size: WidgetSize, val col: Int, val row: Int, val pick: List<Int>? = null)

/** Un profil de l'Accueil du persona : son nom et ses widgets. */
class Profile(@StringRes val name: Int, val slots: List<Slot>)

/**
 * Personas de la démo produit. `id` est celui de la route `POST /demo/persona` du compagnon, qui pose les touches, pages
 * de touches, applis, plugins, tâches et agenda fictifs ; les touches du widget Touches sont celles qu'il rend. Chaque
 * persona change toute l'expérience de la tablette :
 * - `profiles` : son Accueil (un ou plusieurs profils nommés) ;
 * - `arrival` : l'écran où l'on arrive après le choix ;
 * - `deckTabs` : les seuls onglets du Deck, dans l'ordre (clés de DeckFragment : `page:<titre>` pour une page, dont les
 *   titres sont ceux de backend/src/demo.ts ou du deck (Apps, Média, Réunion), ou celle d'un onglet comme `open`,
 *   `agents`, `gitlab`, `jira`) ; tout autre onglet est masqué, même offert par l'ordinateur (Perso compris) ; le premier
 *   présent s'ouvre au départ ;
 * - `textScale` : taille du texte (1 = normale) ; `wakeWord` : mot d'éveil (null : celui d'avant la démo) ;
 * - `theme` : nom d'un [com.unowhy.companion.ui.AppTheme] à appliquer avec le persona (Sam : CONTRASTE).
 */
enum class Persona(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val profiles: List<Profile>,
    val arrival: Mode = Mode.HOME,
    val deckTabs: List<String> = emptyList(),
    val textScale: Float = 1f,
    val wakeWord: Boolean? = null,
    val theme: String? = null,
) {
    NOUR("nour", R.string.persona_nour, R.string.persona_nour_desc, listOf(
        Profile(R.string.persona_nour, listOf(
            Slot(WidgetType.TASKS, WidgetSize.L, 0, 0),
            Slot(WidgetType.AGENDA, WidgetSize.L, 2, 0),
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 2),
        )),
    ), arrival = Mode.DECK, deckTabs = listOf(PAGE + "Excel", PAGE + "Libellés", PAGE + "Dossiers clients", PAGE + "Apps", OPEN)),
    GWEN("gwen", R.string.persona_gwen, R.string.persona_gwen_desc, listOf(
        Profile(R.string.persona_gwen_daily, listOf(
            Slot(WidgetType.HERO, WidgetSize.W, 0, 0),
            Slot(WidgetType.DAYLINE, WidgetSize.W, 0, 1),
            Slot(WidgetType.KEYS, WidgetSize.L, 0, 2, pick = listOf(0, 1, 2, 3)),
            Slot(WidgetType.MEETING, WidgetSize.M, 2, 2),
            Slot(WidgetType.NEXT, WidgetSize.M, 2, 3),
        )),
        Profile(R.string.persona_gwen_spring, listOf(
            Slot(WidgetType.NOTE, WidgetSize.L, 0, 0),
            Slot(WidgetType.TASKS, WidgetSize.L, 2, 0),
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 2, pick = listOf(0, 4, 1, 2)),
        )),
        Profile(R.string.persona_gwen_webinar, listOf(
            Slot(WidgetType.MEETING, WidgetSize.L, 0, 0),
            Slot(WidgetType.NEXT, WidgetSize.L, 2, 0),
            Slot(WidgetType.NOTE, WidgetSize.L, 0, 2),
            Slot(WidgetType.KEYS, WidgetSize.L, 2, 2, pick = listOf(5, 3)),
        )),
    ), deckTabs = listOf(PAGE + "Diapos", PAGE + "Réseaux", PAGE + "Modèles", PAGE + "Réunion", PAGE + "Apps", PAGE + "Média")),
    CAMILLE("camille", R.string.persona_camille, R.string.persona_camille_desc, listOf(
        Profile(R.string.persona_camille_work, listOf(
            Slot(WidgetType.MEETING, WidgetSize.L, 0, 0),
            Slot(WidgetType.NEXT, WidgetSize.L, 2, 0),
            Slot(WidgetType.FOCUS, WidgetSize.L, 0, 2),
            Slot(WidgetType.KEYS, WidgetSize.L, 2, 2, pick = listOf(0, 1, 2)),
        )),
        Profile(R.string.persona_camille_home, listOf(
            Slot(WidgetType.HERO, WidgetSize.W, 0, 0),
            Slot(WidgetType.DAYLINE, WidgetSize.W, 0, 1),
            Slot(WidgetType.WEATHER, WidgetSize.M, 0, 2),
            Slot(WidgetType.MEDIA, WidgetSize.M, 2, 2),
            Slot(WidgetType.WATER, WidgetSize.S, 0, 3),
            Slot(WidgetType.FOCUS, WidgetSize.S, 1, 3),
        )),
    ), deckTabs = listOf(PAGE + "Travail", PAGE + "Maison", PAGE + "Réunion", PAGE + "Apps", PAGE + "Média")),
    AIMEE("aimee", R.string.persona_aimee, R.string.persona_aimee_desc, listOf(
        Profile(R.string.persona_aimee, listOf(
            Slot(WidgetType.NEXT, WidgetSize.M, 0, 0),
            Slot(WidgetType.TASKS, WidgetSize.L, 0, 1),
            Slot(WidgetType.KEYS, WidgetSize.M, 0, 3),
            Slot(WidgetType.AGENDA, WidgetSize.L, 2, 0),
            Slot(WidgetType.OPEN, WidgetSize.L, 2, 2),
        )),
    ), deckTabs = listOf(OPEN, PAGE + "Projets", PAGE + "Réunion", JIRA, PAGE + "Apps")),
    SAM("sam", R.string.persona_sam, R.string.persona_sam_desc, listOf(
        Profile(R.string.persona_sam, listOf(
            Slot(WidgetType.KEYS, WidgetSize.XL, 0, 0),
            Slot(WidgetType.APPS, WidgetSize.L, 0, 2),
            Slot(WidgetType.TASKS, WidgetSize.L, 2, 2),
        )),
    ), deckTabs = listOf(PAGE + "Mes touches", PAGE + "Apps"), textScale = MAX_TEXT_SCALE, wakeWord = true, theme = "CONTRASTE"),
    SACHA("sacha", R.string.persona_sacha, R.string.persona_sacha_desc, listOf(
        Profile(R.string.persona_sacha, listOf(
            Slot(WidgetType.AGENTS, WidgetSize.XL, 0, 0),
            Slot(WidgetType.KEYS, WidgetSize.L, 0, 2),
            Slot(WidgetType.TASKS, WidgetSize.L, 2, 2),
        )),
    ), arrival = Mode.DECK, deckTabs = listOf(PAGE + "Git", PAGE + "Tests", PAGE + "Déploiement", GITLAB, JIRA, AGENTS, OPEN));

    /**
     * L'Accueil du persona : un profil par entrée de [profiles], nommé d'après `names` (même ordre), et pour les widgets
     * Touches les `keys` posées sur l'ordinateur `computerId` (les touches d'un widget sont rangées par ordinateur, voir
     * [KeyRef.putFor]).
     */
    fun layout(names: List<String>, keys: List<KeyRef>, computerId: String?): HomeLayout {
        require(names.size == profiles.size)
        val layout = HomeLayout(mutableListOf(), mutableListOf())
        profiles.forEachIndexed { page, profile ->
            val items = profile.slots.map { slot ->
                Placed(slot.type, slot.size, page, slot.col, slot.row).also {
                    if (slot.type == WidgetType.KEYS) {
                        KeyRef.putFor(it.config, computerId, slot.pick?.mapNotNull { keys.getOrNull(it) } ?: keys)
                    }
                }
            }
            layout.addProfile(names[page], items)
        }
        return layout
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Démo en cours sur la tablette : l'Accueil, le thème, l'ordre des onglets du Deck, la taille du texte et le mot d'éveil
 * d'avant sont gardés au premier persona choisi et rendus par [quit]. Changer de persona garde la sauvegarde d'origine.
 */
object Demo {
    private const val FILE = "companion"
    private const val KEY_PERSONA = "demo_persona"
    private const val KEY_SAVED = "demo_saved"
    private const val KEY_LAYOUT = "demo_saved_layout"
    private const val KEY_THEME = "demo_saved_theme"
    private const val KEY_TABS = "demo_saved_tabs"
    private const val KEY_SCALE = "demo_saved_scale"
    private const val KEY_WAKE = "demo_saved_wake"
    private const val KEY_ARRIVAL = "demo_arrival"
    private const val KEY_START = "demo_start_tabs"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun active(context: Context): Persona? = Persona.of(prefs(context).getString(KEY_PERSONA, null))

    /** Onglets que le persona de la démo en cours laisse au Deck, dans l'ordre ; null hors démo. */
    fun deckTabs(context: Context): List<String>? = active(context)?.deckTabs?.takeIf { it.isNotEmpty() }

    /**
     * Pose `layout` comme Accueil, l'ordre des onglets du Deck, la taille du texte et le mot d'éveil du persona, et son
     * thème (celui d'avant la démo s'il n'en a pas). L'écran d'arrivée et l'onglet de départ du Deck se lisent une fois ([takeArrival],
     * [takeStartTabs]). L'activité doit être recréée ensuite.
     */
    fun start(context: Context, persona: Persona, layout: HomeLayout, cadran: Boolean) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SAVED, false)) {
            prefs.edit()
                .putBoolean(KEY_SAVED, true)
                .putString(KEY_LAYOUT, HomeLayout.raw(context, cadran))
                .putString(KEY_THEME, Prefs.theme(context))
                .putString(KEY_TABS, Prefs.deckTabOrder(context).joinToString("\n"))
                .putFloat(KEY_SCALE, Prefs.textScale(context))
                .putBoolean(KEY_WAKE, Prefs.wakeWord(context))
                .apply()
        }
        prefs.edit()
            .putString(KEY_PERSONA, persona.id)
            .putString(KEY_ARRIVAL, persona.arrival.key)
            .putString(KEY_START, persona.deckTabs.joinToString("\n"))
            .apply()
        HomeLayout.save(context, layout, cadran)
        // Un persona sans thème rend celui d'avant la démo (le précédent persona a pu en poser un).
        Prefs.setTheme(context, persona.theme ?: prefs.getString(KEY_THEME, null))
        Prefs.setDeckTabOrder(context, persona.deckTabs)
        Prefs.setTextScale(context, persona.textScale)
        Prefs.setWakeWord(context, persona.wakeWord ?: prefs.getBoolean(KEY_WAKE, true))
    }

    /** Écran d'arrivée demandé par le dernier choix de persona, une seule fois ; null s'il n'y en a pas. */
    fun takeArrival(context: Context): Mode? {
        val prefs = prefs(context)
        val key = prefs.getString(KEY_ARRIVAL, null) ?: return null
        prefs.edit().remove(KEY_ARRIVAL).apply()
        return Mode.of(key)
    }

    /** Onglets du Deck à ouvrir au départ (le premier présent), une seule fois ; vide s'il n'y en a pas. */
    fun takeStartTabs(context: Context): List<String> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_START, null) ?: return emptyList()
        prefs.edit().remove(KEY_START).apply()
        return raw.split('\n').filter { it.isNotEmpty() }
    }

    /** Rend ce qui était réglé avant la démo. Faux s'il n'y avait pas de démo. */
    fun quit(context: Context, cadran: Boolean): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SAVED, false)) return false
        HomeLayout.restore(context, cadran, prefs.getString(KEY_LAYOUT, null))
        Prefs.setTheme(context, prefs.getString(KEY_THEME, null))
        Prefs.setDeckTabOrder(context, prefs.getString(KEY_TABS, null).orEmpty().split('\n').filter { it.isNotEmpty() })
        Prefs.setTextScale(context, prefs.getFloat(KEY_SCALE, 1f))
        Prefs.setWakeWord(context, prefs.getBoolean(KEY_WAKE, true))
        prefs.edit().remove(KEY_SAVED).remove(KEY_LAYOUT).remove(KEY_THEME).remove(KEY_TABS).remove(KEY_SCALE).remove(KEY_WAKE)
            .remove(KEY_PERSONA).remove(KEY_ARRIVAL).remove(KEY_START).apply()
        return true
    }
}
