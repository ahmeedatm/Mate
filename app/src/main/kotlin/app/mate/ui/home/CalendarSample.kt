package app.mate.ui.home

import app.mate.R
import app.mate.net.CalendarEvent

/**
 * Exemple du catalogue pour les widgets d'agenda (Prochain rendez-vous, Agenda) : une journée type, placée par rapport à
 * maintenant (une réunion dans 13 min, la suite de l'après-midi, les jours suivants), sans ordinateur.
 */
object CalendarSample : WidgetSample {
    private const val MINUTE = 60_000L

    /** Titre, lieu (0 : aucun), début en minutes depuis maintenant, durée en minutes, réunion en ligne. */
    private class Item(val title: Int, val place: Int, val start: Long, val length: Long, val online: Boolean = false)

    private val items = listOf(
        Item(R.string.calendar_sample_daily, 0, -240, 15),
        Item(R.string.calendar_sample_team, R.string.calendar_sample_teams, 13, 30, online = true),
        Item(R.string.calendar_sample_review, R.string.calendar_sample_room, 88, 60),
        Item(R.string.calendar_sample_call, 0, 178, 30, online = true),
        Item(R.string.calendar_sample_lunch, R.string.calendar_sample_place, 1_310, 60),
        Item(R.string.calendar_sample_sport, 0, 2_900, 60),
        Item(R.string.calendar_sample_demo, R.string.calendar_sample_teams, 5_800, 45, online = true),
    )

    /** Les rendez-vous d'exemple à [now] ; [text] lit une chaîne (le contexte, ou un faux en test). */
    fun events(now: Long, text: (Int) -> String): List<CalendarEvent> {
        val base = now - now % MINUTE
        return items.mapIndexed { i, it ->
            val start = base + it.start * MINUTE
            CalendarEvent(
                id = "exemple-$i",
                title = text(it.title),
                location = if (it.place == 0) "" else text(it.place),
                startMs = start,
                endMs = start + it.length * MINUTE,
                allDay = false,
                joinable = it.online,
            )
        }
    }
}
