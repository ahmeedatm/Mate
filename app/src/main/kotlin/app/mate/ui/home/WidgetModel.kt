package app.mate.ui.home

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.mate.ComputerConfig
import app.mate.Computers
import app.mate.R
import app.mate.net.HomeFeatures
import app.mate.ui.AppTheme
import app.mate.ui.ThemeGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accueil à widgets : une grille de [COLS] × [ROWS] cases par profil (une page = un profil nommé, sans limite), des
 * widgets de tailles fixes calés sur la grille. La disposition est propre à la tablette (préférences), quel que soit
 * l'ordinateur.
 */
const val COLS = 4
const val ROWS = 4

/**
 * Les 16 formes de la grille, en cases (largeur × hauteur), de 1 × 1 à 4 × 4. But : chaque widget les accepte toutes
 * (règle gardée par `WidgetRulesTest`, #178) et dessine une mise en page par [family], pas une par forme. En attendant,
 * `WidgetType.sizes` dit ce que chacun accepte (`L3`, `T3`, `FULL` aux tableaux, `XL3` au mixeur…) et les listes
 * d'exceptions `WidgetGaps*.kt` ce qui manque. Les noms sont enregistrés dans les dispositions : ne jamais en renommer un.
 */
enum class WidgetSize(val w: Int, val h: Int, @StringRes val label: Int) {
    S(1, 1, R.string.widget_size_s),
    M(2, 1, R.string.widget_size_m),
    L(2, 2, R.string.widget_size_l),
    T(2, 4, R.string.widget_size_t),
    W(4, 1, R.string.widget_size_w),
    XL(4, 2, R.string.widget_size_xl),
    V(1, 2, R.string.widget_size_v),
    L3(3, 2, R.string.widget_size_l3),
    T3(3, 4, R.string.widget_size_t3),
    XL3(4, 3, R.string.widget_size_xl3),
    FULL(4, 4, R.string.widget_size_full),
    M3(3, 1, R.string.widget_size_m3),
    V3(1, 3, R.string.widget_size_v3),
    V4(1, 4, R.string.widget_size_v4),
    L23(2, 3, R.string.widget_size_l23),
    L33(3, 3, R.string.widget_size_l33),
    ;

    /** Famille de la forme : la mise en page que le widget lui donne ([HomeWidget.applySize]). */
    val family: ShapeFamily
        get() = when {
            w == 1 && h == 1 -> ShapeFamily.PILL
            h == 1 -> ShapeFamily.ROW
            w == 1 -> ShapeFamily.COLUMN
            w == COLS && h == ROWS -> ShapeFamily.FULL
            w == COLS || h == ROWS -> ShapeFamily.LARGE
            else -> ShapeFamily.BLOCK
        }
}

/**
 * Familles de formes : un widget dessine une mise en page par famille, jamais un simple étirement. Plus grand, il
 * montre plus de choses ; plus petit, il garde l'essentiel.
 */
enum class ShapeFamily {
    /** 1 × 1 : un chiffre, une icône ou un bouton. */
    PILL,

    /** 2 × 1, 3 × 1, 4 × 1 : une ligne. */
    ROW,

    /** 1 × 2, 1 × 3, 1 × 4 : une colonne. */
    COLUMN,

    /** 2 × 2, 3 × 2, 2 × 3, 3 × 3 : un bloc. */
    BLOCK,

    /** 2 × 4, 3 × 4, 4 × 2, 4 × 3 : toute une largeur ou toute une hauteur. */
    LARGE,

    /** 4 × 4 : tout l'écran. */
    FULL,
}

/**
 * Widgets du catalogue. `sizes` : tailles acceptées, la première est celle d'un ajout ; `computer` : a besoin de
 * l'ordinateur (sans ordinateur, une carte « Connecter mon ordinateur » le remplace).
 */
