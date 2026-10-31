package app.mate.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.mate.R

/**
 * Icônes proposées pour un écran (boîte des écrans) : clé enregistrée dans la disposition ([HomeLayout.icons]) →
 * icône et nom lu par TalkBack. Sans choix, l'icône du rôle : maison pour l'Accueil, caméra pour Réunion. Les clés sont enregistrées : ne jamais en renommer une.
 */
object ProfileIcons {
    data class Icon(val key: String, @DrawableRes val drawable: Int, @StringRes val label: Int)

    val ALL: List<Icon> = listOf(
        Icon("home", R.drawable.ic_home, R.string.icon_home),
        Icon("work", R.drawable.ic_work, R.string.icon_work),
        Icon("dashboard", R.drawable.ic_dashboard, R.string.icon_dashboard),
        Icon("widgets", R.drawable.ic_widgets, R.string.icon_widgets),
        Icon("apps", R.drawable.ic_apps, R.string.icon_apps),
        Icon("sound", R.drawable.ic_volume, R.string.icon_sound),
        Icon("laptop", R.drawable.ic_laptop, R.string.icon_laptop),
        Icon("display", R.drawable.ic_display, R.string.icon_display),
        Icon("tablet", R.drawable.ic_tablet, R.string.icon_tablet),
        Icon("keyboard", R.drawable.ic_tile_keyboard, R.string.icon_keyboard),
        Icon("code", R.drawable.ic_code, R.string.icon_code),
        Icon("agents", R.drawable.ic_agents, R.string.icon_agents),
        Icon("chat", R.drawable.ic_chat, R.string.icon_chat),
        Icon("mail", R.drawable.ic_mail, R.string.icon_mail),
        Icon("call", R.drawable.ic_call, R.string.icon_call),
        Icon("video", R.drawable.ic_videocam, R.string.icon_video),
        Icon("mic", R.drawable.ic_mic, R.string.icon_mic),
        Icon("present", R.drawable.ic_tile_present, R.string.icon_present),
        Icon("event", R.drawable.ic_event, R.string.icon_event),
        Icon("tasks", R.drawable.ic_tasks, R.string.icon_tasks),
        Icon("doc", R.drawable.ic_doc, R.string.icon_doc),
        Icon("timer", R.drawable.ic_timer, R.string.icon_timer),
        Icon("alarm", R.drawable.ic_alarm, R.string.icon_alarm),
        Icon("book", R.drawable.ic_book, R.string.icon_book),
        Icon("school", R.drawable.ic_school, R.string.icon_school),
        Icon("idea", R.drawable.ic_lightbulb, R.string.icon_idea),
        Icon("music", R.drawable.ic_wave, R.string.icon_music),
        Icon("note", R.drawable.ic_music_note, R.string.icon_note),
        Icon("headphones", R.drawable.ic_headphones, R.string.icon_headphones),
        Icon("camera", R.drawable.ic_camera, R.string.icon_camera),
        Icon("palette", R.drawable.ic_palette, R.string.icon_palette),
        Icon("image", R.drawable.ic_image, R.string.icon_image),
        Icon("folder", R.drawable.ic_folder, R.string.icon_folder),
        Icon("speed", R.drawable.ic_speed, R.string.icon_speed),
        Icon("bolt", R.drawable.ic_bolt, R.string.icon_bolt),
        Icon("fitness", R.drawable.ic_fitness, R.string.icon_fitness),
        Icon("cafe", R.drawable.ic_cafe, R.string.icon_cafe),
        Icon("water", R.drawable.ic_water, R.string.icon_water),
        Icon("cart", R.drawable.ic_cart, R.string.icon_cart),
        Icon("flight", R.drawable.ic_flight, R.string.icon_flight),
        Icon("place", R.drawable.ic_place, R.string.icon_place),
        Icon("sun", R.drawable.ic_wx_clear, R.string.icon_sun),
        Icon("cloud", R.drawable.ic_cloud, R.string.icon_cloud),
        Icon("night", R.drawable.ic_tile_bedtime, R.string.icon_night),
        Icon("star", R.drawable.ic_star, R.string.icon_star),
        Icon("heart", R.drawable.ic_favorite, R.string.icon_heart),
        Icon("lock", R.drawable.ic_lock, R.string.icon_lock),
    )

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
