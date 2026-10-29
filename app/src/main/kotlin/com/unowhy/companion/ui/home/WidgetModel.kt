package com.unowhy.companion.ui.home

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.unowhy.companion.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accueil à widgets : une grille de [COLS] × [ROWS] cases par profil (une page = un profil nommé, sans limite), des
 * widgets de tailles fixes calés sur la grille. La disposition est propre à la tablette (préférences), quel que soit
 * l'ordinateur.
 */
const val COLS = 4
const val ROWS = 4

/** Tailles en cases (largeur × hauteur). */
enum class WidgetSize(val w: Int, val h: Int, @StringRes val label: Int) {
    S(1, 1, R.string.widget_size_s),
    M(2, 1, R.string.widget_size_m),
    L(2, 2, R.string.widget_size_l),
    T(2, 4, R.string.widget_size_t),
    W(4, 1, R.string.widget_size_w),
    XL(4, 2, R.string.widget_size_xl),
    V(1, 2, R.string.widget_size_v),
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
    AGENDA("agenda", R.string.widget_agenda, R.string.widget_agenda_desc, R.drawable.ic_event, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.M), true),
    TASKS("tasks", R.string.widget_tasks, R.string.widget_tasks_desc, R.drawable.ic_tasks, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.V, WidgetSize.M), true),
    FOCUS("focus", R.string.widget_focus, R.string.widget_focus_desc, R.drawable.ic_timer, listOf(WidgetSize.S, WidgetSize.V, WidgetSize.L), false),
    WATER("water", R.string.widget_water, R.string.widget_water_desc, R.drawable.ic_water, listOf(WidgetSize.S, WidgetSize.M), false),
    AUDIO("audio", R.string.widget_audio, R.string.widget_audio_desc, R.drawable.ic_volume, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    MEDIA("media", R.string.widget_media, R.string.widget_media_desc, R.drawable.ic_play, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    PERF("perf", R.string.widget_perf, R.string.widget_perf_desc, R.drawable.ic_speed, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.W), true),
    KEYS("keys", R.string.widget_keys, R.string.widget_keys_desc, R.drawable.ic_deck, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L, WidgetSize.V, WidgetSize.XL), true),
    WEATHER("weather", R.string.widget_weather, R.string.widget_weather_desc, R.drawable.ic_cloud, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), false),
    NOTE("note", R.string.widget_note, R.string.widget_note_desc, R.drawable.ic_edit, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L), false),
    HERO("hero", R.string.widget_hero, R.string.widget_hero_desc, R.drawable.ic_clock, listOf(WidgetSize.W), false),
    DAYLINE("dayline", R.string.widget_dayline, R.string.widget_dayline_desc, R.drawable.ic_alarm, listOf(WidgetSize.W), false),
    AGENTS("agents", R.string.widget_agents, R.string.widget_agents_desc, R.drawable.ic_agents, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.V, WidgetSize.XL), true),
    MIXER("mixer", R.string.widget_mixer, R.string.widget_mixer_desc, R.drawable.ic_wave, listOf(WidgetSize.XL), true),
    OPEN("open", R.string.widget_open, R.string.widget_open_desc, R.drawable.ic_display, listOf(WidgetSize.XL, WidgetSize.L), true),
    APPS("apps", R.string.widget_apps, R.string.widget_apps_desc, R.drawable.ic_tabs, listOf(WidgetSize.L, WidgetSize.M, WidgetSize.XL), true),
    DISCORD("discord", R.string.widget_discord, R.string.widget_discord_desc, R.drawable.ic_call, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true),
    MEETING("meeting", R.string.widget_meeting, R.string.widget_meeting_desc, R.drawable.ic_videocam, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), true);

    /** Sans fond de carte, posé à même l'écran : les tubes du thème Rétro y passent derrière un voile. */
    val bare get() = this == HERO || this == DAYLINE

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Un widget posé : page, case en haut à gauche, taille, réglages propres (touches choisies, ville, texte). */
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

/**
 * Disposition complète : widgets et profils. Un profil = une page ; `names[i]` null = pas encore de nom ([nameMissing]
 * lui en donne un, l'interface passe les textes : le modèle ne touche à aucune ressource). `gameProfile` : le profil de jeu (au plus un), -1 si aucun.
 */