enum class WidgetType(
    val key: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val icon: Int,
    val sizes: List<WidgetSize>,
    val computer: Boolean,
) {
    CLOCK("clock", R.string.widget_clock, R.string.widget_clock_desc, R.drawable.ic_clock, listOf(WidgetSize.L, WidgetSize.XL, WidgetSize.S, WidgetSize.M, WidgetSize.W).let { it + (WidgetSize.entries - it) }, false),
    NEXT("next", R.string.widget_next, R.string.widget_next_desc, R.drawable.ic_event, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.V, WidgetSize.S).let { it + (WidgetSize.entries - it) }, true),
    AGENDA("agenda", R.string.widget_agenda, R.string.widget_agenda_desc, R.drawable.ic_event, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.M, WidgetSize.V).let { it + (WidgetSize.entries - it) }, true),
    TASKS("tasks", R.string.widget_tasks, R.string.widget_tasks_desc, R.drawable.ic_tasks, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.V, WidgetSize.M).let { it + (WidgetSize.entries - it) }, true),
    SHOPPING("shopping", R.string.widget_shopping, R.string.widget_shopping_desc, R.drawable.ic_cart, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.V, WidgetSize.M).let { it + (WidgetSize.entries - it) }, true),
    FOCUS("focus", R.string.widget_focus, R.string.widget_focus_desc, R.drawable.ic_timer, listOf(WidgetSize.S, WidgetSize.V, WidgetSize.L).let { it + (WidgetSize.entries - it) }, false),
    WATER("water", R.string.widget_water, R.string.widget_water_desc, R.drawable.ic_water, listOf(WidgetSize.S, WidgetSize.M).let { it + (WidgetSize.entries - it) }, false),
    AUDIO("audio", R.string.widget_audio, R.string.widget_audio_desc, R.drawable.ic_volume, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S, WidgetSize.W).let { it + (WidgetSize.entries - it) }, true),
    MEDIA("media", R.string.widget_media, R.string.widget_media_desc, R.drawable.ic_play, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S).let { it + (WidgetSize.entries - it) }, true),
    PERF("perf", R.string.widget_perf, R.string.widget_perf_desc, R.drawable.ic_speed, listOf(WidgetSize.M) + (WidgetSize.entries - WidgetSize.M), true),
    KEYS("keys", R.string.widget_keys, R.string.widget_keys_desc, R.drawable.ic_deck, listOf(WidgetSize.M) + (WidgetSize.entries - WidgetSize.M), true),
    WEATHER("weather", R.string.widget_weather, R.string.widget_weather_desc, R.drawable.ic_cloud, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S).let { it + (WidgetSize.entries - it) }, false),
    TRANSPORT("transport", R.string.widget_transport, R.string.widget_transport_desc, R.drawable.ic_transport, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.V, WidgetSize.S).let { it + (WidgetSize.entries - it) }, false),
    NOTE("note", R.string.widget_note, R.string.widget_note_desc, R.drawable.ic_edit, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L).let { it + (WidgetSize.entries - it) }, false),
    HERO("hero", R.string.widget_hero, R.string.widget_hero_desc, R.drawable.ic_clock, listOf(WidgetSize.W).let { it + (WidgetSize.entries - it) }, false),
    DAY("day", R.string.widget_day, R.string.widget_day_desc, R.drawable.ic_event, listOf(WidgetSize.W).let { it + (WidgetSize.entries - it) }, false),
    DAYLINE("dayline", R.string.widget_dayline, R.string.widget_dayline_desc, R.drawable.ic_alarm, listOf(WidgetSize.W).let { it + (WidgetSize.entries - it) }, false),
    AGENTS("agents", R.string.widget_agents, R.string.widget_agents_desc, R.drawable.ic_agents, listOf(WidgetSize.M) + (WidgetSize.entries - WidgetSize.M), true),
    MIXER("mixer", R.string.widget_mixer, R.string.widget_mixer_desc, R.drawable.ic_wave, listOf(WidgetSize.XL, WidgetSize.L3, WidgetSize.XL3, WidgetSize.T3, WidgetSize.FULL, WidgetSize.S, WidgetSize.V, WidgetSize.M, WidgetSize.M3, WidgetSize.W, WidgetSize.V3, WidgetSize.V4, WidgetSize.L, WidgetSize.L23, WidgetSize.L33, WidgetSize.T), true),
    OPEN("open", R.string.widget_open, R.string.widget_open_desc, R.drawable.ic_display, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    APPS("apps", R.string.widget_apps, R.string.widget_apps_desc, R.drawable.ic_tabs, listOf(WidgetSize.L) + (WidgetSize.entries - WidgetSize.L), true),
    DISCORD("discord", R.string.widget_discord, R.string.widget_discord_desc, R.drawable.ic_call, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S, WidgetSize.M3, WidgetSize.W, WidgetSize.V, WidgetSize.V3, WidgetSize.V4, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33, WidgetSize.XL, WidgetSize.XL3, WidgetSize.T, WidgetSize.T3, WidgetSize.FULL), true),
    MEETING("meeting", R.string.widget_meeting, R.string.widget_meeting_desc, R.drawable.ic_videocam, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S, WidgetSize.M3, WidgetSize.W, WidgetSize.V, WidgetSize.V3, WidgetSize.V4, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33, WidgetSize.XL, WidgetSize.XL3, WidgetSize.T, WidgetSize.T3, WidgetSize.FULL), true),
    PAD("pad", R.string.widget_pad, R.string.widget_pad_desc, R.drawable.ic_tablet, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    CALL("call", R.string.widget_call, R.string.widget_call_desc, R.drawable.ic_videocam, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.M, WidgetSize.S, WidgetSize.M3, WidgetSize.W, WidgetSize.V, WidgetSize.V3, WidgetSize.V4, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33, WidgetSize.XL3, WidgetSize.T, WidgetSize.T3, WidgetSize.FULL), true),
    PRESENT("present", R.string.widget_present, R.string.widget_present_desc, R.drawable.ic_tile_slideshow, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.M, WidgetSize.S, WidgetSize.M3, WidgetSize.W, WidgetSize.V, WidgetSize.V3, WidgetSize.V4, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33, WidgetSize.XL3, WidgetSize.T, WidgetSize.T3, WidgetSize.FULL), true),
    JIRA("jira", R.string.widget_jira, R.string.widget_jira_desc, R.drawable.ic_dashboard, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    GITLAB("gitlab", R.string.widget_gitlab, R.string.widget_gitlab_desc, R.drawable.ic_merge, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    HA("ha", R.string.widget_ha, R.string.widget_ha_desc, R.drawable.ic_lightbulb, listOf(WidgetSize.L, WidgetSize.XL, WidgetSize.M).let { it + (WidgetSize.entries - it) }, true),
    CHECKLIST("checklist", R.string.widget_checklist, R.string.widget_checklist_desc, R.drawable.ic_tile_check_circle, listOf(WidgetSize.L, WidgetSize.T).let { it + (WidgetSize.entries - it) }, false),
    AWAY("away", R.string.widget_away, R.string.widget_away_desc, R.drawable.ic_cafe, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S).let { it + (WidgetSize.entries - it) }, true),
    PIPELINES("pipelines", R.string.widget_pipelines, R.string.widget_pipelines_desc, R.drawable.ic_tile_rocket_launch, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    GITHUB("github", R.string.widget_github, R.string.widget_github_desc, R.drawable.ic_code, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    ACTIONS("actions", R.string.widget_actions, R.string.widget_actions_desc, R.drawable.ic_tile_deployed_code, listOf(WidgetSize.XL) + (WidgetSize.entries - WidgetSize.XL), true),
    YTMUSIC("ytmusic", R.string.widget_ytmusic, R.string.widget_ytmusic_desc, R.drawable.ic_queue_music, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3).let { it + (WidgetSize.entries - it) }, true),
    WEB("web", R.string.widget_web, R.string.widget_web_desc, R.drawable.ic_dashboard, listOf(WidgetSize.L) + (WidgetSize.entries - WidgetSize.L), false),
    NEWS("news", R.string.widget_news, R.string.widget_news_desc, R.drawable.ic_tile_description, listOf(WidgetSize.L, WidgetSize.XL).let { it + (WidgetSize.entries - it) }, true),
    MAIL("mail", R.string.widget_mail, R.string.widget_mail_desc, R.drawable.ic_mail, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L).let { it + (WidgetSize.entries - it) }, true),
    METRICS("metrics", R.string.widget_metrics, R.string.widget_metrics_desc, R.drawable.ic_tile_bar_chart, listOf(WidgetSize.L) + (WidgetSize.entries - WidgetSize.L), true),
    MEALS("meals", R.string.widget_meals, R.string.widget_meals_desc, R.drawable.ic_tile_restaurant, listOf(WidgetSize.L, WidgetSize.XL, WidgetSize.T).let { it + (WidgetSize.entries - it) }, true),
    VALORANT("valorant", R.string.widget_valorant, R.string.widget_valorant_desc, R.drawable.ic_gamepad, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.T).let { it + (WidgetSize.entries - it) }, true),
    SCREEN("screen", R.string.widget_screen, R.string.widget_screen_desc, R.drawable.ic_display, listOf(WidgetSize.L) + (WidgetSize.entries - WidgetSize.L), true);

    /** Taille de l'exemplaire agrandi (widget maintenu, [WidgetZoom]) : la plus grande, la plus large à surface égale. */
    val zoomSize get() = sizes.maxWith(compareBy<WidgetSize>({ it.w * it.h }, { it.w }))

    /** Sans fond de carte, posé à même l'écran. */
    val bare get() = this == HERO || this == DAY || this == DAYLINE

    /**
     * Maintenir le widget l'agrandit : pas ceux sans carte (héros, journée, ligne du jour), rien de plus à y voir, ni la page
     * web (un seul WebView à la fois), ni l'écran de l'ordinateur (une seule image en vol, 640 px de large au plus).
     */
    val zoomable get() = !bare && this != WEB && this != SCREEN

    /**
     * Deux doigts posés sur le widget l'agrandissent (et le rendent à sa taille) : tous les agrandissables, sauf le pavé
     * tactile, à qui deux doigts servent déjà (défiler, pincer). Lui garde la loupe du mode Modifier.
     */
    val twoFingerZoom get() = zoomable && this != PAD

    /**
     * Proposé dans le catalogue de l'ordinateur actif : Agents, Jira, Home Assistant, GitLab, Pipelines, GitHub, Actions et Valorant seulement s'il les offre (Agents :
     * Mac avec Claude Code ; Valorant : Windows), Ouvertes, Son et Pavé seulement si leur plugin n'est pas coupé (écran des plugins).
     */
    fun offered(features: HomeFeatures) = when (this) {
        AGENTS -> features.agents
        JIRA -> features.jira
        HA -> features.ha
        VALORANT -> features.valorant
        GITLAB, PIPELINES -> features.gitlab
        GITHUB, ACTIONS -> features.github
        OPEN -> "open" !in features.off
        MIXER -> "mixer" !in features.off
        PAD -> "pad" !in features.off
        else -> true
    }

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Un widget posé : page, case en haut à gauche, taille, réglages propres (touches choisies, ville, texte). */
/** Taille et case où la poignée calerait un widget ([HomeLayout.pickSize]). */
data class Fit(val size: WidgetSize, val col: Int, val row: Int)

