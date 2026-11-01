package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaApiTest {

    @Test
    fun `les entites gardent l'ordre de l'ordinateur, avec leur genre et leur etat`() {
        val entities = parseHaEntities(
            """{"entities":[
                {"id":"light.salon","name":"Lampe du salon","kind":"toggle","on":true,"available":true},
                {"id":"scene.film","name":"Soirée film","kind":"run","on":false,"available":true},
                {"id":"light.bureau","name":"light.bureau","kind":"toggle","on":false,"available":false}
            ]}""",
        )
        assertEquals(listOf("light.salon", "scene.film", "light.bureau"), entities.map { it.id })
        assertTrue(entities[0].on)
        assertTrue(entities[1].isScene)
        assertFalse(entities[0].isScene)
        assertFalse(entities[2].available)
    }

    @Test
    fun `une reponse vide ou incomplete ne casse pas la liste`() {
        assertEquals(emptyList<HaEntity>(), parseHaEntities("""{}"""))
        val e = parseHaEntities("""{"entities":[{"id":"switch.prise"}]}""").single()
        assertEquals("switch.prise", e.name)
        assertFalse(e.on)
        assertTrue(e.available)
    }

    @Test
    fun `l'ordinateur annonce Home Assistant dans ses fonctions`() {
        assertFalse(parseHomeFeatures("""{"agenda":true}""").ha)
        assertTrue(parseHomeFeatures("""{"ha":true}""").ha)
    }
}
