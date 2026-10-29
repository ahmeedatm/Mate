package com.unowhy.companion.ui.home

import com.unowhy.companion.net.CalendarEvent

/** Ce que dit « Ma journée » ([DayWidget]) : la salutation, puis la prochaine réunion du jour ou ce qu'il reste à savoir. */
object DayBrief {

    enum class Greeting { MORNING, AFTERNOON, EVENING }

    /** Bonjour de 5 h à midi, bon après-midi jusqu'à 18 h, bonsoir ensuite. */
    fun greeting(hour: Int) = when (hour) {
        in 5..11 -> Greeting.MORNING
        in 12..17 -> Greeting.AFTERNOON
        else -> Greeting.EVENING
    }

    sealed interface Focus {
        /** Réunion en cours ou plus tard aujourd'hui : en grand. */
        data class Meeting(val event: CalendarEvent, val line: NextLine) : Focus

        /** Plus rien aujourd'hui ; [hadMeetings] : il y en a eu (« Plus de réunion ») ; [later] : la prochaine, demain ou après. */
        data class Free(val hadMeetings: Boolean, val later: NextLine?) : Focus
    }

    fun focus(events: List<CalendarEvent>, now: Long, chosenId: String? = null): Focus {
        val event = nextEvent(events, now, chosenId)
        val line = event?.let { nextLine(it, now) }
        if (event != null && (line is NextLine.Now || line is NextLine.InMinutes || line is NextLine.At)) return Focus.Meeting(event, line)
        val hadMeetings = events.any { !it.allDay && it.endMs <= now && daysFrom(now, it.startMs) == 0 }
        return Focus.Free(hadMeetings, line)
    }

    /**
     * « Rejoindre » d'une réunion en ligne, à partir de [JOIN_EARLY_MS] avant : plus tôt, un toucher par erreur ouvrirait
     * Teams pour rien. Une réunion choisie dans l'agenda peut être rejointe tout de suite (le choix est volontaire).
     */
    fun canJoin(event: CalendarEvent, now: Long, chosen: Boolean, meetings: Boolean) =
        meetings && event.joinable && (chosen || event.startMs - now <= JOIN_EARLY_MS)

    const val JOIN_EARLY_MS = 15 * 60_000L
}
