package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

/** Ticket Jira de l'utilisateur. `priority` : null si Jira n'en donne pas ; `done` : terminé récemment (atténué, touchable). */
data class JiraIssue(val key: String, val summary: String, val type: String, val priority: String?, val done: Boolean)

/** Colonne d'un tableau Jira choisi (vide possible), déjà rangée par l'ordinateur. */
data class JiraColumn(val title: String, val issues: List<JiraIssue>)

/** Tableau Jira proposé au choix. */
data class JiraBoard(val id: Int, val name: String, val project: String)

/** `boardsChosen` faux : aucun tableau choisi, les colonnes sont alors des statuts. */
data class JiraIssues(val boardsChosen: Boolean, val columns: List<JiraColumn>)

/** Tableaux proposés par l'ordinateur et ids cochés, dans l'ordre des colonnes. */
data class JiraBoards(val boards: List<JiraBoard>, val selected: List<Int>)

/** `to` : statut d'arrivée ; `needsJira` : la transition demande des champs, à finir dans Jira. */
data class JiraTransition(val id: String, val name: String, val to: String, val needsJira: Boolean)

private fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { block(getJSONObject(it)) }

fun parseJiraIssues(json: String): JiraIssues {
    val root = JSONObject(json)
    return JiraIssues(
        root.optBoolean("boardsChosen"),
        root.optJSONArray("columns").mapObjects { c ->
            JiraColumn(
                c.getString("title"),
                c.optJSONArray("issues").mapObjects {
                    JiraIssue(it.getString("key"), it.optString("summary"), it.optString("type"), it.optStringOrNull("priority"), it.optBoolean("done"))
                },
            )
        },
    )
}

fun parseJiraBoards(json: String): JiraBoards {
    val root = JSONObject(json)
    return JiraBoards(
        root.optJSONArray("boards").mapObjects { JiraBoard(it.getInt("id"), it.optString("name"), it.optString("project")) },
        root.optJSONArray("selected")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
    )
}

/** Tableaux groupés par projet, projets dans l'ordre où ils apparaissent, tableaux dans l'ordre reçu. */
fun groupBoardsByProject(boards: List<JiraBoard>): List<Pair<String, List<JiraBoard>>> =
    boards.groupBy { it.project }.toList()

/** Ids cochés dans l'ordre de la liste affichée (groupée par projet) : le premier donne l'ordre des colonnes. */
fun checkedBoardIds(boards: List<JiraBoard>, checked: Set<Int>): List<Int> =
    groupBoardsByProject(boards).flatMap { it.second }.map { it.id }.filter { it in checked }

fun parseJiraTransitions(json: String): List<JiraTransition> =
    JSONObject(json).optJSONArray("transitions").mapObjects {
        JiraTransition(it.getString("id"), it.getString("name"), it.optString("to"), it.optBoolean("needsJira"))
    }

/** Jira de l'ordinateur (si configuré) : tickets assignés (colonnes des tableaux choisis), transitions, ouverture dans le navigateur. */
class JiraApi(private val http: Http) {

    suspend fun issues(): JiraIssues = http.io { parseJiraIssues(http.get("/jira/issues", timeoutMs = 20_000)) }

    suspend fun boards(): JiraBoards = http.io { parseJiraBoards(http.get("/jira/boards", timeoutMs = 30_000)) }

    suspend fun saveBoards(ids: List<Int>) = http.io {
        http.postJson("/jira/boards", JSONObject().put("ids", JSONArray(ids)))
        Unit
    }

    suspend fun transitions(key: String): List<JiraTransition> =
        http.io { parseJiraTransitions(http.get("/jira/issues/${http.encode(key)}/transitions", timeoutMs = 20_000)) }

    /** 409 si la transition demande des champs : le message de l'ordinateur le dit (voir `errorText`). */
    suspend fun transition(key: String, id: String) = http.io {
        http.postJson("/jira/issues/${http.encode(key)}/transitions", JSONObject().put("id", id))
        Unit
    }

    suspend fun openOnComputer(key: String) = http.io { http.postJson("/jira/issues/${http.encode(key)}/open", JSONObject()); Unit }
}