class HomeLayout(val widgets: MutableList<Placed>, val names: MutableList<String?>, @set:JvmName("assignGameProfile") var gameProfile: Int = -1) {

    /** Profils sans nom : garde les appels qui ne comptent que des pages. */
    constructor(widgets: MutableList<Placed>, pages: Int) : this(widgets, MutableList<String?>(pages.coerceAtLeast(1)) { null })

    val pages get() = names.size

    fun copy() = HomeLayout(widgets.map { it.copy() }.toMutableList(), names.toMutableList(), gameProfile)

    /** Ajoute un profil à la fin et renvoie son index ; `items` sont recopiés sur sa page. */
    fun addProfile(name: String?, items: List<Placed> = emptyList()): Int {
        names += name
        val index = names.size - 1
        widgets += items.map { it.copy().also { c -> c.page = index } }
        return index
    }

    fun renameProfile(index: Int, name: String) {
        names[index] = name
    }

    /** Premier nom « Profil N » (N ≥ 2) que aucun profil ne porte ; `pattern` est le modèle de nom (« Profil %d »). */
    fun freeName(pattern: String): String =
        generateSequence(2) { it + 1 }.map { pattern.format(it) }.first { it !in names }

    /** Donne un nom à chaque profil qui n'en a pas : `first` au premier, le premier numéro libre aux autres. Vrai si un nom a été posé. */
    fun nameMissing(first: String, pattern: String): Boolean {
        var named = false
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
        names.add(to, names.removeAt(from))
        fun moved(p: Int) = when {
            p == from -> to
            from < to && p in from + 1..to -> p - 1
            from > to && p in to until from -> p + 1
            else -> p
        }
        widgets.forEach { it.page = moved(it.page) }
        if (gameProfile >= 0) gameProfile = moved(gameProfile)
    }

    /** Retire le profil et ses widgets, les suivants reculent d'une page. Faux (rien ne change) pour le dernier profil. */
    fun removeProfile(index: Int): Boolean {
        if (names.size <= 1 || index !in names.indices) return false
        names.removeAt(index)
        widgets.removeAll { it.page == index }
        widgets.filter { it.page > index }.forEach { it.page-- }
        gameProfile = when {
            gameProfile == index -> -1
            gameProfile > index -> gameProfile - 1
            else -> gameProfile
        }
        return true
    }

    /** Un seul profil de jeu : le nouveau remplace l'ancien, -1 n'en laisse aucun. */
    fun setGameProfile(index: Int) {
        gameProfile = index
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

    /** Première case libre (lecture de gauche à droite, de haut en bas) où `size` tient, ou null. */
    fun freeSpot(page: Int, size: WidgetSize, ignore: Placed? = null): Pair<Int, Int>? {
        for (r in 0..ROWS - size.h) for (c in 0..COLS - size.w) if (fits(page, c, r, size, ignore)) return c to r
        return null
    }

    fun toJson(): String = JSONObject()
        .put("v", 2)
        .put("pages", pages)
        .put("names", JSONArray().apply { names.forEach { put(it ?: JSONObject.NULL) } })
        .put("game", gameProfile)
        .put("widgets", JSONArray().apply {
            widgets.forEach {
                put(JSONObject().put("t", it.type.key).put("s", it.size.name).put("p", it.page).put("c", it.col).put("r", it.row).put("cfg", it.config))
            }
        })
        .toString()

    companion object {
        private const val PREFS = "companion"

        /** Les thèmes Cadran ont leur propre disposition : leurs lignes inégales ne vont pas aux autres thèmes. */
        fun key(cadran: Boolean) = if (cadran) "home_layout_cadran" else "home_layout"

        fun load(context: Context, cadran: Boolean): HomeLayout {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(cadran), null)
            return raw?.let { runCatching { parse(it) }.getOrNull() } ?: Template.defaultFor(cadran).build()
        }

        /** Ce widget est-il sur l'Accueil (disposition du thème, modèle par défaut si rien n'est enregistré) ? */
        fun has(context: Context, cadran: Boolean, type: WidgetType) = load(context, cadran).widgets.any { it.type == type }

        fun save(context: Context, layout: HomeLayout, cadran: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(cadran), layout.toJson()).apply()
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
            layout.gameProfile = json.optInt("game", -1).takeIf { it in names.indices } ?: -1
            val list = json.getJSONArray("widgets")
            for (i in 0 until list.length()) {
                val w = list.getJSONObject(i)
                val type = WidgetType.of(w.getString("t")) ?: continue
                val size = WidgetSize.valueOf(w.getString("s")).takeIf { it in type.sizes } ?: type.sizes.first()
                val placed = Placed(type, size, w.getInt("p"), w.getInt("c"), w.getInt("r"), w.optJSONObject("cfg") ?: JSONObject())
                require(placed.page < layout.pages && layout.fits(placed.page, placed.col, placed.row, size))
                layout.widgets += placed
            }
            return layout
        }
    }
}

