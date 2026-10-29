package com.unowhy.companion.net

import org.json.JSONObject

/** État du pipeline d'une MR, comme le compagnon l'annonce (backend/src/gitlab.ts). */
enum class Pipeline(val key: String) {
    SUCCESS("success"),
    RUNNING("running"),
    FAILED("failed"),
    CANCELED("canceled"),
    NONE("none"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** Une MR ouverte. `statusLabel` : phrase française de l'état de fusion ; `sha` : commit affiché, renvoyé à la fusion. */
data class GitLabMr(
    val project: String,
    val iid: Int,
    val title: String,
    val draft: Boolean,
    val author: String,
    val pipeline: Pipeline,
    val approvalsGiven: Int,
    val approvalsRequired: Int,
    val approvedBy: List<String>,
    val statusLabel: String,
    val mergeable: Boolean,
    val sha: String,
)

/**
 * `mine` : MR dont l'utilisateur connecté est assigné ; `review` : MR qu'il doit relire. `connected` faux : l'ordinateur n'a
 * ni compte GitLab connecté ni jeton (réponse normale, pas une erreur) ; `expired` : le compte a été connecté mais GitLab
 * refuse de le renouveler.
 */
data class GitLabMrs(
    val mine: List<GitLabMr>,
    val review: List<GitLabMr>,
    val connected: Boolean = true,
    val expired: Boolean = false,
)

/** MR GitLab lues et pilotées par le compagnon de l'ordinateur (jamais de jeton ni de compte GitLab sur la tablette). */
class GitLabApi(private val http: Http) {

    suspend fun mrs(): GitLabMrs = http.io { parseGitLabMrs(http.get("/gitlab/mrs", timeoutMs = 20_000)) }

    /** Ouvre la MR dans le navigateur de l'ordinateur. */
    suspend fun open(project: String, iid: Int) = http.io {
        http.postJson("/gitlab/mrs/open", JSONObject().put("project", project).put("iid", iid))
        Unit
    }

    /** Fusionne la MR si son commit est encore `sha` (GitLab refuse sinon). */
    suspend fun merge(project: String, iid: Int, sha: String) = http.io {
        http.postJson("/gitlab/mrs/merge", JSONObject().put("project", project).put("iid", iid).put("sha", sha))
        Unit
    }
}

internal fun parseGitLabMrs(text: String): GitLabMrs {
    val json = JSONObject(text)
    fun list(key: String): List<GitLabMr> {
        val a = json.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            val approvals = m.optJSONObject("approvals")
            val by = approvals?.optJSONArray("by")
            GitLabMr(
                project = m.getString("project"),
                iid = m.getInt("iid"),
                title = m.optString("title"),
                draft = m.optBoolean("draft"),
                author = m.optString("author"),
                pipeline = Pipeline.of(m.optStringOrNull("pipeline")),
                approvalsGiven = approvals?.optInt("given") ?: 0,
                approvalsRequired = approvals?.optInt("required") ?: 0,
                approvedBy = by?.let { b -> (0 until b.length()).map { b.getString(it) } }.orEmpty(),
                statusLabel = m.optString("statusLabel"),
                mergeable = m.optBoolean("mergeable"),
                sha = m.optString("sha"),
            )
        }
    }
    return GitLabMrs(list("mine"), list("review"), json.optBoolean("connected", true), json.optBoolean("expired"))
}
