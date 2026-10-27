package com.unowhy.companion.ui.home

import com.unowhy.companion.net.CalendarEvent

/** Le prochain rendez-vous en une ligne : quand, selon qu'il est proche, en cours, demain ou plus loin. */
sealed interface NextLine {
    val title: String

    /** Départ dans moins d'une heure. */
    data class InMinutes(override val title: String, val minutes: Int) : NextLine

    /** Plus tard aujourd'hui. */
    data class At(override val title: String, val startMs: Long) : NextLine

    /** Déjà commencé, pas fini. */
    data class Now(override val title: String, val endMs: Long) : NextLine

    data class Tomorrow(override val title: String, val startMs: Long) : NextLine

    data class Day(override val title: String, val startMs: Long, val days: Int) : NextLine
}

/**
 * L'événement à montrer : la réunion choisie dans l'agenda tant qu'elle n'est pas finie, sinon le premier événement
 * horaire qui n'est pas terminé (les événements sur la journée entière ne sont jamais « le prochain »).
 */
fun nextEvent(events: List<CalendarEvent>, now: Long, chosenId: String? = null): CalendarEvent? =
    events.firstOrNull { it.id == chosenId && it.endMs > now } ?: events.firstOrNull { !it.allDay && it.endMs > now }

fun nextLine(event: CalendarEvent, now: Long): NextLine {
    if (event.startMs <= now) return NextLine.Now(event.title, event.endMs)
    return when (val days = daysFrom(now, event.startMs)) {
        0 ->
            if (event.startMs - now < 60 * 60_000L) NextLine.InMinutes(event.title, ((event.startMs - now) / 60_000).toInt())
            else NextLine.At(event.title, event.startMs)
        1 -> NextLine.Tomorrow(event.title, event.startMs)
        else -> NextLine.Day(event.title, event.startMs, days)
    }
}

fun nextLine(events: List<CalendarEvent>, now: Long, chosenId: String? = null): NextLine? =
    nextEvent(events, now, chosenId)?.let { nextLine(it, now) }
