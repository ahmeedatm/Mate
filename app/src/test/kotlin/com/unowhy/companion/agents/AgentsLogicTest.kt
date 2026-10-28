package com.unowhy.companion.agents

import com.unowhy.companion.net.AgentCounts
import com.unowhy.companion.net.AgentSession
import com.unowhy.companion.net.AgentState
import com.unowhy.companion.net.parseAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentsLogicTest {

    private fun session(id: String, state: AgentState) = AgentSession(id, id, "dir", null, state, 0, null, null, emptyList(), null, null, false)

    @Test
    fun `une permission se distingue d'une question`() {
        assertEquals(Waiting.PERMISSION, waitingKind("permission prompt"))
        assertEquals(Waiting.INPUT, waitingKind("input needed"))
        assertEquals(Waiting.INPUT, waitingKind("dialog open"))
        assertEquals(Waiting.INPUT, waitingKind(null))
    }

    @Test
    fun `la duree se calcule avec l'heure de l'ordinateur puis le temps passe sur la tablette`() {
        assertEquals(90_000, elapsedMs(since = 10_000, serverNow = 70_000, readAtMs = 5_000, nowMs = 35_000))
        // Horloges décalées de plusieurs heures : seul l'écart compte.
        assertEquals(60_000, elapsedMs(since = 1_000_000, serverNow = 1_060_000, readAtMs = 500, nowMs = 500))
        assertEquals(0, elapsedMs(since = 100, serverNow = 50, readAtMs = 0, nowMs = 0))
    }

    @Test
    fun `la duree s'affiche en instant, minutes puis heures`() {
        assertEquals(Age.Now, age(59_999))
        assertEquals(Age.Minutes(1), age(60_000))
        assertEquals(Age.Minutes(59), age(59 * 60_000L + 59_000))
        assertEquals(Age.Hours(1, 0), age(3_600_000))
        assertEquals(Age.Hours(2, 5), age(2 * 3_600_000L + 5 * 60_000L))
    }

    @Test
    fun `la liste courte saute les inactifs et garde l'ordre du compagnon`() {
        val all = listOf(
            session("a", AgentState.WAITING), session("b", AgentState.IDLE), session("c", AgentState.DONE),
            session("d", AgentState.WORKING), session("e", AgentState.WORKING),
        )
        assertEquals(listOf("a", "c", "d"), shortList(all, 3).map { it.id })
        assertTrue(shortList(listOf(session("x", AgentState.IDLE)), 3).isEmpty())
    }

    @Test
    fun `lit la reponse du compagnon`() {
        val state = parseAgents(
            """{"sessions":[
              {"id":"s1","name":"refacto","dir":"projet","branch":"feat/x","state":"waiting","since":1000,"waitingFor":"permission prompt","tool":null,"lines":[],"workedMs":null,"limit":null,"resumeAt":null,"resumeArmed":false,"askId":"a1","askKind":"permission","canSend":false,"canFocus":true},
              {"id":"s2","name":"autre","dir":"autre","branch":null,"state":"done","since":2000,"waitingFor":null,"tool":null,"lines":["Fini.","Trois fichiers."],"workedMs":125000,"limit":null,"canFocus":false}
            ],"counts":{"working":0,"waiting":1,"done":1,"limit":0,"idle":0},"total":2,"hooks":true,"available":true,"now":99000}""",
        )
        assertEquals(AgentCounts(0, 1, 1, 0, 0), state.counts)
        assertEquals(99_000, state.now)
        assertTrue(state.hooks)
        val (waiting, done) = state.sessions
        assertEquals(AgentState.WAITING, waiting.state)
        assertEquals("permission prompt", waiting.waitingFor)
        assertEquals("feat/x", waiting.branch)
        assertTrue(waiting.canFocus)
        assertNull(waiting.tool)
        assertNull(waiting.workedMs)
        assertEquals(listOf("Fini.", "Trois fichiers."), done.lines)
        assertEquals(125_000L, done.workedMs)
        assertNull(done.branch)
        assertFalse(done.canFocus)
    }

    @Test
    fun `lit la limite avec son heure de reprise et le message rapide`() {
        val state = parseAgents(
            """{"sessions":[{"id":"s1","name":"site","dir":"site","branch":null,"state":"limit","since":1,"waitingFor":null,"tool":null,"lines":[],"workedMs":null,
              "limit":"You've hit your limit · resets 3pm","resumeAt":1793194380000,"resumeArmed":true,"askId":null,"askKind":null,"canSend":true,"canFocus":true},
              {"id":"s2","name":"autre","dir":"autre","branch":null,"state":"done","since":1,"waitingFor":null,"tool":null,"lines":[],"workedMs":null,"limit":null,"resumeAt":null,"resumeArmed":false,"canSend":false,"canFocus":false}],
              "counts":{"working":0,"waiting":0,"done":1,"limit":1,"idle":0},"total":2,"hooks":true,"now":1}""",
        )
        val (limit, done) = state.sessions
        assertEquals(AgentState.LIMIT, limit.state)
        assertEquals(1_793_194_380_000L, limit.resumeAt)
        assertTrue(limit.resumeArmed)
        assertTrue(limit.canSend)
        assertEquals("You've hit your limit · resets 3pm", limit.limit)
        assertNull(done.resumeAt)
        assertFalse(done.resumeArmed)
        assertFalse(done.canSend)
    }

    @Test
    fun `un etat inconnu est inactif`() {
        assertEquals(AgentState.IDLE, AgentState.of("autre"))
        assertEquals(AgentState.IDLE, AgentState.of(null))
        assertEquals(AgentState.LIMIT, AgentState.of("limit"))
    }
}
