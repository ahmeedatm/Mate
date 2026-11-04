package app.mate.net

import org.json.JSONArray
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

/** Un stage du pipeline d'une MR ou d'une planification : son nom et son état, réduit comme celui du pipeline. */
data class GitLabStage(val name: String, val status: Pipeline)

/**
 * Colonne du tableau des MR (le compagnon décide, backend/src/gitlab.ts) : mes MR, une colonne chacune (brouillon, à fixer,
 * prête à merger, en cours, prête pour relecture), et `REVIEW` : MR que je dois relire. L'ordre est celui de l'affichage :
 * prête à merger, à fixer, à relire, prête pour relecture, puis le reste.
 */
enum class MrColumn(val key: String) {
    MERGE("merge"),
    FIX("fix"),
    REVIEW("review"),
    READY("ready"),
    RUNNING("running"),
    DRAFT("draft"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
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
    val column: MrColumn = MrColumn.READY,
    val stages: List<GitLabStage> = emptyList(),
)

/**
 * `mine` : MR dont l'utilisateur connecté est assigné ; `review` : MR qu'il doit relire. `connected` faux : l'ordinateur n'a
 * ni compte GitLab connecté ni jeton (réponse normale, pas une erreur) ; `expired` : le compte a été connecté mais GitLab
 * refuse de le renouveler. `canMerge` : l'ordinateur a un accès en écriture, la tablette peut fusionner ; sinon elle renvoie
 * vers l'ordinateur (absent chez un ancien compagnon : vrai, comme avant).
 */
data class GitLabMrs(
    val mine: List<GitLabMr>,
    val review: List<GitLabMr>,
    val connected: Boolean = true,
    val expired: Boolean = false,
    val canMerge: Boolean = true,
)

/**
 * Un pipeline de la console (backend `GET /gitlab/pipelines`) : dernier run d'une planification choisie, ou pipeline d'une de
 * mes MR (`mrIid` renseigné). `title` : description de la planification ou titre de la MR. `durationSec` : durée du pipeline
 * terminé (null tant qu'il tourne) ; `ageSec` : âge à la lecture du compagnon, depuis la fin (ou le départ s'il tourne) ;
 * `failedJobs` : jobs en échec, vide hors échec.
 */
data class GitLabPipeline(
    val project: String,
    val ref: String,
    /** Long : les identifiants de runs GitHub Actions dépassent un Int. */
    val id: Long,
    val status: Pipeline,
    val durationSec: Int?,
    val ageSec: Int,
    val failedJobs: List<String>,
    val title: String = "",
    val mrIid: Int? = null,
    val stages: List<GitLabStage> = emptyList(),
)

/**
 * GitLab : `schedules` : dernier run des planifications choisies ; `mrs` : pipelines de mes MR. GitHub : `pipelines`, la liste
 * à plat des derniers runs. `connected` faux : l'ordinateur n'a ni compte connecté ni jeton (réponse normale) ; `expired` :
 * le compte n'est plus renouvelable.
 */
data class GitLabPipelines(
    val schedules: List<GitLabPipeline> = emptyList(),
    val mrs: List<GitLabPipeline> = emptyList(),
    val connected: Boolean = true,
    val expired: Boolean = false,
    val pipelines: List<GitLabPipeline> = emptyList(),
)

/** Une planification de pipelines d'un projet, proposée au choix. */
data class ScheduleChoice(val id: Int, val description: String, val ref: String, val active: Boolean)

/** Planifications d'un projet GitLab dont l'utilisateur est membre. */
data class ScheduleProject(val project: String, val schedules: List<ScheduleChoice>)

/** Une planification choisie : le projet et son numéro. */
data class ScheduleRef(val project: String, val id: Int)

/** Planifications proposées et choix en cours, dans l'ordre. `connected` faux : pas de compte GitLab sur l'ordinateur. */
data class ScheduleChoices(
    val projects: List<ScheduleProject>,
    val selected: List<ScheduleRef>,
    val connected: Boolean = true,
    val expired: Boolean = false,
)

/** MR GitLab lues et pilotées par le compagnon de l'ordinateur (jamais de jeton ni de compte GitLab sur la tablette). */
class GitLabApi(private val http: Http) : ForgeApi {

    override suspend fun mrs(): GitLabMrs = http.io { parseGitLabMrs(http.get("/gitlab/mrs", timeoutMs = 20_000)) }

    override suspend fun pipelines(): GitLabPipelines = http.io { parseGitLabPipelines(http.get("/gitlab/pipelines", timeoutMs = 20_000)) }

