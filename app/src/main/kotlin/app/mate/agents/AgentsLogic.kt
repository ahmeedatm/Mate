package app.mate.agents

import app.mate.net.AgentCounts
import app.mate.net.AgentSession
import app.mate.net.AgentState
import app.mate.net.AgentsState
import java.util.Calendar
import java.util.TimeZone

/** Ce que Claude attend, d'après `waitingFor` de `claude agents --json` (`permission prompt`, `input needed`…). */
enum class Waiting { PERMISSION, INPUT }

fun waitingKind(waitingFor: String?): Waiting = if (waitingFor?.contains("permission", ignoreCase = true) == true) Waiting.PERMISSION else Waiting.INPUT

/** Durée écoulée depuis `since`, en heure de l'ordinateur (`serverNow`) puis le temps passé sur la tablette depuis la lecture. */
fun elapsedMs(since: Long, serverNow: Long, readAtMs: Long, nowMs: Long): Long = (serverNow - since + (nowMs - readAtMs)).coerceAtLeast(0)

/** Durée à afficher : « à l'instant » sous la minute, sinon minutes, puis heures et minutes. */
sealed class Age {
    object Now : Age()
    data class Minutes(val minutes: Int) : Age()
    data class Hours(val hours: Int, val minutes: Int) : Age()
}

fun age(ms: Long): Age {
    val minutes = (ms / 60_000).toInt()
    return when {
        minutes < 1 -> Age.Now
        minutes < 60 -> Age.Minutes(minutes)
        else -> Age.Hours(minutes / 60, minutes % 60)
    }
}

/** Jours entre `now` et la reprise d'une fenêtre de quota, au calendrier (0 = aujourd'hui, 1 = demain). */
fun daysUntil(resetsAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
    fun day(ms: Long) = Calendar.getInstance(zone).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((day(resetsAt) - day(now)) / 86_400_000.0).toInt().coerceAtLeast(0)
}

/** Sessions à montrer dans un widget : celles qui demandent l'attention d'abord (le compagnon les trie déjà), `max` au plus. */
fun shortList(sessions: List<AgentSession>, max: Int): List<AgentSession> = sessions.filter { it.state != AgentState.IDLE }.take(max)

/**
 * Titre d'une session à l'écran. Le titre automatique résume ce que fait l'agent : en mode privé il est remplacé par le
 * dossier de la session (la branche, le ticket et la MR restent montrés à côté).
 */
fun sessionTitle(s: AgentSession, private: Boolean): String = if (private) s.dir else s.title

/**
 * Colonnes du tableau de l'onglet Agents : travaillent, surveillent (tâche de fond), vous concerne (une réponse ou une
 * limite à lever), finis (sessions au repos comprises).
 */
enum class Column { WORKING, MONITORING, YOU, DONE }

fun columnOf(s: AgentSession): Column = when (s.state) {
    AgentState.WORKING -> if (s.monitoring) Column.MONITORING else Column.WORKING
    AgentState.WAITING, AgentState.LIMIT -> Column.YOU
    AgentState.DONE, AgentState.IDLE -> Column.DONE
}

/** Nombre de sessions par colonne, d'après les compteurs du compagnon (toutes les sessions, pas seulement les 10 montrées). */
fun columnCounts(c: app.mate.net.AgentCounts): Map<Column, Int> = mapOf(
    Column.WORKING to c.working,
    Column.MONITORING to c.monitoring,
    Column.YOU to c.waiting + c.limit,
    Column.DONE to c.done + c.idle,
)

/** Sessions des projets cachés sur la tablette retirées, compteurs et total refaits sur ce qui reste. */
fun AgentsState.without(hidden: Set<String>): AgentsState {
    if (hidden.isEmpty()) return this
    val kept = sessions.filter { it.project !in hidden }
    fun count(state: AgentState, monitoring: Boolean = false) = kept.count { it.state == state && (state != AgentState.WORKING || it.monitoring == monitoring) }
    return copy(
        sessions = kept,
        counts = AgentCounts(
            working = count(AgentState.WORKING),
            waiting = count(AgentState.WAITING),
            done = count(AgentState.DONE),
            limit = count(AgentState.LIMIT),
            idle = count(AgentState.IDLE),
            monitoring = count(AgentState.WORKING, monitoring = true),
        ),
        total = kept.size,
    )
}

/** Projets du menu ⋯ : ceux des sessions et ceux déjà cachés (pour pouvoir les remontrer), par ordre alphabétique. */
fun projectsOf(state: AgentsState, hidden: Set<String>): List<String> =
    (state.sessions.map { it.project } + hidden).filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
