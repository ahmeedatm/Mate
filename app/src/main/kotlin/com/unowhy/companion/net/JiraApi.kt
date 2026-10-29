package com.unowhy.companion.net

import org.json.JSONArray
import org.json.JSONObject

/** Ticket Jira de l'utilisateur. `priority` : null si Jira n'en donne pas. */
data class JiraIssue(val key: String, val summary: String, val type: String, val priority: String?)

/** Colonne = un statut ; `category` : `new` (à faire) ou `indeterminate` (en cours). Déjà rangée par l'ordinateur. */
data class JiraColumn(val status: String, val category: String, val issues: List<JiraIssue>)

/** `to` : statut d'arrivée ; `needsJira` : la transition demande des champs, à finir dans Jira. */
data class JiraTransition(val id: String, val name: String, val to: String, val needsJira: Boolean)

private fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { block(getJSONObject(it)) }

fun parseJiraIssues(json: String): List<JiraColumn> =
    JSONObject(json).optJSONArray("columns").mapObjects { c ->
        JiraColumn(
            c.getString("status"),
            c.optString("category"),
            c.optJSONArray("issues").mapObjects { JiraIssue(it.getString("key"), it.optString("summary"), it.optString("type"), it.optStringOrNull("priority")) },
        )
    }

fun parseJiraTransitions(json: String): List<JiraTransition> =
    JSONObject(json).optJSONArray("transitions").mapObjects {
        JiraTransition(it.getString("id"), it.getString("name"), it.optString("to"), it.optBoolean("needsJira"))
    }

/** Jira de l'ordinateur (si configuré) : tickets assignés non terminés, transitions, ouverture dans le navigateur. */
class JiraApi(private val http: Http) {

    suspend fun issues(): List<JiraColumn> = http.io { parseJiraIssues(http.get("/jira/issues", timeoutMs = 20_000)) }

    suspend fun transitions(key: String): List<JiraTransition> =
        http.io { parseJiraTransitions(http.get("/jira/issues/${http.encode(key)}/transitions", timeoutMs = 20_000)) }

    /** 409 si la transition demande des champs : le message de l'ordinateur le dit (voir `errorText`). */
    suspend fun transition(key: String, id: String) = http.io {
        http.postJson("/jira/issues/${http.encode(key)}/transitions", JSONObject().put("id", id))
        Unit
    }

    suspend fun openOnComputer(key: String) = http.io { http.postJson("/jira/issues/${http.encode(key)}/open", JSONObject()); Unit }
}