class Placed(
    val type: WidgetType,
    var size: WidgetSize,
    var page: Int,
    var col: Int,
    var row: Int,
    val config: JSONObject = JSONObject(),
) {
    fun copy() = Placed(type, size, page, col, row, JSONObject(config.toString()))

    fun covers(c: Int, r: Int) = c in col until col + size.w && r in row until row + size.h
}

/** Noms automatiques d'avant « Accueil » et « Écran N » (dispositions v1 relues, 29/10). */
private val LEGACY_NAME = Regex("Profil (\\d+)")

/**
 * Disposition complète : widgets et profils. Un profil = une page ; `names[i]` null = pas encore de nom ([nameMissing]
 * lui en donne un, l'interface passe les textes : le modèle ne touche à aucune ressource).
 * Rôles (Deck V2) : `homeProfile` (Accueil, l'écran de départ : supprimé, le premier écran restant hors Réunion le
 * reprend ; -1 s'il n'en reste aucun), `deckProfile` (Deck, -1 s'il a été supprimé), `callProfile`
 * (Réunion : -1 s'il a été supprimé, absent de la barre et du balayage hors appel). `rolesSet` : rôles déjà posés une fois
 * ([ensureRoles] ne recrée pas un Deck supprimé).
 */
class HomeLayout(val widgets: MutableList<Placed>, val names: MutableList<String?>) {

