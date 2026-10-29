package com.unowhy.companion.ui.home

import androidx.annotation.DrawableRes
import com.unowhy.companion.R

/**
 * Symboles des touches du deck (`symbol` des tuiles, liste TILE_SYMBOLS de backend/src/screens.ts) : icônes vectorielles
 * monochromes, teintées comme le texte, à la place de l'emoji. Un nom inconnu (ordinateur plus récent) : null, l'emoji
 * ou l'initiale prend le relais.
 */
object TileIcons {
    private val icons: Map<String, Int> = mapOf(
        "lock" to R.drawable.ic_lock,
        "screenshot" to R.drawable.ic_tile_screenshot,
        "dnd" to R.drawable.ic_tile_dnd,
        "mic_off" to R.drawable.ic_mic_off,
        "videocam_off" to R.drawable.ic_tile_videocam_off,
        "clipboard" to R.drawable.ic_clipboard,
        "windows" to R.drawable.ic_display,
        "dark_mode" to R.drawable.ic_tile_dark_mode,
        "bedtime" to R.drawable.ic_tile_bedtime,
        "play_pause" to R.drawable.ic_tile_play_pause,
        "skip_next" to R.drawable.ic_skip,
        "skip_previous" to R.drawable.ic_skip_previous,
        "volume_up" to R.drawable.ic_volume,
        "volume_off" to R.drawable.ic_volume_off,
        "present" to R.drawable.ic_tile_present,
        "hand" to R.drawable.ic_tile_hand,
        "call_end" to R.drawable.ic_call_end,
        "keyboard" to R.drawable.ic_tile_keyboard,
        "link" to R.drawable.ic_link,
        "apps" to R.drawable.ic_deck,
        "search" to R.drawable.ic_tile_search,
        "call" to R.drawable.ic_call,
        "alarm" to R.drawable.ic_alarm,
        "videocam" to R.drawable.ic_videocam,
        "event" to R.drawable.ic_event,
        "play" to R.drawable.ic_play,
        "close" to R.drawable.ic_close,
        "add" to R.drawable.ic_add,
        "save" to R.drawable.ic_tile_save,
        "undo" to R.drawable.ic_tile_undo,
        "redo" to R.drawable.ic_tile_redo,
        "folder" to R.drawable.ic_tile_folder,
        "table_chart" to R.drawable.ic_tile_table_chart,
        "filter_alt" to R.drawable.ic_tile_filter_alt,
        "vertical_split" to R.drawable.ic_tile_vertical_split,
        "check_circle" to R.drawable.ic_tile_check_circle,
        "attach_file" to R.drawable.ic_tile_attach_file,
        "receipt_long" to R.drawable.ic_tile_receipt_long,
        "account_balance" to R.drawable.ic_tile_account_balance,
        "palette" to R.drawable.ic_tile_palette,
        "groups" to R.drawable.ic_tile_groups,
        "bar_chart" to R.drawable.ic_tile_bar_chart,
        "slideshow" to R.drawable.ic_tile_slideshow,
        "work" to R.drawable.ic_tile_work,
        "image" to R.drawable.ic_tile_image,
        "thumb_up" to R.drawable.ic_tile_thumb_up,
        "edit_note" to R.drawable.ic_tile_edit_note,
        "mail" to R.drawable.ic_tile_mail,
        "campaign" to R.drawable.ic_tile_campaign,
        "description" to R.drawable.ic_tile_description,
        "home" to R.drawable.ic_tile_home,
        "restaurant" to R.drawable.ic_tile_restaurant,
        "shopping_cart" to R.drawable.ic_tile_shopping_cart,
        "school" to R.drawable.ic_tile_school,
        "fullscreen" to R.drawable.ic_tile_fullscreen,
        "content_copy" to R.drawable.ic_tile_content_copy,
        "content_paste" to R.drawable.ic_tile_content_paste,
        "science" to R.drawable.ic_tile_science,
        "build" to R.drawable.ic_tile_build,
        "code" to R.drawable.ic_tile_code,
        "swap_horiz" to R.drawable.ic_tile_swap_horiz,
        "account_tree" to R.drawable.ic_tile_account_tree,
        "download" to R.drawable.ic_tile_download,
        "history" to R.drawable.ic_tile_history,
        "rocket_launch" to R.drawable.ic_tile_rocket_launch,
        "deployed_code" to R.drawable.ic_tile_deployed_code,
        "sell" to R.drawable.ic_tile_sell,
        "visibility" to R.drawable.ic_tile_visibility,
    )

    val NAMES: Set<String> get() = icons.keys

    @DrawableRes
    fun drawable(name: String?): Int? = name?.let { icons[it] }
}
