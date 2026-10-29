package com.unowhy.companion

import android.content.Context

/** Réglages locaux : ordinateur piloté (voir Computers), luminosité, thème, kiosque. Aucune clé d'API ici. */
object Prefs {
    private const val FILE = "companion"
    private const val KEY_ALLOW_INSTALLS = "allow_installs"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_NIGHT_DIM = "night_dim"
    private const val KEY_THEME = "theme"
    private const val KEY_RAIL_LABELS = "rail_labels"
    private const val KEY_TOUR_DONE = "tour_done"
    private const val KEY_RAIL_HIDDEN = "rail_hidden_tools"
    private const val KEY_FOCUS_DND = "focus_dnd"
    private const val KEY_AGENTS_PRIVATE = "agents_private"
    private const val KEY_NOTIFICATIONS_DISCREET = "notifications_discreet"
    private const val KEY_FRAME_DELAY = "frame_delay_min"
    private const val KEY_FRAME_INTERVAL = "frame_interval_s"
    private const val KEY_TRIAL_MR = "trial_mr"
    private const val KEY_TRIAL_BACKEND = "trial_backend"
    private const val KEY_TRIAL_OPENED = "trial_opened"

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

    /** Visite guidée du premier lancement déjà vue (ou passée). */
    fun tourDone(context: Context): Boolean = prefs(context).getBoolean(KEY_TOUR_DONE, false)

    fun setTourDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_TOUR_DONE, true).apply()
    }

    /** Barre de gauche : noms sous les icônes (Réglages > Barre de gauche). Affichés par défaut. */
    fun railLabels(context: Context): Boolean = prefs(context).getBoolean(KEY_RAIL_LABELS, true)

    fun setRailLabels(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAIL_LABELS, value).apply()
    }

    /** Outils de la barre de gauche, dans leur ordre : tous sauf ceux masqués dans Réglages. */
    fun railTools(context: Context): List<Mode> {
        val hidden = prefs(context).getStringSet(KEY_RAIL_HIDDEN, emptySet()).orEmpty()
        return RAIL_TOOLS.filter { it.key !in hidden }
    }

    fun setRailTool(context: Context, tool: Mode, shown: Boolean) {
        val hidden = prefs(context).getStringSet(KEY_RAIL_HIDDEN, emptySet()).orEmpty().toMutableSet()
        if (shown) hidden -= tool.key else hidden += tool.key
        prefs(context).edit().putStringSet(KEY_RAIL_HIDDEN, hidden).apply()
    }

    /** Outils que la barre de gauche peut montrer. */
    val RAIL_TOOLS = listOf(Mode.FOCUS, Mode.COMPUTER, Mode.CAMERA)

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

    fun theme(context: Context): String? = prefs(context).getString(KEY_THEME, null)

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
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