    /** Profils sans nom : garde les appels qui ne comptent que des pages. */
    constructor(widgets: MutableList<Placed>, pages: Int) : this(widgets, MutableList<String?>(pages.coerceAtLeast(1)) { null })

    var homeProfile = 0
    var deckProfile = -1
    var callProfile = -1
    var rolesSet = false

    /** Applis de chaque profil (noms du catalogue), dans l'ordre des noms : l'une d'elles devant sur l'ordinateur affiche la page. */
    private val pageApps: MutableList<MutableList<String>> = MutableList(names.size) { mutableListOf() }

    fun appsOf(index: Int): List<String> = pageApps.getOrNull(index).orEmpty()

    /** Pose les applis de la page ; une appli ne mène qu'à une page, elle est retirée des autres (sans casse). */
    fun setApps(index: Int, apps: List<String>) {
        syncIcons()
        if (index !in pageApps.indices) return
        val taken = apps.map { it.lowercase() }.toSet()
        pageApps.forEachIndexed { i, list -> if (i != index) list.removeAll { it.lowercase() in taken } }
        pageApps[index] = apps.toMutableList()
    }

    /** Applis par page pour [app.mate.ui.GameSession] : Réunion (cachée hors appel) et les pages sans applis n'y sont pas. */
    fun appPages(): Map<Int, List<String>> =
        pageApps.indices.filter { it != callProfile && pageApps[it].isNotEmpty() }.associateWith { pageApps[it].toList() }

    val pages get() = names.size

