package com.unowhy.companion.ui.home

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.unowhy.companion.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accueil à widgets : une grille de [COLS] × [ROWS] cases par page (jusqu'à [MAX_PAGES] pages), des widgets de
 * tailles fixes calés sur la grille. La disposition est propre à la tablette (préférences), quel que soit l'ordinateur.
 */
const val COLS = 4
const val ROWS = 4
const val MAX_PAGES = 3

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
    CLOCK("clock", R.string.widget_clock, R.string.widget_clock_desc, R.drawable.ic_clock, listOf(WidgetSize.L, WidgetSize.M, WidgetSize.W, WidgetSize.XL), false),
    NEXT("next", R.string.widget_next, R.string.widget_next_desc, R.drawable.ic_event, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.V), true),
    AGENDA("agenda", R.string.widget_agenda, R.string.widget_agenda_desc, R.drawable.ic_event, listOf(WidgetSize.L, WidgetSize.T), true),
    TASKS("tasks", R.string.widget_tasks, R.string.widget_tasks_desc, R.drawable.ic_tasks, listOf(WidgetSize.L, WidgetSize.T, WidgetSize.V), true),
    FOCUS("focus", R.string.widget_focus, R.string.widget_focus_desc, R.drawable.ic_timer, listOf(WidgetSize.S, WidgetSize.M, WidgetSize.V), false),
    WATER("water", R.string.widget_water, R.string.widget_water_desc, R.drawable.ic_water, listOf(WidgetSize.S, WidgetSize.M), false),
    AUDIO("audio", R.string.widget_audio, R.string.widget_audio_desc, R.drawable.ic_volume, listOf(WidgetSize.M), true),
    MEDIA("media", R.string.widget_media, R.string.widget_media_desc, R.drawable.ic_play, listOf(WidgetSize.M), true),
    KEYS("keys", R.string.widget_keys, R.string.widget_keys_desc, R.drawable.ic_deck, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L, WidgetSize.V), true),
    WEATHER("weather", R.string.widget_weather, R.string.widget_weather_desc, R.drawable.ic_cloud, listOf(WidgetSize.M, WidgetSize.S), false),
    NOTE("note", R.string.widget_note, R.string.widget_note_desc, R.drawable.ic_edit, listOf(WidgetSize.M, WidgetSize.S, WidgetSize.L), false),
    HERO("hero", R.string.widget_hero, R.string.widget_hero_desc, R.drawable.ic_clock, listOf(WidgetSize.W), false),
    DAYLINE("dayline", R.string.widget_dayline, R.string.widget_dayline_desc, R.drawable.ic_alarm, listOf(WidgetSize.W), false);

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

/** Disposition complète : widgets et nombre de pages. */
class HomeLayout(val widgets: MutableList<Placed>, var pages: Int) {

    fun copy() = HomeLayout(widgets.map { it.copy() }.toMutableList(), pages)

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

    /** Pages vides retirées (la première reste), widgets renumérotés. */
    fun compact() {
        val used = (0 until pages).filter { p -> p == 0 || widgets.any { it.page == p } }
        widgets.forEach { it.page = used.indexOf(it.page) }
        pages = used.size.coerceAtLeast(1)
    }

    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("pages", pages)
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

        fun save(context: Context, layout: HomeLayout, cadran: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(cadran), layout.toJson()).apply()
        }

        /** Disposition invalide (widget inconnu, chevauchement) : on la refuse en entier, le modèle la remplace. */
        private fun parse(raw: String): HomeLayout {
            val json = JSONObject(raw)
            val layout = HomeLayout(mutableListOf(), json.getInt("pages").coerceIn(1, MAX_PAGES))
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
        Placed(WidgetType.FOCUS, WidgetSize.M, 0, 2, 2),
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

    fun build() = HomeLayout(items.map { it.copy() }.toMutableList(), 1)

    companion object {
        /** Modèle d'un premier lancement : Travail (celui de la famille Cadran, quand il existe). */
        fun defaultFor(cadran: Boolean) = entries.firstOrNull { it.cadran == cadran } ?: WORK
    }
}
