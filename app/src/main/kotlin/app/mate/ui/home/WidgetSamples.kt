package app.mate.ui.home

import app.mate.R

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
        WidgetType.WEATHER -> null
        WidgetType.NEXT -> null
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
        WidgetType.AUDIO -> null
        WidgetType.MEDIA -> null
        WidgetType.MIXER -> null
        WidgetType.YTMUSIC -> null

        // #183 Piloter l'ordinateur
        WidgetType.KEYS -> null
        WidgetType.APPS -> null
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
        WidgetType.FOCUS -> null
        WidgetType.WATER -> null
        WidgetType.AWAY -> null
        WidgetType.HA -> null
        WidgetType.VALORANT -> null
    }
}
