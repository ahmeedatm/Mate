package com.unowhy.unomate.ui.home

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.unowhy.unomate.R
import com.unowhy.unomate.net.HomeFeatures
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
 * Tailles en cases (largeur × hauteur). `L3` (3 × 2), `T3` (3 × 4) et `FULL` (4 × 4, tout l'écran) ne sont proposées qu'aux
 * tableaux (Agents, Jira, GitLab, Pipelines, Ouvertes, Son) : ceux-là ont de quoi remplir la place.
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
    FULL(4, 4, R.string.widget_size_full),
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
    CLOCK("clock", R.string.widget_clock, R.string.widget_clock_desc, R.drawable.ic_clock, listOf(WidgetSize.L, WidgetSize.XL, WidgetSize.S, WidgetSize.M, WidgetSize.W), false),
    NEXT("next", R.string.widget_next, R.string.widget_next_desc, R.drawable.ic_event, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.V, WidgetSize.S), true),
    AGENDA("agenda", R.string.widget_agenda, R.string.widget_agenda_desc, R.drawable.ic_event, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.M, WidgetSize.V), true),
    TASKS("tasks", R.string.widget_tasks, R.string.widget_tasks_desc, R.drawable.ic_tasks, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.V, WidgetSize.M), true),
    FOCUS("focus", R.string.widget_focus, R.string.widget_focus_desc, R.drawable.ic_timer, listOf(WidgetSize.S, WidgetSize.V, WidgetSize.L), false),
    WATER("water", R.string.widget_water, R.string.widget_water_desc, R.drawable.ic_water, listOf(WidgetSize.S, WidgetSize.M), false),
    AUDIO("audio", R.string.widget_audio, R.string.widget_audio_desc, R.drawable.ic_volume, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S, WidgetSize.W), true),
    MEDIA("media", R.string.widget_media, R.string.widget_media_desc, R.drawable.ic_play, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    PERF("perf", R.string.widget_perf, R.string.widget_perf_desc, R.drawable.ic_speed, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.W), true),
    KEYS("keys", R.string.widget_keys, R.string.widget_keys_desc, R.drawable.ic_deck, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L, WidgetSize.V, WidgetSize.XL), true),
    WEATHER("weather", R.string.widget_weather, R.string.widget_weather_desc, R.drawable.ic_cloud, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), false),
    NOTE("note", R.string.widget_note, R.string.widget_note_desc, R.drawable.ic_edit, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L), false),
    HERO("hero", R.string.widget_hero, R.string.widget_hero_desc, R.drawable.ic_clock, listOf(WidgetSize.W), false),
    DAY("day", R.string.widget_day, R.string.widget_day_desc, R.drawable.ic_event, listOf(WidgetSize.W), false),
    DAYLINE("dayline", R.string.widget_dayline, R.string.widget_dayline_desc, R.drawable.ic_alarm, listOf(WidgetSize.W), false),
    AGENTS("agents", R.string.widget_agents, R.string.widget_agents_desc, R.drawable.ic_agents, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.V, WidgetSize.XL, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), true),
    MIXER("mixer", R.string.widget_mixer, R.string.widget_mixer_desc, R.drawable.ic_wave, listOf(WidgetSize.XL, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), true),
    OPEN("open", R.string.widget_open, R.string.widget_open_desc, R.drawable.ic_display, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), true),
    APPS("apps", R.string.widget_apps, R.string.widget_apps_desc, R.drawable.ic_tabs, listOf(WidgetSize.L, WidgetSize.M, WidgetSize.XL, WidgetSize.W, WidgetSize.V), true),
    DISCORD("discord", R.string.widget_discord, R.string.widget_discord_desc, R.drawable.ic_call, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    MEETING("meeting", R.string.widget_meeting, R.string.widget_meeting_desc, R.drawable.ic_videocam, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    PAD("pad", R.string.widget_pad, R.string.widget_pad_desc, R.drawable.ic_tablet, listOf(WidgetSize.XL, WidgetSize.L), true),
    CALL("call", R.string.widget_call, R.string.widget_call_desc, R.drawable.ic_videocam, listOf(WidgetSize.XL, WidgetSize.L), true),
    PRESENT("present", R.string.widget_present, R.string.widget_present_desc, R.drawable.ic_tile_slideshow, listOf(WidgetSize.XL, WidgetSize.L), true),
    JIRA("jira", R.string.widget_jira, R.string.widget_jira_desc, R.drawable.ic_dashboard, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), true),
    GITLAB("gitlab", R.string.widget_gitlab, R.string.widget_gitlab_desc, R.drawable.ic_merge, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), true),
    CHECKLIST("checklist", R.string.widget_checklist, R.string.widget_checklist_desc, R.drawable.ic_tile_check_circle, listOf(WidgetSize.L, WidgetSize.T), false),
    AWAY("away", R.string.widget_away, R.string.widget_away_desc, R.drawable.ic_cafe, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    PIPELINES("pipelines", R.string.widget_pipelines, R.string.widget_pipelines_desc, R.drawable.ic_tile_rocket_launch, listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), true);

    /** Sans fond de carte, posé à même l'écran : les tubes du thème Rétro y passent derrière un voile. */
    val bare get() = this == HERO || this == DAY || this == DAYLINE

    /** Taille de l'exemplaire agrandi (widget maintenu, [WidgetZoom]) : la plus grande, la plus large à surface égale. */
    val zoomSize get() = sizes.maxWith(compareBy<WidgetSize>({ it.w * it.h }, { it.w }))

    /** Maintenir le widget l'agrandit : pas ceux sans carte (héros, journée, ligne du jour), rien de plus à y voir. */
    val zoomable get() = !bare

    /**
     * Proposé dans le catalogue de l'ordinateur actif : Agents, Jira, GitLab et Pipelines seulement s'il les offre (Agents :
     * Mac avec Claude Code), Ouvertes, Son et Pavé seulement si leur plugin n'est pas coupé (écran des plugins).
     */
    fun offered(features: HomeFeatures) = when (this) {
        AGENTS -> features.agents
        JIRA -> features.jira
        GITLAB, PIPELINES -> features.gitlab
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
private const val LEGACY_FIRST = "Bureau"

/**
 * Disposition complète : widgets et profils. Un profil = une page ; `names[i]` null = pas encore de nom ([nameMissing]
 * lui en donne un, l'interface passe les textes : le modèle ne touche à aucune ressource).
 * Rôles (Deck V2) : `homeProfile` (Accueil, jamais supprimé), `deckProfile` (Deck, -1 s'il a été supprimé), `callProfile`
 * (Réunion : jamais supprimé, absent de la barre et du balayage hors appel). `rolesSet` : rôles déjà posés une fois
 * ([ensureRoles] ne recrée pas un Deck supprimé).
 */
class HomeLayout(val widgets: MutableList<Placed>, val names: MutableList<String?>) {

    /** Profils sans nom : garde les appels qui ne comptent que des pages. */
    constructor(widgets: MutableList<Placed>, pages: Int) : this(widgets, MutableList<String?>(pages.coerceAtLeast(1)) { null })

    var homeProfile = 0
    var deckProfile = -1
    var callProfile = -1
    var rolesSet = false

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
    }

    fun copy() = HomeLayout(widgets.map { it.copy() }.toMutableList(), names.toMutableList()).also {
        it.homeProfile = homeProfile
        it.deckProfile = deckProfile
        it.callProfile = callProfile
        it.rolesSet = rolesSet
        it.icons.clear()
        it.icons += icons
    }

    /**
     * Pose une fois les rôles : Réunion en dernier, depuis son modèle (affiché seulement pendant un appel). De base, un
     * seul écran : l'Accueil ; d'autres seulement si l'utilisateur en ajoute. Vrai si la disposition a changé.
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

    /** Le dernier profil reste ; une fois les rôles posés, l'Accueil et Réunion aussi. */
    fun canRemove(index: Int) =
        names.size > 1 && index in names.indices && !(rolesSet && (index == homeProfile || index == callProfile))

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
     * au modèle `pattern`, même numéro, et « Bureau » de l'Accueil en `first`. Vrai si un nom a été posé.
     */
    fun nameMissing(first: String, pattern: String): Boolean {
        var named = false
        if (rolesSet && names.getOrNull(homeProfile) == LEGACY_FIRST && first !in names) {
            names[homeProfile] = first
            named = true
        }
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

    /** Remet le profil `from` à la place `to` ; ses widgets et le marquage de jeu le suivent. */
    fun moveProfile(from: Int, to: Int) {
        if (from == to || from !in names.indices || to !in names.indices) return
        syncIcons()
        names.add(to, names.removeAt(from))
        icons.add(to, icons.removeAt(from))
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
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return raw?.let { runCatching { parse(it) }.getOrNull() } ?: Template.WORK_CADRAN.build()
        }

        /** Ce widget est-il sur l'Accueil (modèle par défaut si rien n'est enregistré) ? */
        fun has(context: Context, type: WidgetType) = load(context).widgets.any { it.type == type }

        fun save(context: Context, layout: HomeLayout) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, layout.toJson()).apply()
        }

        /** Disposition enregistrée telle quelle (null : jamais enregistrée, le modèle par défaut s'applique). */
        fun raw(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

        /** Remet une disposition lue par [raw] ; null efface l'enregistrée. */
        fun restore(context: Context, raw: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
        }

        /** Disposition invalide (widget inconnu, chevauchement) : on la refuse en entier, le modèle la remplace. */
        private fun parse(raw: String): HomeLayout {
            val json = JSONObject(raw)
            val saved = json.optJSONArray("names")
            val names = if (saved != null) {
                MutableList(saved.length().coerceAtLeast(1)) { i -> if (saved.isNull(i)) null else saved.getString(i) }
            } else {
                MutableList<String?>(json.getInt("pages").coerceAtLeast(1)) { null }
            }
            val layout = HomeLayout(mutableListOf(), names)
            json.optJSONArray("icons")?.let { saved -> names.indices.forEach { i -> if (i < saved.length() && !saved.isNull(i)) layout.setIcon(i, saved.getString(i)) } }
            layout.homeProfile = json.optInt("home", 0).takeIf { it in names.indices } ?: 0
            layout.deckProfile = json.optInt("deck", -1).takeIf { it in names.indices } ?: -1
            layout.callProfile = json.optInt("call", -1).takeIf { it in names.indices } ?: -1
            layout.rolesSet = json.optBoolean("roles", false)
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
 * profil (Réunion ne l'est pas : il n'existe qu'une fois, posé par [HomeLayout.ensureRoles]).
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
    ), listed = false);

    /** Modèle d'un rôle (Deck, Réunion) : une page pour agir, pas un Accueil (ni héros ni ligne du jour). */
    val role get() = this == DECK_CADRAN || this == CALL_CADRAN

    /** Les widgets du modèle recopiés sur la page `page` (profil à ajouter). */
    fun widgets(page: Int) = items.map { it.copy().also { c -> c.page = page } }

    fun build() = HomeLayout(widgets(0).toMutableList(), 1)
}
