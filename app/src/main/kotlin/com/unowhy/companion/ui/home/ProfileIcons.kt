package com.unowhy.companion.ui.home

import androidx.annotation.DrawableRes
import com.unowhy.companion.R

/**
 * Icônes proposées pour un profil (boîte des profils) : clé enregistrée dans la disposition ([HomeLayout.icons]) →
 * icône. Sans choix, l'icône du rôle : maison pour l'Accueil, caméra pour Réunion, manette pour le profil de jeu.
 */
object ProfileIcons {
    val ALL: List<Pair<String, Int>> = listOf(
        "home" to R.drawable.ic_home,
        "work" to R.drawable.ic_work,
        "dashboard" to R.drawable.ic_dashboard,
        "widgets" to R.drawable.ic_widgets,
        "apps" to R.drawable.ic_apps,
        "sound" to R.drawable.ic_volume,
        "laptop" to R.drawable.ic_laptop,
        "display" to R.drawable.ic_display,
        "keyboard" to R.drawable.ic_tile_keyboard,
        "agents" to R.drawable.ic_agents,
        "chat" to R.drawable.ic_chat,
        "call" to R.drawable.ic_call,
        "video" to R.drawable.ic_videocam,
        "present" to R.drawable.ic_tile_present,
        "event" to R.drawable.ic_event,
        "tasks" to R.drawable.ic_tasks,
        "timer" to R.drawable.ic_timer,
        "music" to R.drawable.ic_wave,
        "game" to R.drawable.ic_gamepad,
        "palette" to R.drawable.ic_palette,
        "image" to R.drawable.ic_image,
        "folder" to R.drawable.ic_folder,
        "speed" to R.drawable.ic_speed,
        "night" to R.drawable.ic_tile_bedtime,
    )

    @DrawableRes
    fun drawable(key: String?): Int? = ALL.firstOrNull { it.first == key }?.second

    /** Icône du profil `page` : celle choisie, sinon celle de son rôle. */
    @DrawableRes
    fun of(layout: HomeLayout, page: Int): Int = drawable(layout.iconOf(page)) ?: when (page) {
        layout.homeProfile -> R.drawable.ic_home
        layout.deckProfile -> R.drawable.ic_deck
        layout.callProfile -> R.drawable.ic_videocam
        layout.gameProfile -> R.drawable.ic_gamepad
        else -> R.drawable.ic_widgets
    }
}
