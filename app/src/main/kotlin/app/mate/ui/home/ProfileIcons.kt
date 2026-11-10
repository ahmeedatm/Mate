package app.mate.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.mate.R

/**
 * Icônes proposées pour un écran (boîte des écrans), rangées par thème ([GROUPS]) : clé enregistrée dans la disposition ([HomeLayout.icons]) →
 * icône et nom lu par TalkBack. Sans choix, l'icône du rôle : maison pour l'Accueil, caméra pour Réunion. Les clés sont enregistrées : ne jamais en renommer une.
 */
object ProfileIcons {
    data class Icon(val key: String, @DrawableRes val drawable: Int, @StringRes val label: Int)

    data class Group(@StringRes val title: Int, val icons: List<Icon>)

    // Constructeur à liste variable : chaque thème s'écrit comme une liste.
    private fun Group(@StringRes title: Int, vararg icons: Icon) = Group(title, icons.toList())

    /** Thèmes de la grille, dans l'ordre affiché ; [ALL] les met bout à bout. */
    val GROUPS: List<Group> = listOf(
        Group(
            R.string.icon_group_office,
            Icon("home", R.drawable.ic_home, R.string.icon_home),
            Icon("work", R.drawable.ic_work, R.string.icon_work),
            Icon("dashboard", R.drawable.ic_dashboard, R.string.icon_dashboard),
            Icon("widgets", R.drawable.ic_widgets, R.string.icon_widgets),
            Icon("apps", R.drawable.ic_apps, R.string.icon_apps),
            Icon("laptop", R.drawable.ic_laptop, R.string.icon_laptop),
            Icon("display", R.drawable.ic_display, R.string.icon_display),
            Icon("tablet", R.drawable.ic_tablet, R.string.icon_tablet),
            Icon("keyboard", R.drawable.ic_tile_keyboard, R.string.icon_keyboard),
            Icon("code", R.drawable.ic_code, R.string.icon_code),
            Icon("agents", R.drawable.ic_agents, R.string.icon_agents),
            Icon("doc", R.drawable.ic_doc, R.string.icon_doc),
            Icon("folder", R.drawable.ic_folder, R.string.icon_folder),
            Icon("tasks", R.drawable.ic_tasks, R.string.icon_tasks),
            Icon("event", R.drawable.ic_event, R.string.icon_event),
            Icon("terminal", R.drawable.ic_terminal, R.string.icon_terminal),
            Icon("stats", R.drawable.ic_tile_bar_chart, R.string.icon_stats),
            Icon("team", R.drawable.ic_tile_groups, R.string.icon_team),
            Icon("memo", R.drawable.ic_tile_sticky_note_2, R.string.icon_memo),
            Icon("calc", R.drawable.ic_calculate, R.string.icon_calc),
            Icon("inbox", R.drawable.ic_tile_inbox, R.string.icon_inbox),
            Icon("rocket", R.drawable.ic_tile_rocket_launch, R.string.icon_rocket),
            Icon("science", R.drawable.ic_tile_science, R.string.icon_science),
            Icon("tools", R.drawable.ic_tile_build, R.string.icon_tools),
            Icon("print", R.drawable.ic_tile_print, R.string.icon_print),
        ),
        Group(
            R.string.icon_group_talk,
            Icon("chat", R.drawable.ic_chat, R.string.icon_chat),
            Icon("mail", R.drawable.ic_mail, R.string.icon_mail),
            Icon("call", R.drawable.ic_call, R.string.icon_call),
            Icon("video", R.drawable.ic_videocam, R.string.icon_video),
            Icon("mic", R.drawable.ic_mic, R.string.icon_mic),
            Icon("present", R.drawable.ic_tile_present, R.string.icon_present),
            Icon("announce", R.drawable.ic_tile_campaign, R.string.icon_announce),
        ),
        Group(
            R.string.icon_group_media,
            Icon("music", R.drawable.ic_wave, R.string.icon_music),
            Icon("note", R.drawable.ic_music_note, R.string.icon_note),
            Icon("headphones", R.drawable.ic_headphones, R.string.icon_headphones),
            Icon("sound", R.drawable.ic_volume, R.string.icon_sound),
            Icon("camera", R.drawable.ic_camera, R.string.icon_camera),
            Icon("image", R.drawable.ic_image, R.string.icon_image),
            Icon("palette", R.drawable.ic_palette, R.string.icon_palette),
            Icon("valorant", R.drawable.ic_valorant, R.string.icon_valorant),
            Icon("movie", R.drawable.ic_movie, R.string.icon_movie),
            Icon("tv", R.drawable.ic_tv, R.string.icon_tv),
            Icon("radio", R.drawable.ic_radio, R.string.icon_radio),
            Icon("podcast", R.drawable.ic_podcast, R.string.icon_podcast),
            Icon("brush", R.drawable.ic_brush, R.string.icon_brush),
            Icon("piano", R.drawable.ic_piano, R.string.icon_piano),
            Icon("gamepad", R.drawable.ic_esports, R.string.icon_gamepad),
        ),
        Group(
            R.string.icon_group_house,
            Icon("sofa", R.drawable.ic_chair, R.string.icon_sofa),
            Icon("bed", R.drawable.ic_bed, R.string.icon_bed),
            Icon("kitchen", R.drawable.ic_kitchen, R.string.icon_kitchen),
            Icon("meal", R.drawable.ic_tile_restaurant, R.string.icon_meal),
            Icon("thermostat", R.drawable.ic_thermostat, R.string.icon_thermostat),
            Icon("router", R.drawable.ic_router, R.string.icon_router),
            Icon("shower", R.drawable.ic_shower, R.string.icon_shower),
            Icon("laundry", R.drawable.ic_laundry, R.string.icon_laundry),
            Icon("pets", R.drawable.ic_pets, R.string.icon_pets),
            Icon("kids", R.drawable.ic_child_care, R.string.icon_kids),
            Icon("garden", R.drawable.ic_yard, R.string.icon_garden),
            Icon("garage", R.drawable.ic_garage, R.string.icon_garage),
        ),
        Group(
            R.string.icon_group_health,
            Icon("fitness", R.drawable.ic_fitness, R.string.icon_fitness),
            Icon("cafe", R.drawable.ic_cafe, R.string.icon_cafe),
            Icon("water", R.drawable.ic_water, R.string.icon_water),
            Icon("run", R.drawable.ic_run, R.string.icon_run),
            Icon("bike", R.drawable.ic_bike, R.string.icon_bike),
            Icon("pool", R.drawable.ic_pool, R.string.icon_pool),
            Icon("zen", R.drawable.ic_meditation, R.string.icon_zen),
            Icon("spa", R.drawable.ic_spa, R.string.icon_spa),
            Icon("meds", R.drawable.ic_medication, R.string.icon_meds),
            Icon("soccer", R.drawable.ic_soccer, R.string.icon_soccer),
            Icon("basket", R.drawable.ic_basketball, R.string.icon_basket),
        ),
        Group(
            R.string.icon_group_outing,
            Icon("cart", R.drawable.ic_cart, R.string.icon_cart),
            Icon("flight", R.drawable.ic_flight, R.string.icon_flight),
            Icon("place", R.drawable.ic_place, R.string.icon_place),
            Icon("car", R.drawable.ic_car, R.string.icon_car),
            Icon("train", R.drawable.ic_train, R.string.icon_train),
            Icon("beach", R.drawable.ic_beach, R.string.icon_beach),
            Icon("hike", R.drawable.ic_hiking, R.string.icon_hike),
            Icon("world", R.drawable.ic_public, R.string.icon_world),
            Icon("map", R.drawable.ic_map, R.string.icon_map),
        ),
        Group(
            R.string.icon_group_nature,
            Icon("sun", R.drawable.ic_wx_clear, R.string.icon_sun),
            Icon("cloud", R.drawable.ic_cloud, R.string.icon_cloud),
            Icon("night", R.drawable.ic_tile_bedtime, R.string.icon_night),
            Icon("rain", R.drawable.ic_wx_rain, R.string.icon_rain),
            Icon("snow", R.drawable.ic_wx_snow, R.string.icon_snow),
            Icon("storm", R.drawable.ic_wx_storm, R.string.icon_storm),
            Icon("forest", R.drawable.ic_forest, R.string.icon_forest),
            Icon("flower", R.drawable.ic_florist, R.string.icon_flower),
            Icon("eco", R.drawable.ic_eco, R.string.icon_eco),
        ),
        Group(
            R.string.icon_group_marks,
            Icon("star", R.drawable.ic_star, R.string.icon_star),
            Icon("heart", R.drawable.ic_favorite, R.string.icon_heart),
            Icon("lock", R.drawable.ic_lock, R.string.icon_lock),
            Icon("bolt", R.drawable.ic_bolt, R.string.icon_bolt),
            Icon("speed", R.drawable.ic_speed, R.string.icon_speed),
            Icon("idea", R.drawable.ic_lightbulb, R.string.icon_idea),
            Icon("book", R.drawable.ic_book, R.string.icon_book),
            Icon("school", R.drawable.ic_school, R.string.icon_school),
            Icon("timer", R.drawable.ic_timer, R.string.icon_timer),
            Icon("alarm", R.drawable.ic_alarm, R.string.icon_alarm),
            Icon("flag", R.drawable.ic_tile_flag, R.string.icon_flag),
            Icon("pin", R.drawable.ic_push_pin, R.string.icon_pin),
            Icon("done", R.drawable.ic_tile_check_circle, R.string.icon_done),
            Icon("diamond", R.drawable.ic_diamond, R.string.icon_diamond),
            Icon("trophy", R.drawable.ic_trophy, R.string.icon_trophy),
            Icon("party", R.drawable.ic_celebration, R.string.icon_party),
            Icon("savings", R.drawable.ic_savings, R.string.icon_savings),
            Icon("shield", R.drawable.ic_shield, R.string.icon_shield),
            Icon("key", R.drawable.ic_key, R.string.icon_key),
            Icon("bookmark", R.drawable.ic_bookmark, R.string.icon_bookmark),
        ),
    )

    val ALL: List<Icon> = GROUPS.flatMap { it.icons }

    fun find(key: String?): Icon? = ALL.firstOrNull { it.key == key }

    @DrawableRes
    fun drawable(key: String?): Int? = find(key)?.drawable

    /** Clé de l'icône montrée pour `page` : `choice` (par défaut celle enregistrée), sinon celle de son rôle (null si aucune ne correspond). */
    fun keyOf(layout: HomeLayout, page: Int, choice: String? = layout.iconOf(page)): String? =
        of(layout, page, choice).let { shown -> ALL.firstOrNull { it.drawable == shown }?.key }

    /** Icône de l'écran `page` : `choice` (par défaut celle enregistrée), sinon celle de son rôle. */
    @DrawableRes
    fun of(layout: HomeLayout, page: Int, choice: String? = layout.iconOf(page)): Int = drawable(choice) ?: when (page) {
        layout.homeProfile -> R.drawable.ic_home
        layout.deckProfile -> R.drawable.ic_deck
        layout.callProfile -> R.drawable.ic_videocam
        else -> R.drawable.ic_widgets
    }
}
