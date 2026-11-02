package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

/** `label` : nom de la touche du Deck, null si elle a été supprimée depuis ; `schedule` : « Lun–ven · 18:00 », « Toutes les 45 min ». */
data class ScheduleItem(val id: String, val tileId: String, val label: String?, val enabled: Boolean, val schedule: String)

/** Programmations d'une touche du Deck, lancées par l'ordinateur (src/schedule.ts). */
class ScheduleApi(private val http: Http) {

    suspend fun list(): List<ScheduleItem> = http.io { parse(http.get("/schedule")) }

    /** Heure fixe (`at`, avec `days` : 1 = lundi ; tous les jours si vide) ou intervalle (`everyMinutes`). */
    suspend fun add(tileId: String, at: String? = null, days: List<Int>? = null, everyMinutes: Int? = null) = http.io {
        http.postJson("/schedule", body(tileId, at, days, everyMinutes)); Unit
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = http.io {
        http.postJson("/schedule/${http.encode(id)}/enabled", JSONObject().put("enabled", enabled)); Unit
    }

    suspend fun delete(id: String) = http.io { http.delete("/schedule/" + http.encode(id)); Unit }

    companion object {
        /** Intervalles proposés, en minutes. */
        val INTERVALS = listOf(15, 30, 45, 60, 90, 120)

        fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

        fun body(tileId: String, at: String? = null, days: List<Int>? = null, everyMinutes: Int? = null): JSONObject {
            val body = JSONObject().put("tileId", tileId)
            if (at != null) body.put("at", at)
            if (at != null && !days.isNullOrEmpty()) body.put("days", JSONArray(days))
            if (at == null && everyMinutes != null) body.put("everyMinutes", everyMinutes)
            return body
        }

        fun parse(json: String): List<ScheduleItem> {
            val items = JSONObject(json).getJSONArray("items")
            return (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                ScheduleItem(item.getString("id"), item.getString("tileId"), item.optStringOrNull("label"), item.getBoolean("enabled"), item.getString("schedule"))
            }
        }
    }
}
