package app.mate.net

import org.json.JSONObject

/** Agenda relié à l'ordinateur (compte ou lien ICS, jamais le lien lui-même). */
data class LinkedCalendar(val id: String, val label: String, val detail: String, val removable: Boolean, val expired: Boolean)

/**
 * `joinable` : réunion Teams, l'ordinateur sait l'ouvrir (le lien reste sur l'ordinateur).
 * `localId` : événement de l'agenda personnel (modifiable depuis la tablette), `remindBefore` : son rappel en minutes.
 */
data class CalendarEvent(
    val id: String, val title: String, val location: String, val startMs: Long, val endMs: Long, val allDay: Boolean,
    val joinable: Boolean, val localId: String? = null, val remindBefore: Int? = null,
)

data class CalendarDays(val source: String, val events: List<CalendarEvent>, val error: String?)

/** Agenda : agendas reliés, événements, agenda personnel, réunions en ligne. */
class CalendarApi(private val http: Http) {

    suspend fun linked(): List<LinkedCalendar> = http.io {
        val list = JSONObject(http.get("/calendar/linked")).getJSONArray("calendars")
        (0 until list.length()).map { i ->
            val c = list.getJSONObject(i)
            LinkedCalendar(c.getString("id"), c.getString("label"), c.optString("detail"), c.optBoolean("removable"), c.optBoolean("expired"))
        }
    }

    suspend fun unlink(id: String) = http.io { http.delete("/calendar/linked/" + http.encode(id)); Unit }

    /** Événements des `days` prochains jours ; `error` : agenda relié injoignable (l'agenda personnel est là quand même). */
    suspend fun days(days: Int = 1): CalendarDays = http.io {
        val json = JSONObject(http.get("/calendar?days=$days"))
        val events = json.getJSONArray("events")
        CalendarDays(
            json.getString("source"),
            (0 until events.length()).map { i ->
                val e = events.getJSONObject(i)
                CalendarEvent(
                    e.optString("id"), e.getString("title"), e.optString("location"), e.getLong("startMs"), e.getLong("endMs"),
                    e.getBoolean("allDay"), e.optBoolean("joinable"), e.optStringOrNull("localId"),
                    if (e.isNull("remindBefore") || !e.has("remindBefore")) null else e.getInt("remindBefore"),
                )
            },
            json.optStringOrNull("error"),
        )
    }

    /** Agenda personnel : `id` null = nouvel événement ; `remindBefore` en minutes (0 = à l'heure), null = pas de rappel. */
    suspend fun saveEvent(id: String?, title: String, startMs: Long, allDay: Boolean, remindBefore: Int?) = http.io {
        val body = JSONObject().put("title", title).put("startMs", startMs).put("allDay", allDay)
            .put("remindBefore", remindBefore ?: JSONObject.NULL)
        if (id == null) http.postJson("/calendar/events", body)
        else http.putJson("/calendar/events/" + http.encode(id), body)
        Unit
    }

    suspend fun deleteEvent(id: String) = http.io { http.delete("/calendar/events/" + http.encode(id)); Unit }

    /** Ouvre la réunion sur l'ordinateur ; renvoie le service (teams, meet, zoom, webex) ou null. */
    suspend fun joinMeeting(id: String): String? = http.io { JSONObject(http.postJson("/calendar/join", JSONObject().put("id", id))).optStringOrNull("kind") }

    /** Option « Rejoindre automatiquement 1 min avant », gardée sur le Mac (marche tablette éteinte). */
    suspend fun autoJoin(): Boolean = http.io { JSONObject(http.get("/calendar/autojoin")).getBoolean("enabled") }

    suspend fun setAutoJoin(enabled: Boolean) = http.io { http.putJson("/calendar/autojoin", JSONObject().put("enabled", enabled)); Unit }
}