/** Modèles prêts à l'emploi : le défaut (Travail) ressemble à l'ancien Accueil. */
enum class Template(@StringRes val title: Int, @StringRes val description: Int, private val items: List<Placed>, val cadran: Boolean = false) {
    WORK(R.string.template_work, R.string.template_work_desc, listOf(
        Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0),
        Placed(WidgetType.AGENDA, WidgetSize.L, 0, 2, 0),
        Placed(WidgetType.NEXT, WidgetSize.M, 0, 0, 2),
        Placed(WidgetType.TASKS, WidgetSize.L, 0, 2, 2),
        Placed(WidgetType.FOCUS, WidgetSize.S, 0, 0, 3),
        Placed(WidgetType.WATER, WidgetSize.S, 0, 1, 3),
    )),
    HOME(R.string.template_home, R.string.template_home_desc, listOf(
        Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0),
        Placed(WidgetType.WEATHER, WidgetSize.M, 0, 2, 0),
        Placed(WidgetType.AUDIO, WidgetSize.M, 0, 2, 1),
        Placed(WidgetType.KEYS, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.MEDIA, WidgetSize.M, 0, 2, 2),
        Placed(WidgetType.FOCUS, WidgetSize.S, 0, 2, 3),
        Placed(WidgetType.WATER, WidgetSize.S, 0, 3, 3),
    )),
    STANDBY(R.string.template_standby, R.string.template_standby_desc, listOf(
        Placed(WidgetType.CLOCK, WidgetSize.XL, 0, 0, 0),
        Placed(WidgetType.NEXT, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.FOCUS, WidgetSize.S, 0, 2, 2),
        Placed(WidgetType.WATER, WidgetSize.M, 0, 2, 3),
    )),
    WORK_CADRAN(R.string.template_work, R.string.template_work_cadran_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.NEXT, WidgetSize.V, 0, 0, 2),
        Placed(WidgetType.TASKS, WidgetSize.V, 0, 1, 2),
        Placed(WidgetType.FOCUS, WidgetSize.V, 0, 2, 2),
        Placed(WidgetType.KEYS, WidgetSize.V, 0, 3, 2),
    ), cadran = true),
    HOME_CADRAN(R.string.template_home, R.string.template_home_cadran_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
        Placed(WidgetType.KEYS, WidgetSize.L, 0, 0, 2),
        Placed(WidgetType.MEDIA, WidgetSize.M, 0, 2, 2),
        Placed(WidgetType.AUDIO, WidgetSize.M, 0, 2, 3),
    ), cadran = true),
    STANDBY_CADRAN(R.string.template_standby, R.string.template_standby_cadran_desc, listOf(
        Placed(WidgetType.HERO, WidgetSize.W, 0, 0, 0),
        Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
    ), cadran = true);

    /** Les widgets du modèle recopiés sur la page `page` (profil à ajouter). */
    fun widgets(page: Int) = items.map { it.copy().also { c -> c.page = page } }

    fun build() = HomeLayout(widgets(0).toMutableList(), 1)

    companion object {
        /** Modèle d'un premier lancement : Travail (celui de la famille Cadran, quand il existe). */
        fun defaultFor(cadran: Boolean) = entries.firstOrNull { it.cadran == cadran } ?: WORK
    }
}
