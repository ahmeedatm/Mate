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

    @Test
    fun `voie sans son - une appli choisie absente, jamais la sortie ni le micro`() {
        val live = listOf(discord)
        assertTrue(MixerApps.isSilent("valorant", live))
        assertFalse(MixerApps.isSilent("discord", live))
        assertFalse(MixerApps.isSilent("master", live))
        assertFalse(MixerApps.isSilent("mic", live))
        assertFalse("Mac : rien n'est muet faute de liste", MixerApps.isSilent("valorant", null))
    }

    // --- Groupes : plusieurs applis sur une voie

    private val spotify = MixerChannel("spotify", "Spotify", 40, true, 10)
    private val jeu = MixerApps.group("g1", "Jeu", listOf(MixerApps.App("valorant", "Valorant"), MixerApps.App("discord", "Discord")))

    @Test
    fun `un groupe se range et se relit avec ses membres`() {
        val config = JSONObject()
        assertTrue(MixerApps.write(config, "pc-1", listOf(valorant, jeu)))
        assertEquals(listOf(valorant, jeu), MixerApps.read(config, "pc-1"))
        assertTrue(MixerApps.read(config, "pc-1")[1].isGroup)
        assertFalse(MixerApps.read(config, "pc-1")[0].isGroup)
    }

    @Test
    fun `la voie d'un groupe prend le plus fort volume, le plus fort niveau, et coupe si tous sont coupes`() {
        val group = MixerApps.channels(listOf(jeu), listOf(discord, firefox))!!.single()
        assertEquals("group:g1", group.id)
        assertEquals("Jeu", group.name)
        assertEquals("seul Discord joue", 80, group.volume)
        assertEquals(30, group.level)
        assertFalse(group.muted)

        val both = MixerApps.channels(listOf(jeu), listOf(discord.copy(muted = true), MixerChannel("valorant", "Valorant", 100, true, 5)))!!.single()
        assertEquals(100, both.volume)
        assertTrue("tous coupés : le groupe est coupé", both.muted)
        assertFalse("un seul coupé : le groupe sonne encore", MixerApps.channels(listOf(jeu), listOf(discord.copy(muted = true), MixerChannel("valorant", "Valorant", 100, false, 5)))!!.single().muted)
    }

    @Test
    fun `groupe dont aucun membre ne joue - grise, ne se pilote pas`() {
        val group = MixerApps.channels(listOf(jeu), listOf(firefox))!!.single()
        assertNull(group.volume)
        assertTrue(MixerApps.isSilent(group, listOf(firefox)))
        assertFalse(MixerApps.isSilent(MixerApps.channels(listOf(jeu), listOf(discord))!!.single(), listOf(discord)))
    }

    @Test
    fun `membres a regler pour une voie - ceux du groupe, rien pour une simple appli`() {
        assertEquals(listOf("valorant", "discord"), MixerApps.membersOf(listOf(valorant, jeu), "group:g1"))
        assertNull(MixerApps.membersOf(listOf(valorant, jeu), "valorant"))
        assertNull(MixerApps.membersOf(emptyList(), "group:g1"))
    }

    @Test
    fun `mac - un groupe n'a pas de voie non plus`() {
        assertNull(MixerApps.channels(listOf(jeu), null))
    }

    @Test
    fun `le groupe est propose dans la liste avec les choisies, ses membres restent proposes`() {
        assertEquals(
            listOf(jeu, MixerApps.App("discord", "Discord"), MixerApps.App("firefox", "Firefox")),
            MixerApps.candidates(listOf(jeu), listOf(discord, firefox)),
        )
    }

    // --- Voies « premier plan » et « autres »

    private val front = MixerApps.front("Premier plan")
    private val others = MixerApps.others("Autres applis")

    @Test
    fun `la voie du premier plan suit l'appli devant, avec son nom`() {
        val live = listOf(discord, firefox)
        val onDiscord = MixerApps.channels(listOf(front), live, "discord")!!.single()
        assertEquals(MixerApps.FRONT_ID, onDiscord.id)
        assertEquals("Discord", onDiscord.name)
        assertEquals(80, onDiscord.volume)
        assertEquals(30, onDiscord.level)
        assertEquals("Firefox", MixerApps.channels(listOf(front), live, "firefox")!!.single().name)
    }

    @Test
    fun `premier plan sans son ou inconnu - grise, garde son nom par defaut`() {
        val live = listOf(discord)
        listOf("explorer", null).forEach { devant ->
            val channel = MixerApps.channels(listOf(front), live, devant)!!.single()
            assertNull(channel.volume)
            assertEquals("Premier plan", channel.name)
            assertTrue(MixerApps.isSilent(channel, live))
        }
    }

    @Test
    fun `la voie autres regroupe les applis sans voie, ni la sortie, ni le systeme`() {
        val system = MixerChannel("system", "", 100, false, 0)
        val live = listOf(discord, firefox, spotify, system)
        val shown = MixerApps.channels(listOf(valorant, others), live, null)!!
        val rest = shown[1]
        assertEquals(MixerApps.OTHERS_ID, rest.id)
        assertEquals("Autres applis", rest.name)
        assertEquals("le plus fort volume de Discord, Firefox, Spotify", 100, rest.volume)
        assertEquals(30, rest.level)
        assertFalse("Firefox n'est pas coupé", rest.muted)
        assertEquals(listOf("discord", "firefox", "spotify"), MixerApps.membersOf(listOf(valorant, others), MixerApps.OTHERS_ID, live, null))
    }

    @Test
    fun `autres laisse de cote les applis choisies, celles d'un groupe et celle du premier plan`() {
        val live = listOf(discord, firefox, spotify)
        val chosen = listOf(MixerApps.App("spotify", "Spotify"), front, others)
        assertEquals(listOf("discord"), MixerApps.membersOf(chosen, MixerApps.OTHERS_ID, live, "firefox"))
        assertEquals("sans voie premier plan, Firefox reste dans autres", listOf("discord", "firefox"), MixerApps.membersOf(listOf(MixerApps.App("spotify", "Spotify"), others), MixerApps.OTHERS_ID, live, "firefox"))
        assertEquals("discord est dans le groupe", listOf("firefox", "spotify"), MixerApps.membersOf(listOf(jeu, others), MixerApps.OTHERS_ID, live, null))
    }

    @Test
    fun `autres sans aucune appli a regrouper - grisee`() {
        val channel = MixerApps.channels(listOf(MixerApps.App("discord", "Discord"), others), listOf(discord), null)!![1]
        assertNull(channel.volume)
        assertTrue(MixerApps.isSilent(channel, listOf(discord)))
    }

    @Test
    fun `autres coupee seulement si toutes ses applis le sont`() {
        val live = listOf(discord.copy(muted = true), spotify.copy(muted = false))
        assertFalse(MixerApps.channels(listOf(others), live, null)!!.single().muted)
        assertTrue(MixerApps.channels(listOf(others), listOf(discord.copy(muted = true), spotify.copy(muted = true)), null)!!.single().muted)
    }

    @Test
    fun `toucher la voie du premier plan regle l'appli devant, rien si aucune`() {
        val live = listOf(discord, firefox)
        assertEquals(listOf("discord"), MixerApps.membersOf(listOf(front), MixerApps.FRONT_ID, live, "discord"))
        assertEquals(emptyList<String>(), MixerApps.membersOf(listOf(front), MixerApps.FRONT_ID, live, "explorer"))
        assertEquals(emptyList<String>(), MixerApps.membersOf(listOf(front), MixerApps.FRONT_ID, live, null))
    }

    @Test
    fun `les deux voies se rangent et se relisent, sans membres`() {
        val config = JSONObject()
        assertTrue(MixerApps.write(config, "pc-1", listOf(front, valorant, others)))
        assertEquals(listOf(front, valorant, others), MixerApps.read(config, "pc-1"))
        assertFalse(MixerApps.read(config, "pc-1")[0].isGroup)
        assertTrue(MixerApps.read(config, "pc-1")[0].isVirtual)
    }

    @Test
    fun `propositions - les deux voies viennent apres les choisies, une fois chacune`() {
        val specials = listOf(front, others)
        assertEquals(listOf(valorant, front, others, MixerApps.App("discord", "Discord")), MixerApps.candidates(listOf(valorant), listOf(discord), { it.name }, specials))
        assertEquals(listOf(others, front), MixerApps.candidates(listOf(others, front), emptyList(), { it.name }, specials))
    }
}