    /** Planifications des projets de l'utilisateur et choix en cours ; la première lecture peut durer (centaines de projets). */
    suspend fun scheduleChoices(): ScheduleChoices = http.io { parseScheduleChoices(http.get("/gitlab/schedules", timeoutMs = 60_000)) }

    /** Garde le choix sur l'ordinateur ; le widget Pipelines le relit ensuite. */
    suspend fun saveSchedules(refs: List<ScheduleRef>) = http.io {
        val array = JSONArray()
        for (r in refs) array.put(JSONObject().put("project", r.project).put("id", r.id))
        http.postJson("/gitlab/schedules", JSONObject().put("schedules", array))
        Unit
    }

    /** Ouvre la page du pipeline dans le navigateur de l'ordinateur. */
    override suspend fun openPipeline(project: String, id: Long) = http.io {
        http.postJson("/gitlab/pipelines/open", JSONObject().put("project", project).put("id", id))
        Unit
    }

    /** Ouvre la MR dans le navigateur de l'ordinateur. */
    override suspend fun open(project: String, iid: Int) = http.io {
        http.postJson("/gitlab/mrs/open", JSONObject().put("project", project).put("iid", iid))
        Unit
    }

    /** Fusionne la MR si son commit est encore `sha` (GitLab refuse sinon). */
    override suspend fun merge(project: String, iid: Int, sha: String) = http.io {
        http.postJson("/gitlab/mrs/merge", JSONObject().put("project", project).put("iid", iid).put("sha", sha))
        Unit
    }
}

private fun JSONObject.stages(): List<GitLabStage> {
    val a = optJSONArray("stages") ?: return emptyList()
    return (0 until a.length()).map { i ->
        val st = a.getJSONObject(i)
        GitLabStage(st.optString("name"), Pipeline.of(st.optStringOrNull("status")))
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
                // Ancien compagnon, sans colonne : brouillon, sinon prête pour review ; la liste « à relire » reste la sienne.
                column = MrColumn.of(m.optStringOrNull("column"))
                    ?: if (key == "review") MrColumn.REVIEW else if (m.optBoolean("draft")) MrColumn.DRAFT else MrColumn.READY,
                stages = m.stages(),
            )
        }
    }
    return GitLabMrs(list("mine"), list("review"), json.optBoolean("connected", true), json.optBoolean("expired"), json.optBoolean("canMerge", true))
}

internal fun parseGitLabPipelines(text: String): GitLabPipelines {
    val json = JSONObject(text)
    fun list(key: String): List<GitLabPipeline> {
        val a = json.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            val jobs = p.optJSONArray("failedJobs")
            GitLabPipeline(
                project = p.getString("project"),
                ref = p.optString("ref"),
                id = p.getLong("id"),
                status = Pipeline.of(p.optStringOrNull("pipeline")),
                durationSec = if (p.isNull("durationSec")) null else p.optInt("durationSec"),
                ageSec = p.optInt("ageSec"),
                failedJobs = jobs?.let { j -> (0 until j.length()).map { j.getString(it) } }.orEmpty(),
                title = p.optString("title"),
                mrIid = if (p.isNull("mrIid")) null else p.optInt("mrIid"),
                stages = p.stages(),
            )
        }
    }
    return GitLabPipelines(
        schedules = list("schedules"),
        mrs = list("mrs"),
        connected = json.optBoolean("connected", true),
        expired = json.optBoolean("expired"),
        pipelines = list("pipelines"),
    )
}

internal fun parseScheduleChoices(text: String): ScheduleChoices {
    val json = JSONObject(text)
    val projects = json.optJSONArray("projects")
    val selected = json.optJSONArray("selected")
    return ScheduleChoices(
        projects = (0 until (projects?.length() ?: 0)).map { i ->
            val p = projects!!.getJSONObject(i)
            val schedules = p.optJSONArray("schedules")
            ScheduleProject(
                p.getString("project"),
                (0 until (schedules?.length() ?: 0)).map { j ->
                    val s = schedules!!.getJSONObject(j)
                    ScheduleChoice(s.getInt("id"), s.optString("description"), s.optString("ref"), s.optBoolean("active"))
                },
            )
        },
        selected = (0 until (selected?.length() ?: 0)).map { i ->
            val s = selected!!.getJSONObject(i)
            ScheduleRef(s.getString("project"), s.getInt("id"))
        },
        connected = json.optBoolean("connected", true),
        expired = json.optBoolean("expired"),
    )
}