    /** Icône choisie de chaque profil ([ProfileIcons], null = celle du rôle), dans l'ordre des noms. */
    val icons: MutableList<String?> = MutableList(names.size) { null }

    fun iconOf(index: Int): String? = icons.getOrNull(index)

    fun setIcon(index: Int, key: String?) {
        syncIcons()
        if (index in icons.indices) icons[index] = key
    }

    /** Une entrée par profil, même si les noms ont changé sans passer par ici. */
    private fun syncIcons() {
        while (icons.size < names.size) icons += null
        while (icons.size > names.size) icons.removeAt(icons.lastIndex)
        while (pageApps.size < names.size) pageApps += mutableListOf<String>()
        while (pageApps.size > names.size) pageApps.removeAt(pageApps.lastIndex)
    }

    fun copy() = HomeLayout(widgets.map { it.copy() }.toMutableList(), names.toMutableList()).also {
        it.homeProfile = homeProfile
        it.deckProfile = deckProfile
        it.callProfile = callProfile
        it.rolesSet = rolesSet
        it.icons.clear()
        it.icons += icons
        pageApps.forEachIndexed { i, apps -> it.pageApps[i] += apps }
    }

    /**
     * Pose une fois les rôles : Réunion en dernier, depuis son modèle (affiché seulement pendant un appel). De base, un
     * seul écran : l'Accueil ; d'autres seulement si l'utilisateur en ajoute. Réunion supprimé ne revient pas ici : il
     * se recrée par [addFromTemplate]. Vrai si la disposition a changé.
     */
    fun ensureRoles(callName: String): Boolean {
        if (rolesSet) return false
        rolesSet = true
        callProfile = addProfile(callName, Template.CALL_CADRAN.widgets(0))
        return true
    }

    /**
     * Le premier Deck V2 posait un profil Deck d'office : retiré s'il est resté tel que posé (mêmes widgets aux mêmes
     * places, réglages compris les touches remplies), sinon gardé comme un écran ordinaire. Une seule fois : ensuite
     * plus de rôle Deck. Vrai si la disposition a changé.
     */
    fun dropDefaultDeck(): Boolean {
        val deck = deckProfile.takeIf { it >= 0 } ?: return false
        fun shape(list: List<Placed>) = list.map { listOf(it.type, it.size, it.col, it.row) }.sortedBy { it.toString() }
        val untouched = shape(on(deck)) == shape(Template.DECK_CADRAN.widgets(deck))
        if (untouched && removeProfile(deck)) return true
        deckProfile = -1
        return true
    }

    /** Tout profil se supprime, l'Accueil et le dernier compris ([settleHome]). Réunion supprimé : plus d'écran d'appel. */
    fun canRemove(index: Int) = index in names.indices

    /** Sans Accueil valable, le premier écran hors Réunion le devient ; aucun : -1 (l'Accueil montre l'heure seule). */
    fun settleHome() {
        if (homeProfile !in names.indices || homeProfile == callProfile) homeProfile = profilesInOrder(inCall = false).firstOrNull() ?: -1
    }

    /** Modèles proposés à la création : Réunion seulement tant qu'il n'y a pas d'écran d'appel. */
    fun offered(): List<Template> = Template.entries.filter { it.listed || (it == Template.CALL_CADRAN && callProfile < 0) }

    /** Ajoute un profil depuis un modèle, au nom libre ; Réunion recréé reprend le rôle d'appel s'il n'y en a plus. */
    fun addFromTemplate(template: Template, name: String): Int {
        val index = addProfile(uniqueName(name), template.widgets(pages))
        if (template == Template.CALL_CADRAN && callProfile < 0) callProfile = index
        settleHome()
        return index
    }

    /** Profils dans l'ordre de la barre et du balayage : Réunion n'y est que pendant un appel, en dernier. */
    fun profilesInOrder(inCall: Boolean): List<Int> =
        names.indices.filter { it != callProfile } + if (inCall && callProfile >= 0) listOf(callProfile) else emptyList()

    /** Profil où mène « Deck » (canal, voix, extension Claude) : l'Accueil si le Deck a été supprimé. */
    fun deckTarget() = if (deckProfile >= 0) deckProfile else homeProfile

    /** Ajoute un profil à la fin et renvoie son index ; `items` sont recopiés sur sa page. */
    fun addProfile(name: String?, items: List<Placed> = emptyList()): Int {
        syncIcons()
        names += name
        icons += null
        pageApps += mutableListOf<String>()
        val index = names.size - 1
        widgets += items.map { it.copy().also { c -> c.page = index } }
        return index
    }

