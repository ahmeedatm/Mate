package com.unowhy.companion.net

import org.json.JSONArray
import org.json.JSONObject

data class TaskItem(
    val id: String, val label: String, val daily: Boolean, val done: Boolean,
    /** Cochée par Jira : pas décochable à la main. */
    val auto: Boolean, val detail: String?, val hasUrl: Boolean,
    /** « Lun–ven · 17:30 », « Rappel à 15:00 » ; `deletable` : pas une tâche du fichier de l'ordinateur. */
    val schedule: String? = null, val deletable: Boolean = !daily,
)

/** Tâches du jour, récurrentes et ponctuelles (src/tasks.ts). */
class TasksApi(private val http: Http) {

    /** `refresh` : force une nouvelle lecture de Jira (sinon gardée 5 min sur le Mac). */
    suspend fun list(refresh: Boolean = false): List<TaskItem> = http.io {
        parseTasks(JSONObject(http.get("/tasks" + if (refresh) "?refresh=1" else "")).getJSONArray("tasks"))
    }

    /** Toutes les tâches récurrentes, même celles qui ne tombent pas aujourd'hui. */
    suspend fun recurring(): List<TaskItem> = http.io {
        parseTasks(JSONObject(http.get("/tasks/recurring")).getJSONArray("tasks"))
    }

    /**
     * Nouvelle tâche ; `days` (1 = lundi) : récurrente ; `remindAt` ("HH:MM") : rappel parlé.
     * Renvoie sa fréquence lisible (« Lun–ven · 17:30 »), ou null pour une tâche simple.
     */
    suspend fun add(label: String, remindAt: String? = null, days: List<Int>? = null): String? = http.io {
        val body = JSONObject().put("label", label)
        if (remindAt != null) body.put("remindAt", remindAt)
        if (!days.isNullOrEmpty()) body.put("days", JSONArray(days))
        JSONObject(http.postJson("/tasks", body)).optStringOrNull("schedule")
    }

    suspend fun setDone(id: String, done: Boolean) = http.io { http.postJson("/tasks/${http.encode(id)}/done", JSONObject().put("done", done)); Unit }

    suspend fun delete(id: String) = http.io { http.delete("/tasks/" + http.encode(id)); Unit }

    suspend fun openOnComputer(id: String) = http.io { http.postJson("/tasks/${http.encode(id)}/open", JSONObject()); Unit }

    private fun parseTasks(tasks: JSONArray) = (0 until tasks.length()).map { i ->
        val t = tasks.getJSONObject(i)
        val daily = t.getString("kind") == "daily"
        TaskItem(
            t.getString("id"), t.getString("label"), daily, t.getBoolean("done"),
            t.getBoolean("auto"), t.optStringOrNull("detail"), t.getBoolean("hasUrl"),
            t.optStringOrNull("schedule"), t.optBoolean("deletable", !daily),
        )
    }
}
