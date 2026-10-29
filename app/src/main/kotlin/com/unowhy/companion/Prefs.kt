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
    private const val KEY_NOTIFICATIONS_DISCREET = "notifications_discreet"
    private const val KEY_WAKE_WORD = "wake_word"
    private const val KEY_WEATHER_LOCATION = "weather_location"
    private const val KEY_TEXT_SCALE = "text_scale"
    private const val KEY_FRAME_DELAY = "frame_delay_min"
    private const val KEY_FRAME_INTERVAL = "frame_interval_s"
    private const val KEY_TRIAL_MR = "trial_mr"
    private const val KEY_TRIAL_BACKEND = "trial_backend"
    private const val KEY_TRIAL_OPENED = "trial_opened"
    private const val KEY_LEGIBLE_FONT = "legible_font"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_READ_BANNERS = "read_banners"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Backend de l'ordinateur actif ; sans ordinateur, l'URL de build (USB : http://127.0.0.1:3000). */
    fun backendUrl(context: Context): String = Computers.active(context)?.url ?: BuildConfig.DEFAULT_BACKEND_URL

    fun token(context: Context): String = Computers.active(context)?.token.orEmpty()

    /** Empreinte du certificat de l'ordinateur actif, retenue à l'appairage (null : USB ou ancien appairage). */
    fun pin(context: Context): String? = Computers.active(context)?.pin

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

    /** Cadre photo : minutes sans toucher avant qu'il se lance (0 = jamais). 5 par défaut ; sans photo, il ne se lance pas. */
    fun frameDelayMin(context: Context): Int = prefs(context).getInt(KEY_FRAME_DELAY, 5)

    fun setFrameDelayMin(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_FRAME_DELAY, value).apply()
    }

    /** Cadre photo : secondes par photo. */
    fun frameIntervalS(context: Context): Int = prefs(context).getInt(KEY_FRAME_INTERVAL, 60)

    fun setFrameIntervalS(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_FRAME_INTERVAL, value).apply()
    }

    /** Kiosque : installations permises (mise à jour de l'appli par adb). Bloquées par défaut. */
    fun allowInstalls(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_INSTALLS, false)

    fun setAllowInstalls(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_INSTALLS, value).apply()
    }

    /** Ordre des onglets du Deck choisi sur la tablette, propre à chaque ordinateur (clés d'onglets, voir DeckFragment). */
    fun deckTabOrder(context: Context): List<String> =
        prefs(context).getString(KEY_DECK_TABS + Computers.active(context)?.id.orEmpty(), null)?.split('\n').orEmpty()

    /** Liste vide : retour à l'ordre par défaut. */
    fun setDeckTabOrder(context: Context, keys: List<String>) {
        val key = KEY_DECK_TABS + Computers.active(context)?.id.orEmpty()
        prefs(context).edit().apply { if (keys.isEmpty()) remove(key) else putString(key, keys.joinToString("\n")) }.apply()
    }

    /** Thème choisi dans Réglages (nom d'AppTheme) ; null : Nuit. */
    /** Agents Claude : contenu des sessions (commandes, questions, résumés) masqué à l'écran, pour le direct. */
    fun agentsPrivate(context: Context): Boolean = prefs(context).getBoolean(KEY_AGENTS_PRIVATE, false)

    fun setAgentsPrivate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AGENTS_PRIVATE, value).apply()
    }

    /** Notifications de l'ordinateur : appli et titre seulement, sans le texte (bandeau et historique). Désactivé par défaut. */
    fun notificationsDiscreet(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFICATIONS_DISCREET, false)

    fun setNotificationsDiscreet(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_DISCREET, value).apply()
    }

    /** Mot de réveil « Mate » : écoute du mot quand Companion est à l'écran (voice/WakeWord). Activé par défaut. */
    fun wakeWord(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE_WORD, true)

    fun setWakeWord(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_WORD, value).apply()
    }

    /**
     * Ville de la météo trouvée seule par la position (services de localisation de Google, docs/confidentialite.md).
     * Coupé par défaut ; « Ma position » dans « Choisir ma ville » reste possible, c'est une demande explicite.
     */
    /**
     * Taille du texte de l'appli, en plus de celle d'Android (que le kiosque empêche de régler) : 1 = normale.
     * Appliquée par MainActivity.attachBaseContext ; un changement recrée l'activité.
     */
    fun textScale(context: Context): Float = prefs(context).getFloat(KEY_TEXT_SCALE, 1f)

    fun setTextScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TEXT_SCALE, value).apply()
    }

    fun weatherLocation(context: Context): Boolean = prefs(context).getBoolean(KEY_WEATHER_LOCATION, false)

    fun setWeatherLocation(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEATHER_LOCATION, value).apply()
    }

    fun theme(context: Context): String? = prefs(context).getString(KEY_THEME, null)

    /** `null` : retour au thème par défaut. */
    fun setTheme(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
    }

    /** Accessibilité : police Atkinson Hyperlegible à la place de celles du thème. Désactivé par défaut. */
    fun legibleFont(context: Context): Boolean = prefs(context).getBoolean(KEY_LEGIBLE_FONT, false)

    fun setLegibleFont(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGIBLE_FONT, value).apply()
    }

    /** Accessibilité : ni animations continues ni ressorts (Look.still). Désactivé par défaut. */
    fun reduceMotion(context: Context): Boolean = prefs(context).getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REDUCE_MOTION, value).apply()
    }

    /** Accessibilité : notifications du PC et messages de l'ordinateur lus à voix haute. Désactivé par défaut. */
    fun readBanners(context: Context): Boolean = prefs(context).getBoolean(KEY_READ_BANNERS, false)

    fun setReadBanners(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_BANNERS, value).apply()
    }

    /** MR de l'essai installé par la stable (0 : aucun, ou installé autrement) et son backend : « Ouvrir l'essai » les relit. */
    fun trialMr(context: Context): Int = prefs(context).getInt(KEY_TRIAL_MR, 0)

    fun trialHasBackend(context: Context): Boolean = prefs(context).getBoolean(KEY_TRIAL_BACKEND, false)

    fun setTrial(context: Context, mr: Int, hasBackend: Boolean) {
        prefs(context).edit().putInt(KEY_TRIAL_MR, mr).putBoolean(KEY_TRIAL_BACKEND, hasBackend).apply()
    }

    /** La stable a ouvert l'essai : à son retour, elle remet le backend d'origine de l'ordinateur si l'essai n'a pas pu. */
    fun setTrialOpened(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRIAL_OPENED, value).apply()
    }

    fun trialOpened(context: Context): Boolean = prefs(context).getBoolean(KEY_TRIAL_OPENED, false)
}
