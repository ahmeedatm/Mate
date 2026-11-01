package app.mate.ui.mixer

import app.mate.net.MixerChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Applis choisies d'une table de mixage : gardées par ordinateur dans la config du widget, dans l'ordre voulu. */
class MixerAppsTest {
    private val discord = MixerChannel("discord", "Discord", 80, false, 30)
    private val firefox = MixerChannel("firefox", "Firefox", 100, false, 0)
    private val valorant = MixerApps.App("valorant", "Valorant")

    @Test
    fun `sans choix, toutes les applis`() {
        assertEquals(listOf(discord, firefox), MixerApps.channels(emptyList(), listOf(discord, firefox)))
    }

    @Test
    fun `le choix dans son ordre, une appli muette grisee a sa place`() {
        val shown = MixerApps.channels(listOf(valorant, MixerApps.App("discord", "Discord")), listOf(discord, firefox))!!
        assertEquals(listOf("valorant", "discord"), shown.map { it.id })
        assertNull("pas de son : ne se pilote pas", shown[0].volume)
        assertEquals("Valorant", shown[0].name)
        assertEquals(discord, shown[1])
    }

    @Test
    fun `mac sans volume par appli - rien a choisir`() {
        assertNull(MixerApps.channels(listOf(valorant), null))
    }

    @Test
    fun `propositions - les choisies d'abord, puis celles qui jouent`() {
        assertEquals(
            listOf(valorant, MixerApps.App("discord", "Discord"), MixerApps.App("firefox", "Firefox")),
            MixerApps.candidates(listOf(valorant, MixerApps.App("discord", "Discord")), listOf(discord, firefox)),
        )
    }

    @Test
    fun `range par ordinateur, relu tel quel`() {
        val config = JSONObject()
        assertTrue(MixerApps.write(config, "pc-1", listOf(valorant)))
        assertFalse("rien de changé", MixerApps.write(config, "pc-1", listOf(valorant)))
        assertEquals(listOf(valorant), MixerApps.read(config, "pc-1"))
        assertEquals(emptyList<MixerApps.App>(), MixerApps.read(config, "pc-2"))
        assertEquals(emptyList<MixerApps.App>(), MixerApps.read(config, null))
    }
}
