package com.unowhy.companion.agents

import com.unowhy.companion.net.AgentSession
import com.unowhy.companion.net.AgentState

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

/** Sessions à montrer dans un widget : celles qui demandent l'attention d'abord (le compagnon les trie déjà), `max` au plus. */
fun shortList(sessions: List<AgentSession>, max: Int): List<AgentSession> = sessions.filter { it.state != AgentState.IDLE }.take(max)

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
fun columnCounts(c: com.unowhy.companion.net.AgentCounts): Map<Column, Int> = mapOf(
    Column.WORKING to c.working,
    Column.MONITORING to c.monitoring,
    Column.YOU to c.waiting + c.limit,
    Column.DONE to c.done + c.idle,
)
