package com.unowhy.companion

import android.content.Context

/** Réglages locaux : ordinateur piloté (voir Computers), luminosité, thème, kiosque. Aucune clé d'API ici. */
object Prefs {
    private const val FILE = "companion"
    private const val KEY_ALLOW_INSTALLS = "allow_installs"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_NIGHT_DIM = "night_dim"
    private const val KEY_DECK_TABS = "deck_tabs_"
    private const val KEY_THEME = "theme"
    private const val KEY_FOCUS_DND = "focus_dnd"
    private const val KEY_AGENTS_PRIVATE = "agents_private"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Backend de l'ordinateur actif ; sans ordinateur, l'URL de build (USB : http://127.0.0.1:3000). */
    fun backendUrl(context: Context): String = Computers.active(context)?.url ?: BuildConfig.DEFAULT_BACKEND_URL

    fun token(context: Context): String = Computers.active(context)?.token.orEmpty()

    /** Luminosité de l'écran choisie dans Réglages (0,05 à 1) ; -1 : celle du système. */
    fun brightness(context: Context): Float = prefs(context).getFloat(KEY_BRIGHTNESS, -1f)

    fun setBrightness(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_BRIGHTNESS, value).apply()
    }

    /** Écran atténué la nuit (il reste allumé en permanence). Activé par défaut. */
    fun nightDim(context: Context): Boolean = prefs(context).getBoolean(KEY_NIGHT_DIM, true)

    fun setNightDim(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NIGHT_DIM, value).apply()
    }

    /** Ne pas déranger sur l'ordinateur pendant la concentration du Focus. Activé par défaut. */
    fun focusDnd(context: Context): Boolean = prefs(context).getBoolean(KEY_FOCUS_DND, true)

    fun setFocusDnd(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOCUS_DND, value).apply()
    }

    /** Kiosque : installations permises (mise à jour de l'appli par adb). Bloquées par défaut. */
    fun allowInstalls(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_INSTALLS, false)

    fun setAllowInstalls(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_INSTALLS, value).apply()
    }

    /** Ordre des onglets du Deck choisi sur la tablette, propre à chaque ordinateur (clés d'onglets, voir DeckFragment). */
    fun deckTabOrder(context: Context): List<String> =
        prefs(context).getString(KEY_DECK_TABS + Computers.active(context)?.id.orEmpty(), null)?.split('\n').orEmpty()

    fun setDeckTabOrder(context: Context, keys: List<String>) {
        prefs(context).edit().putString(KEY_DECK_TABS + Computers.active(context)?.id.orEmpty(), keys.joinToString("\n")).apply()
    }

    /** Thème choisi dans Réglages (nom d'AppTheme) ; null : Nuit. */
    /** Agents Claude : contenu des sessions (commandes, questions, résumés) masqué à l'écran, pour le direct. */
    fun agentsPrivate(context: Context): Boolean = prefs(context).getBoolean(KEY_AGENTS_PRIVATE, false)

    fun setAgentsPrivate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AGENTS_PRIVATE, value).apply()
    }

    fun theme(context: Context): String? = prefs(context).getString(KEY_THEME, null)

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
    }
}
