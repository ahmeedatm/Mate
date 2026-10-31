package com.unowhy.unomate.net

import org.json.JSONObject

/**
 * Pull requests et runs GitHub Actions lus et pilotés par le compagnon de l'ordinateur (backend/src/github.ts) : mêmes
 * formes que GitLab (`project` = `propriétaire/dépôt`, `iid` = numéro de la PR). Jamais de jeton ni de compte GitHub sur la tablette.
 */
class GitHubApi(private val http: Http) : ForgeApi {

    override suspend fun mrs(): GitLabMrs = http.io { parseGitLabMrs(http.get("/github/prs", timeoutMs = 20_000)) }

    override suspend fun pipelines(): GitLabPipelines = http.io { parseGitLabPipelines(http.get("/github/actions", timeoutMs = 20_000)) }

    /** Ouvre la page du run dans le navigateur de l'ordinateur. */
    override suspend fun openPipeline(project: String, id: Long) = http.io {
        http.postJson("/github/actions/open", JSONObject().put("project", project).put("id", id))
        Unit
    }

    /** Ouvre la PR dans le navigateur de l'ordinateur. */
    override suspend fun open(project: String, iid: Int) = http.io {
        http.postJson("/github/prs/open", JSONObject().put("project", project).put("iid", iid))
        Unit
    }

    /** Fusionne la PR si son commit est encore `sha` (GitHub refuse sinon). */
    override suspend fun merge(project: String, iid: Int, sha: String) = http.io {
        http.postJson("/github/prs/merge", JSONObject().put("project", project).put("iid", iid).put("sha", sha))
        Unit
    }
}
