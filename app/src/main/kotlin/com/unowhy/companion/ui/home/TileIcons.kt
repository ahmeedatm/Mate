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
    )

    val NAMES: Set<String> get() = icons.keys

    @DrawableRes
    fun drawable(name: String?): Int? = name?.let { icons[it] }
}
