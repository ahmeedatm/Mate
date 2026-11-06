package app.mate.ui.home

import app.mate.R
import app.mate.focus.FocusTimer
import app.mate.focus.NoisePlayer
import app.mate.focus.Water
import java.util.Calendar

/**
 * Données d'exemple d'un widget, pour son aperçu dans le catalogue ([HomeWidget.showPreview]) : écrites en dur, sans
 * réseau ni ordinateur. Chaque widget a sa classe d'exemple, à côté de son code.
 */
interface WidgetSample

/** L'exemple de chaque widget ; null = pas encore d'aperçu (exception de `WidgetRulesTest`). Un bloc par famille de widgets. */
object WidgetSamples {
    fun of(type: WidgetType): WidgetSample? = when (type) {
        // #180 Heure et journée
        WidgetType.CLOCK -> ClockSample
        WidgetType.HERO -> null
        WidgetType.DAY -> null
        WidgetType.DAYLINE -> null
        WidgetType.WEATHER -> WeatherSample.paris()
        WidgetType.NEXT -> CalendarSample
        WidgetType.AGENDA -> null

        // #181 Listes et notes
        WidgetType.TASKS -> null
        WidgetType.SHOPPING -> null
        WidgetType.CHECKLIST -> null
        WidgetType.NOTE -> NoteSample(R.string.widget_note_sample)
        WidgetType.MEALS -> null
        WidgetType.NEWS -> null
        WidgetType.MAIL -> null

        // #182 Son et musique
        WidgetType.AUDIO -> AudioSample
        WidgetType.MEDIA -> MediaSample
        WidgetType.MIXER -> MixerSample
        WidgetType.YTMUSIC -> YtMusicSample

        // #183 Piloter l'ordinateur
        WidgetType.KEYS -> KeysSample(
            listOf(
                R.string.widget_keys_sample_mute to "mic_off",
                R.string.widget_keys_sample_screenshot to "screenshot",
                R.string.widget_keys_sample_play to "play_pause",
                R.string.widget_keys_sample_next to "skip_next",
                R.string.widget_keys_sample_dnd to "dnd",
                R.string.widget_keys_sample_lock to "lock",
                R.string.widget_keys_sample_dark to "dark_mode",
                R.string.widget_keys_sample_volume_off to "volume_off",
            ),
        )
        WidgetType.APPS -> AppsSample(
            listOf(
                R.string.widget_apps_sample_music,
                R.string.widget_apps_sample_chat,
                R.string.widget_apps_sample_browser,
                R.string.widget_apps_sample_notes,
                R.string.widget_apps_sample_terminal,
                R.string.widget_apps_sample_mail,
            ),
        )
        WidgetType.OPEN -> null
        WidgetType.PAD -> null
        WidgetType.SCREEN -> null
        WidgetType.PERF -> null
        WidgetType.WEB -> null

        // #184 Appels
        WidgetType.DISCORD -> null
        WidgetType.MEETING -> null
        WidgetType.CALL -> null
        WidgetType.PRESENT -> null

        // #185 Dev
        WidgetType.AGENTS -> null
        WidgetType.JIRA -> null
        WidgetType.GITLAB -> null
        WidgetType.PIPELINES -> null
        WidgetType.GITHUB -> null
        WidgetType.ACTIONS -> null
        WidgetType.METRICS -> null

        // #186 Perso, maison et jeux
        WidgetType.FOCUS -> FocusSample(FocusTimer.State(FocusTimer.Phase.FOCUS, 25 * MINUTE, 18 * MINUTE + 42_000, running = true, completed = 2), NoisePlayer.Kind.FOREST)
        WidgetType.WATER -> WaterSample(Water.State(glasses = 5, goal = 8, reminder = true, intervalMinutes = 60), todayAt(15))
        WidgetType.AWAY -> null
        WidgetType.HA -> null
        WidgetType.VALORANT -> ValorantWidget.SAMPLE
    }

    private const val MINUTE = 60_000L

    /** Aujourd'hui à [hour] heures pile (le prochain rappel de l'exemple de l'Eau). */
    private fun todayAt(hour: Int) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
