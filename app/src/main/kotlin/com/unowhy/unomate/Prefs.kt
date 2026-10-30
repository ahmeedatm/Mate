package com.unowhy.unomate

import android.content.Context
import com.unowhy.unomate.audio.VoiceChoice
import com.unowhy.unomate.away.AwayState
import com.unowhy.unomate.focus.FocusPresets

/** Réglages locaux : ordinateur piloté (voir Computers), luminosité, thème, kiosque. Aucune clé d'API ici. */
/** Familles d'animations qui se coupent une à une dans Réglages > Animations (`key` : préférence). */
enum class AnimFamily(val key: String) {
    /** Changement d'écran : glissé, écran qui suit le doigt au balayage. */
    NAVIGATION("anim_navigation"),

    /** Arrivée des widgets de l'Accueil, un à un. */
    HOME("anim_home"),

    /** Boîtes de dialogue qui apparaissent en douceur. */
    DIALOGS("anim_dialogs"),

    /** Cloche, bandeau, pastille de l'ordinateur. */
    ALERTS("anim_alerts"),

    /** Rebonds, fondus des contenus, compte à rebours du Pomodoro. */
    DETAILS("anim_details"),
}

object Prefs {
    private const val FILE = "companion"
    private const val KEY_ALLOW_INSTALLS = "allow_installs"
    private const val KEY_EXIT_LOCK = "exit_lock"
    private const val KEY_POWER_MENU = "power_menu"
    private const val KEY_LANDSCAPE_LOCKED = "landscape_locked"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_NIGHT_DIM = "night_dim"
    private const val KEY_THEME = "theme"
    private const val KEY_RAIL_LABELS = "rail_labels"
    private const val KEY_TOUR_DONE = "tour_done"
    private const val KEY_RAIL_HIDDEN = "rail_hidden_tools"
    private const val KEY_FOCUS_DND = "focus_dnd"
    private const val KEY_FOCUS_MINUTES = "focus_minutes"
    private const val KEY_FOCUS_PAUSE = "focus_pause"
    private const val KEY_FOCUS_CUSTOM_MINUTES = "focus_custom_minutes"
    private const val KEY_FOCUS_CUSTOM_PAUSE = "focus_custom_pause"
    private const val KEY_AGENTS_PRIVATE = "agents_private"
    private const val KEY_NOTIFICATIONS_DISCREET = "notifications_discreet"
    private const val KEY_WAKE_WORD = "wake_word"
    private const val KEY_MEETING_ALERT = "meeting_alert"
    private const val KEY_MEETING_ALERT_SEEN = "meeting_alert_seen"
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
    private const val KEY_TTS_VOICE = "tts_voice"
    private const val KEY_TTS_RATE = "tts_rate"
    private const val KEY_AWAY_SINCE = "away_since"
    private const val KEY_AWAY_MIC = "away_mic_by_us"
    private const val KEY_AWAY_FOCUS = "away_focus_by_us"
    private const val KEY_SPEAK_ACTIONS = "speak_actions"
    private const val KEY_TOP_BAR = "top_bar"
    private const val KEY_TOP_BELL = "top_bar_bell"
    private const val KEY_TOP_WEATHER = "top_weather"

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

    /** Durées du Pomodoro choisies sur l'écran Focus (25 / 5 par défaut), reprises au démarrage. */
    fun focusPreset(context: Context): FocusPresets.Preset = prefs(context).let {
        FocusPresets.bounded(it.getInt(KEY_FOCUS_MINUTES, 25), it.getInt(KEY_FOCUS_PAUSE, 5))
    }

    fun setFocusPreset(context: Context, value: FocusPresets.Preset) {
        prefs(context).edit().putInt(KEY_FOCUS_MINUTES, value.focus).putInt(KEY_FOCUS_PAUSE, value.pause).apply()
    }

    /** Durées de la pastille « Perso » de Focus. */
    fun focusCustom(context: Context): FocusPresets.Preset = prefs(context).let {
        val default = FocusPresets.DEFAULT_CUSTOM
        FocusPresets.bounded(it.getInt(KEY_FOCUS_CUSTOM_MINUTES, default.focus), it.getInt(KEY_FOCUS_CUSTOM_PAUSE, default.pause))
    }

    fun setFocusCustom(context: Context, value: FocusPresets.Preset) {
        prefs(context).edit().putInt(KEY_FOCUS_CUSTOM_MINUTES, value.focus).putInt(KEY_FOCUS_CUSTOM_PAUSE, value.pause).apply()
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

    /** Kiosque : réinitialisation d'usine et mode sans échec interdits (anti-sortie). Permis par défaut. */
    fun exitLock(context: Context): Boolean = prefs(context).getBoolean(KEY_EXIT_LOCK, false)

    fun setExitLock(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXIT_LOCK, value).apply()
    }

    /** Kiosque : menu du bouton marche/arrêt (éteindre, redémarrer) permis en verrouillage d'écran. Coupé par défaut. */
    fun powerMenu(context: Context): Boolean = prefs(context).getBoolean(KEY_POWER_MENU, false)

