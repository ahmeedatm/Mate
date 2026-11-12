package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `une lumiere reglable porte sa luminosite et sa couleur, une prise ou une lumiere simple rien`() {
        val (salon, ruban, applique, prise) = parseHaEntities(
            """{"entities":[
                {"id":"light.salon","kind":"toggle","on":true,"light":{"color":true,"brightness":50,"rgb":[255,120,0]}},
                {"id":"light.ruban","kind":"toggle","on":false,"light":{"color":false,"brightness":null,"rgb":null}},
                {"id":"light.applique","kind":"toggle","on":true},
                {"id":"switch.prise","kind":"toggle","on":true}
            ]}""",
        )
        assertEquals(HaLight(color = true, brightness = 50, rgb = 0xFF7800), salon.light)
        assertEquals(HaLight(color = false, brightness = null, rgb = null), ruban.light)
        assertNull(applique.light)
        assertNull(prise.light)
    }

    @Test
    fun `le reglage envoie la luminosite et la couleur en octets`() {
        assertEquals("""{"brightness":40,"rgb":[0,128,255]}""", haLightBody(brightness = 40, rgb = 0x0080FF).toString())
        assertEquals("""{"brightness":100}""", haLightBody(brightness = 100, rgb = null).toString())
    }

    @Test
    fun `l'ordinateur annonce Home Assistant dans ses fonctions`() {
        assertFalse(parseHomeFeatures("""{"agenda":true}""").ha)
        assertTrue(parseHomeFeatures("""{"ha":true}""").ha)
    }
}
