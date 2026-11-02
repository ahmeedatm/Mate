package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantApiTest {
    @Test
    fun readsTheMatchWithBothTeams() {
        val v = ValorantApi.parse(
            """{"state":"ingame","queue":"Compétition","map":"Summit","score":{"ally":2,"enemy":0},
                "me":{"name":"Eliot#EUW","tier":10,"rank":"Argent 2","rr":42,"level":90},
                "players":[
                  {"name":"Eliot#EUW","agent":"Sova","locked":true,"tier":10,"rank":"Argent 2","rr":42,"ally":true,"me":true},
                  {"name":null,"agent":null,"locked":true,"tier":9,"rank":"Argent 1","rr":null,"ally":false,"me":false}
                ]}""",
        )
        assertEquals(ValorantState.INGAME, v.state)
        assertEquals("Summit", v.map)
        assertEquals(2, v.score?.ally)
        assertEquals(0, v.score?.enemy)
        assertEquals("Argent 2", v.me?.rank)
        assertEquals(42, v.me?.rr)
        assertEquals(listOf("Eliot#EUW"), v.allies.map { it.name })
        assertTrue(v.allies.single().me)
        val hidden = v.enemies.single()
        assertNull(hidden.name)
        assertNull(hidden.agent)
        assertNull(hidden.rr)
        assertEquals(9, hidden.tier)
    }

    @Test
    fun closedGameHasNoPlayerAndNoMe() {
        val v = ValorantApi.parse("""{"state":"off","queue":null,"map":null,"score":null,"me":null,"players":[]}""")
        assertEquals(ValorantState.OFF, v.state)
        assertNull(v.me)
        assertNull(v.score)
        assertNull(v.queue)
        assertTrue(v.allies.isEmpty() && v.enemies.isEmpty())
    }

    @Test
    fun unknownStateFromANewerComputerIsTheMenu() {
        assertEquals(ValorantState.MENU, ValorantApi.parse("""{"state":"replay","players":[]}""").state)
    }

    @Test
    fun agentNotYetLockedDuringSelection() {
        val v = ValorantApi.parse(
            """{"state":"pregame","players":[{"name":"Kinj#EUW","agent":"Jett","locked":false,"tier":12,"rank":"Or 1","rr":15,"ally":true,"me":false}]}""",
        )
        assertEquals(ValorantState.PREGAME, v.state)
        assertFalse(v.allies.single().locked)
        assertEquals("Jett", v.allies.single().agent)
    }
}
