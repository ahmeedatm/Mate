package app.mate.ui.home

import app.mate.net.AgentState
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentsLookTest {
    @Test
    fun `la DEL est allumee quand l'etat demande l'oeil`() {
        assertEquals(true, AgentsLook.lit(AgentState.WAITING, false))
        assertEquals(true, AgentsLook.lit(AgentState.LIMIT, false))
        assertEquals(true, AgentsLook.lit(AgentState.WORKING, false))
    }

    @Test
    fun `la DEL est eteinte quand il n'y a rien a faire`() {
        assertEquals(false, AgentsLook.lit(AgentState.DONE, false))
        assertEquals(false, AgentsLook.lit(AgentState.IDLE, false))
        // Une session qui surveille une tâche de fond n'attend rien de l'utilisateur.
        assertEquals(false, AgentsLook.lit(AgentState.WORKING, true))
    }

    @Test
    fun `un compteur a zero est eteint quel que soit l'etat`() {
        for (state in AgentState.entries) assertEquals("$state", false, AgentsLook.lit(state, false, 0))
        assertEquals(true, AgentsLook.lit(AgentState.WAITING, false, 3))
    }
}