    /** Nom affiché : celui donné, sinon `first` pour le premier profil et `pattern` numéroté pour les autres. */
    fun nameOf(index: Int, first: String, pattern: String): String =
        names.getOrNull(index) ?: if (index == 0) first else pattern.format(index + 1)

    fun renameProfile(index: Int, name: String) {
        names[index] = name
    }

    /** Premier nom du modèle `pattern` (« Écran %d ») qu'aucun profil ne porte, du plus petit numéro : un numéro libéré resert. */
    fun freeName(pattern: String): String =
        generateSequence(1) { it + 1 }.map { pattern.format(it) }.first { it !in names }

    /** `base` s'il est libre, sinon « base 2 », « base 3 »… (deux profils créés du même modèle). */
    fun uniqueName(base: String): String =
        if (base !in names) base else generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in names }

    /**
     * Donne un nom à chaque profil qui n'en a pas : `first` au premier, aux autres le plus petit numéro libre (Écran 1
     * et 2 supprimés, le suivant créé reprend « Écran 1 », pas la place dans la liste où comptent Deck et Réunion). Renomme aussi les noms automatiques d'avant : « Profil 2 »
     * au modèle `pattern`, même numéro. Vrai si un nom a été posé.
     */
    fun nameMissing(first: String, pattern: String): Boolean {
        var named = false
        for (i in names.indices) {
            val old = names[i]?.let { LEGACY_NAME.matchEntire(it) }?.groupValues?.get(1)?.toInt()
            if (old != null && pattern.format(old) != names[i] && pattern.format(old) !in names) {
                names[i] = pattern.format(old)
                named = true
            }
        }
        for (i in names.indices) {
            if (names[i] == null) {
                names[i] = if (i == 0) first else freeName(pattern)
                named = true
            }
        }
        return named
    }

    /** Remet le profil `from` à la place `to` ; ses widgets et ses applis le suivent. */
    fun moveProfile(from: Int, to: Int) {
        if (from == to || from !in names.indices || to !in names.indices) return
        syncIcons()
        names.add(to, names.removeAt(from))
        icons.add(to, icons.removeAt(from))
        pageApps.add(to, pageApps.removeAt(from))
        fun moved(p: Int) = when {
            p == from -> to
            from < to && p in from + 1..to -> p - 1
            from > to && p in to until from -> p + 1
            else -> p
        }
        widgets.forEach { it.page = moved(it.page) }
        homeProfile = moved(homeProfile)
        if (deckProfile >= 0) deckProfile = moved(deckProfile)
        if (callProfile >= 0) callProfile = moved(callProfile)
    }

    /** Retire le profil et ses widgets, les suivants reculent d'une page. Faux (rien ne change) si [canRemove] le refuse. */
    fun removeProfile(index: Int): Boolean {
        if (!canRemove(index)) return false
        syncIcons()
        names.removeAt(index)
        icons.removeAt(index)
        pageApps.removeAt(index)
        widgets.removeAll { it.page == index }
        widgets.filter { it.page > index }.forEach { it.page-- }
        fun after(p: Int) = when {
            p == index -> -1
            p > index -> p - 1
            else -> p
        }
        homeProfile = after(homeProfile)
        deckProfile = after(deckProfile)
        callProfile = after(callProfile)
        settleHome()
        return true
    }

    fun on(page: Int) = widgets.filter { it.page == page }

    /** La taille `size` tient en (col, row) de `page` sans sortir de la grille ni chevaucher un autre widget. */
    fun fits(page: Int, col: Int, row: Int, size: WidgetSize, ignore: Placed? = null): Boolean {
        if (col < 0 || row < 0 || col + size.w > COLS || row + size.h > ROWS) return false
        return widgets.none { other ->
            other !== ignore && other.page == page &&
                col < other.col + other.size.w && other.col < col + size.w &&
                row < other.row + other.size.h && other.row < row + size.h
        }
    }

    /**
     * Taille et case de `placed` les plus proches de la zone tirée (bords `left`, `top`, `right`, `bottom` en cases,
     * fractions admises) parmi les tailles que son type accepte, placées sans chevaucher et en gardant sa case en haut à
     * gauche : un widget du bord droit ou du bas grandit vers la gauche ou le haut. À distance égale, la plus petite.
     * Null si aucune ne tient.
     */
    fun pickSize(placed: Placed, left: Float, top: Float, right: Float, bottom: Float): Fit? =
        placed.type.sizes
            .flatMap { size ->
                (placed.row downTo placed.row - size.h + 1).flatMap { r -> (placed.col downTo placed.col - size.w + 1).map { c -> Fit(size, c, r) } }
            }
            .filter { fits(placed.page, it.col, it.row, it.size, ignore = placed) }
            .minWithOrNull(
                compareBy(
                    {
                        val dl = it.col - left
                        val dt = it.row - top
                        val dr = it.col + it.size.w - right
                        val db = it.row + it.size.h - bottom
                        dl * dl + dt * dt + dr * dr + db * db
                    },
                    { it.size.w * it.size.h },
                ),
            )

    /** Première case libre (lecture de gauche à droite, de haut en bas) où `size` tient, ou null. */
    fun freeSpot(page: Int, size: WidgetSize, ignore: Placed? = null): Pair<Int, Int>? {
        for (r in 0..ROWS - size.h) for (c in 0..COLS - size.w) if (fits(page, c, r, size, ignore)) return c to r
        return null
    }

    fun toJson(): String = JSONObject()
        .put("v", 2)
        .put("pages", pages)
        .put("names", JSONArray().apply { names.forEach { put(it ?: JSONObject.NULL) } })
        .put("icons", JSONArray().apply { names.indices.forEach { put(iconOf(it) ?: JSONObject.NULL) } })
        .put("home", homeProfile)
        .put("deck", deckProfile)
        .put("call", callProfile)
        .put("roles", rolesSet)
        .put("pageApps", JSONArray().apply { names.indices.forEach { put(JSONArray(appsOf(it))) } })
        .put("widgets", JSONArray().apply {
            widgets.forEach {
                put(JSONObject().put("t", it.type.key).put("s", it.size.name).put("p", it.page).put("c", it.col).put("r", it.row).put("cfg", it.config))
            }
        })
        .toString()

    companion object {
        private const val PREFS = "companion"

        /** Clé gardée depuis la séparation des thèmes : les dispositions enregistrées restent lues. */
        const val KEY = "home_layout_cadran"

        fun load(context: Context): HomeLayout {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ComputerConfig.key(context, KEY), null)
            return raw?.let { runCatching { parse(it) }.getOrNull() } ?: defaultTemplate(context).build()
        }

        /** Le modèle d'une tablette neuve : Maison sur Maison, sinon celui d'Instrument (heure et rendez-vous en grand) ou, aux autres thèmes, Travail. */
        private fun defaultTemplate(context: Context) =
            if (Computers.homeActive(context)) Template.HOUSE else if (AppTheme.current(context).group == ThemeGroup.INSTRUMENT) Template.WORK_INSTRUMENT else Template.WORK_CADRAN

        /** Ce widget est-il sur l'Accueil (modèle par défaut si rien n'est enregistré) ? */
        fun has(context: Context, type: WidgetType) = load(context).widgets.any { it.type == type }

        fun save(context: Context, layout: HomeLayout) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ComputerConfig.key(context, KEY), layout.toJson()).apply()
        }

        /** Disposition enregistrée telle quelle (null : jamais enregistrée, le modèle par défaut s'applique). */
        fun raw(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ComputerConfig.key(context, KEY), null)

        /** Disposition invalide (widget inconnu, chevauchement) : on la refuse en entier, le modèle la remplace. */
        private fun parse(raw: String): HomeLayout {
            val json = JSONObject(raw)
            val saved = json.optJSONArray("names")
            val names = if (saved != null) {
                MutableList(saved.length()) { i -> if (saved.isNull(i)) null else saved.getString(i) }
            } else {
                MutableList<String?>(json.getInt("pages").coerceAtLeast(1)) { null }
            }
            val layout = HomeLayout(mutableListOf(), names)
            json.optJSONArray("icons")?.let { saved -> names.indices.forEach { i -> if (i < saved.length() && !saved.isNull(i)) layout.setIcon(i, saved.getString(i)) } }
            layout.homeProfile = json.optInt("home", 0)
            layout.deckProfile = json.optInt("deck", -1).takeIf { it in names.indices } ?: -1
            layout.callProfile = json.optInt("call", -1).takeIf { it in names.indices } ?: -1
            layout.settleHome()
            layout.rolesSet = json.optBoolean("roles", false)
            LayoutConfig.readPageApps(json, layout)
            val list = json.getJSONArray("widgets")
            for (i in 0 until list.length()) {
                val w = list.getJSONObject(i)
                val type = WidgetType.of(w.getString("t")) ?: continue
                val size = WidgetSize.entries.firstOrNull { it.name == w.getString("s") }?.takeIf { it in type.sizes } ?: type.sizes.first()
                val placed = Placed(type, size, w.getInt("p"), w.getInt("c"), w.getInt("r"), w.optJSONObject("cfg") ?: JSONObject())
                require(placed.page < layout.pages && layout.fits(placed.page, placed.col, placed.row, size))
                layout.widgets += placed
            }
            return layout
        }
    }
}

