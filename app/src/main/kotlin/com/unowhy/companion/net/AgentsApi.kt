package com.unowhy.companion.net

import org.json.JSONArray
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
    /** Message rapide possible : session au repos dans un terminal Orca (seul moyen fiable d'y écrire). */
    val canSend: Boolean = false,
    /** Limite atteinte : heure de reprise (heure de l'ordinateur, absolue) si elle a pu être lue, et « continue » armé pour cette heure. */
    val resumeAt: Long? = null,
    val resumeArmed: Boolean = false,
    /** Demande à laquelle la tablette peut répondre (permission ou question), tenue ouverte par l'ordinateur. */
    val askId: String? = null,
)

data class AgentCounts(val working: Int, val waiting: Int, val done: Int, val limit: Int, val idle: Int)

/** `now` : l'heure de l'ordinateur à la lecture (les durées se calculent avec elle, pas avec l'horloge de la tablette). */
data class AgentsState(val sessions: List<AgentSession>, val counts: AgentCounts, val total: Int, val hooks: Boolean, val now: Long)

data class AgentOption(val label: String, val description: String)

/** Une question à choix de Claude (`AskUserQuestion`) : `multi` : plusieurs réponses possibles. */
data class AgentQuestion(val header: String, val question: String, val multi: Boolean, val options: List<AgentOption>)

/**
 * Demande en attente d'une réponse : `permission` (le texte est la commande, le fichier ou l'adresse), `question`
 * (`questions`, de 1 à 4) ou `plan` (le texte est le plan, en Markdown brut). `risky` : commande à risque, il faut maintenir le bouton pour l'autoriser. `expiresInMs` : temps
 * restant avant que le terminal reprenne la main. Le texte vient de l'agent : à afficher tel quel.
 */
data class AgentAsk(
    val id: String,
    val kind: String,
    val session: String,
    val dir: String,
    val tool: String,
    val text: String,
    val risky: Boolean,
    val questions: List<AgentQuestion>,
    val expiresInMs: Long,
)

/** Réponse à une question : numéros des options choisies, et/ou texte libre (« Autre »). */
data class AgentAnswer(val choices: List<Int>, val other: String?)

/** Agents Claude Code de l'ordinateur (Mac) : état des sessions, réponses aux demandes, amener une session devant. */
class AgentsApi(private val http: Http) {

    suspend fun state(): AgentsState = http.io { parseAgents(http.get("/agents", timeoutMs = 10_000)) }

    /** La demande en attente, ou null si elle n'existe plus (déjà traitée au clavier, ou expirée). */
    suspend fun ask(id: String): AgentAsk? = http.io {
        try {
            parseAsk(http.get("/agents/ask?id=${http.encode(id)}", timeoutMs = 10_000))
        } catch (e: HttpException) {
            if (e.code == 404) null else throw e
        }
    }

    /** Autorise (`held` : appui maintenu, exigé pour une commande à risque) ou refuse CET appel, rien de plus. */
    suspend fun answerPermission(id: String, allow: Boolean, held: Boolean = false) = http.io {
        val body = JSONObject().put("id", id).put("decision", if (allow) "allow" else "deny")
        if (held) body.put("held", true)
        http.postJson("/agents/answer", body)
        Unit
    }

    /** Plan de Claude : approuver (sortie du mode plan, modifications toujours demandées) ou refuser avec ce qu'il faut changer. */
    suspend fun answerPlan(id: String, approve: Boolean, feedback: String? = null) = http.io {
        val body = JSONObject().put("id", id).put("decision", if (approve) "allow" else "deny")
        if (!feedback.isNullOrBlank()) body.put("feedback", feedback)
        http.postJson("/agents/answer", body)
        Unit
    }

    suspend fun answerQuestions(id: String, answers: List<AgentAnswer>) = http.io {
        val list = JSONArray()
        for (a in answers) {
            list.put(JSONObject().put("choices", JSONArray(a.choices)).apply { a.other?.let { put("other", it) } })
        }
        http.postJson("/agents/answer", JSONObject().put("id", id).put("answers", list))
        Unit
    }

    /** Écrit une ligne dans le terminal d'une session au repos (« continue », une phrase dictée). */
    suspend fun send(session: String, text: String) = http.io {
        http.postJson("/agents/send", JSONObject().put("session", session).put("text", text))
        Unit
    }

    /** Arme ou désarme l'envoi de « continue » à l'heure de reprise d'une limite. */
    suspend fun resume(session: String, armed: Boolean) = http.io {
        http.postJson("/agents/resume", JSONObject().put("session", session).put("armed", armed))
        Unit
    }

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
                canSend = s.optBoolean("canSend"),
                resumeAt = if (s.isNull("resumeAt")) null else s.optLong("resumeAt"),
                resumeArmed = s.optBoolean("resumeArmed"),
                askId = s.optStringOrNull("askId"),
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

internal fun parseAsk(text: String): AgentAsk {
    val json = JSONObject(text)
    val questions = json.optJSONArray("questions")
    return AgentAsk(
        id = json.getString("id"),
        kind = json.getString("kind"),
        session = json.optString("session"),
        dir = json.optString("dir"),
        tool = json.optString("tool"),
        text = json.optString("text"),
        risky = json.optBoolean("risky"),
        questions = questions?.let { list ->
            (0 until list.length()).map { i ->
                val q = list.getJSONObject(i)
                val options = q.getJSONArray("options")
                AgentQuestion(
                    q.optString("header"), q.getString("question"), q.optBoolean("multi"),
                    (0 until options.length()).map { j -> options.getJSONObject(j).let { AgentOption(it.getString("label"), it.optString("description")) } },
                )
            }
        }.orEmpty(),
        expiresInMs = json.optLong("expiresInMs"),
    )
}
