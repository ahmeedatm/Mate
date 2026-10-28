package com.unowhy.companion.net

import org.json.JSONObject

/** États d'une session Claude Code, comme le compagnon les annonce (backend/src/agents.ts). */
enum class AgentState(val key: String) {
    WORKING("working"),
    WAITING("waiting"),
    DONE("done"),
    LIMIT("limit"),
    IDLE("idle"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: IDLE
    }
}

/**
 * Une session Claude Code de l'ordinateur. `since` : début de l'état, en heure de l'ordinateur ; `lines` : résumé du dernier
 * message (3 lignes au plus, texte brut) ; `waitingFor` : ce que Claude attend (`permission prompt`, `input needed`…) ;
 * `canFocus` : le compagnon sait l'amener devant.
 */
data class AgentSession(
    val id: String,
    val name: String,
    val dir: String,
    val branch: String?,
    val state: AgentState,
    val since: Long,
    val waitingFor: String?,
    val tool: String?,
    val lines: List<String>,
    val workedMs: Long?,
    val limit: String?,
    val canFocus: Boolean,
)

data class AgentCounts(val working: Int, val waiting: Int, val done: Int, val limit: Int, val idle: Int)

/** `now` : l'heure de l'ordinateur à la lecture (les durées se calculent avec elle, pas avec l'horloge de la tablette). */
data class AgentsState(val sessions: List<AgentSession>, val counts: AgentCounts, val total: Int, val hooks: Boolean, val now: Long)

/** Agents Claude Code de l'ordinateur (Mac) : état des sessions, et amener une session devant. */
class AgentsApi(private val http: Http) {

    suspend fun state(): AgentsState = http.io { parseAgents(http.get("/agents", timeoutMs = 10_000)) }

    suspend fun focus(session: String) = http.io {
        http.postJson("/agents/focus", JSONObject().put("session", session))
        Unit
    }
}

internal fun parseAgents(text: String): AgentsState {
    val json = JSONObject(text)
    val list = json.getJSONArray("sessions")
    val counts = json.getJSONObject("counts")
    return AgentsState(
        sessions = (0 until list.length()).map { i ->
            val s = list.getJSONObject(i)
            val lines = s.optJSONArray("lines")
            AgentSession(
                id = s.getString("id"),
                name = s.getString("name"),
                dir = s.optString("dir"),
                branch = s.optStringOrNull("branch"),
                state = AgentState.of(s.optString("state")),
                since = s.optLong("since"),
                waitingFor = s.optStringOrNull("waitingFor"),
                tool = s.optStringOrNull("tool"),
                lines = lines?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                workedMs = if (s.isNull("workedMs")) null else s.optLong("workedMs"),
                limit = s.optStringOrNull("limit"),
                canFocus = s.optBoolean("canFocus"),
            )
        },
        counts = AgentCounts(
            counts.optInt("working"), counts.optInt("waiting"), counts.optInt("done"), counts.optInt("limit"), counts.optInt("idle"),
        ),
        total = json.optInt("total", list.length()),
        hooks = json.optBoolean("hooks"),
        now = json.optLong("now"),
    )
}