/**
 * Modèles prêts à l'emploi : le défaut est Travail. `listed` : proposé à la création d'un
 * profil (Réunion ne l'est que s'il n'existe plus : [HomeLayout.offered] ; Travail Instrument sert de défaut du thème,
 * il ne double pas Travail dans la liste).
 */
enum class Template(
    @StringRes val title: Int,
    @StringRes val description: Int,
    private val items: List<Placed>,
    val listed: Boolean = true,
) {
    WORK_CADRAN(R.string.template_work, R.string.template_work_cadran_desc, listOf(
        // La grande heure (météo, eau) en haut, puis l'agenda, le Focus, les applis du travail (Teams, Outlook, Chrome…).
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.AGENDA, WidgetSize.V, 0, 0, 2),
        Placed(WidgetType.FOCUS, WidgetSize.V, 0, 1, 2),
        Placed(WidgetType.APPS, WidgetSize.V, 0, 2, 2),
        // Touches remplies d'office (Verrouiller, Couper le micro, Ne pas déranger…) : pas de case vide au premier lancement.
        Placed(WidgetType.KEYS, WidgetSize.V, 0, 3, 2, JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis")),
    )),
    // Instrument : l'heure et le prochain rendez-vous en haut (fenêtres LCD), la ligne du jour 8 h-20 h sur toute la largeur,
    // puis l'agenda et le Focus l'un sur l'autre, les applis, les touches, l'eau et la météo (4 colonnes × 2 lignes en tout).
    WORK_INSTRUMENT(R.string.template_work, R.string.template_work_instrument_desc, listOf(
        Placed(WidgetType.CLOCK, WidgetSize.M, 0, 0, 0),
        Placed(WidgetType.NEXT, WidgetSize.M, 0, 2, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.AGENDA, WidgetSize.S, 0, 0, 2),
        Placed(WidgetType.FOCUS, WidgetSize.S, 0, 0, 3),
        Placed(WidgetType.APPS, WidgetSize.V, 0, 1, 2),
        Placed(WidgetType.KEYS, WidgetSize.V, 0, 2, 2, JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis")),
        Placed(WidgetType.WATER, WidgetSize.S, 0, 3, 2),
        Placed(WidgetType.WEATHER, WidgetSize.S, 0, 3, 3),
    ), listed = false),
    STANDBY_CADRAN(R.string.template_standby, R.string.template_standby_cadran_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
    )),

    // Deck : agir sur l'ordinateur. Applis sur la haute ligne, son de l'ordinateur sur la ligne fine, touches et fenêtres
    // dessous (la musique est dans le header, MusicChip : pas de doublon).
    DECK_CADRAN(R.string.template_deck, R.string.template_deck_desc, listOf(
        Placed(WidgetType.APPS, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.AUDIO, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.KEYS, WidgetSize.L, 0, 0, 2, JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis")),
        Placed(WidgetType.OPEN, WidgetSize.L, 0, 2, 2),
    )),

    // Réunion : piloter l'appel en cours. Affiché seulement pendant un appel.
    CALL_CADRAN(R.string.template_call, R.string.template_call_desc, listOf(
        Placed(WidgetType.CALL, WidgetSize.XL, 0, 0, 0),
        Placed(WidgetType.NEXT, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.AUDIO, WidgetSize.L, 0, 2, 2),
    ), listed = false),

    // Maison : la tablette sans ordinateur. Rien qui lise le PC : l'heure, la météo et les transports se lisent seuls.
    HOUSE(R.string.template_house, R.string.template_house_desc, listOf(
        Placed(WidgetType.CLOCK, WidgetSize.M, 0, 0, 0),
        Placed(WidgetType.WEATHER, WidgetSize.M, 0, 2, 0),
        Placed(WidgetType.TRANSPORT, WidgetSize.L23, 0, 0, 1),
        Placed(WidgetType.HA, WidgetSize.L23, 0, 2, 1),
    ), listed = false);

    /** Modèle d'un rôle (Deck, Réunion) : une page pour agir, pas un Accueil (ni héros ni ligne du jour). */
    val role get() = this == DECK_CADRAN || this == CALL_CADRAN

    /** Les widgets du modèle recopiés sur la page `page` (profil à ajouter). */
    fun widgets(page: Int) = items.map { it.copy().also { c -> c.page = page } }

    fun build() = HomeLayout(widgets(0).toMutableList(), 1)
}