    fun setPowerMenu(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_POWER_MENU, value).apply()
    }

    /** Écran verrouillé en paysage, un seul sens. Par défaut les deux sens de paysage suivent le capteur. */
    fun landscapeLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_LANDSCAPE_LOCKED, false)

    fun setLandscapeLocked(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LANDSCAPE_LOCKED, value).apply()
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

    /** Outils que la barre de gauche peut montrer ; la Caméra s'ouvre depuis Ordi (ses deux cases), plus depuis la barre. */
    val RAIL_TOOLS = listOf(Mode.FOCUS, Mode.COMPUTER)

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

    /** Alerte plein écran 5 min avant une réunion de l'agenda (ui/MeetingAlertOverlay). Coupée par défaut. */
    fun meetingAlert(context: Context): Boolean = prefs(context).getBoolean(KEY_MEETING_ALERT, false)

    fun setMeetingAlert(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEETING_ALERT, value).apply()
    }

    /** Réunions déjà annoncées (clés de MeetingAlertModel) : une seule alerte par réunion, même après un redémarrage de l'appli. */
    fun meetingAlertSeen(context: Context): Set<String> = prefs(context).getStringSet(KEY_MEETING_ALERT_SEEN, emptySet()).orEmpty()

    fun setMeetingAlertSeen(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_MEETING_ALERT_SEEN, value).apply()
    }

    /** Mot de réveil « Mate » : écoute du mot quand UnoMate est à l'écran (voice/WakeWord). Activé par défaut. */
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

    /** Réglages > Animations : une famille d'animations coupée (toutes actives par défaut). Voir ui/Look.on. */
    fun animation(context: Context, family: AnimFamily): Boolean = prefs(context).getBoolean(family.key, true)

    fun setAnimation(context: Context, family: AnimFamily, value: Boolean) {
        prefs(context).edit().putBoolean(family.key, value).apply()
    }

    /** Accessibilité : notifications du PC et messages de l'ordinateur lus à voix haute. Désactivé par défaut. */
    fun readBanners(context: Context): Boolean = prefs(context).getBoolean(KEY_READ_BANNERS, false)

    fun setReadBanners(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_BANNERS, value).apply()
    }

    /** Accessibilité : voix de la synthèse vocale (nom donné par le moteur) ; null = automatique (celle du moteur). */
    fun ttsVoice(context: Context): String? = prefs(context).getString(KEY_TTS_VOICE, null)

    fun setTtsVoice(context: Context, value: String?) {
        prefs(context).edit().apply { if (value == null) remove(KEY_TTS_VOICE) else putString(KEY_TTS_VOICE, value) }.apply()
    }

    /** Accessibilité : débit de la synthèse vocale (1 = celui du moteur). 0,9 par défaut (VoiceChoice.DEFAULT_RATE). */
    fun ttsRate(context: Context): Float = prefs(context).getFloat(KEY_TTS_RATE, VoiceChoice.DEFAULT_RATE)

    fun setTtsRate(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TTS_RATE, value).apply()
    }

    /** Accessibilité : chaque touche lancée depuis la tablette est annoncée à voix haute (ui/ActionSpeech). Désactivé par défaut. */
    fun speakActions(context: Context): Boolean = prefs(context).getBoolean(KEY_SPEAK_ACTIONS, false)

    fun setSpeakActions(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK_ACTIONS, value).apply()
    }

    /**
     * Éléments du bandeau du haut, dans l'ordre (clés de TopItem séparées par des virgules) ; null : jamais réglé.
     * La cloche, venue de la barre de gauche (30/10), est ajoutée une fois aux bandeaux déjà réglés (TopBarModel.withNotifications).
     */
    fun topBar(context: Context): String? {
        val prefs = prefs(context)
        val saved = prefs.getString(KEY_TOP_BAR, null)
        if (saved == null || prefs.getBoolean(KEY_TOP_BELL, false)) return saved
        val withBell = com.unowhy.unomate.ui.topbar.TopBarModel.run { serialize(withNotifications(parse(saved))) }
        prefs.edit().putString(KEY_TOP_BAR, withBell).putBoolean(KEY_TOP_BELL, true).apply()
        return withBell
    }

    fun setTopBar(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOP_BAR, value).apply()
    }

    /** Ville de la météo du bandeau (même forme que la config d'un widget Météo : city, lat, lon, auto) ; null : aucune. */
    fun topWeather(context: Context): String? = prefs(context).getString(KEY_TOP_WEATHER, null)

    fun setTopWeather(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOP_WEATHER, value).apply()
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

    /** Absence en cours (widget « Je m'absente ») : survit à la recréation de l'activité ; null si aucune. */
    fun away(context: Context): AwayState? = prefs(context).let {
        val since = it.getLong(KEY_AWAY_SINCE, 0L)
        if (since <= 0L) null else AwayState(since, it.getBoolean(KEY_AWAY_MIC, false), it.getBoolean(KEY_AWAY_FOCUS, false))
    }

    fun setAway(context: Context, state: AwayState?) {
        prefs(context).edit().apply {
            if (state == null) remove(KEY_AWAY_SINCE).remove(KEY_AWAY_MIC).remove(KEY_AWAY_FOCUS)
            else putLong(KEY_AWAY_SINCE, state.sinceMs).putBoolean(KEY_AWAY_MIC, state.micMutedByUs).putBoolean(KEY_AWAY_FOCUS, state.focusPausedByUs)
        }.apply()
    }
}
