package com.unowhy.companion.voice

import com.unowhy.companion.net.DeckTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class VoiceCommandsTest {

    private val tiles = listOf(
        DeckTile("t1", "Spotify", "app", null, null),
        DeckTile("t2", "Quitter l'appel", "keys", null, null),
    )

    private fun parse(text: String, now: Calendar = Calendar.getInstance()) = VoiceCommands.parse(text, tiles, now)

    @Test
    fun `normalise accents majuscules et ponctuation`() {
        assertEquals("ouvre l accueil", VoiceCommands.normalize("Ouvre l'Accueil !"))
    }

    @Test
    fun `ouvre un mode`() {
        assertEquals(VoiceCommand.ShowMode("home"), parse("Ouvre l'accueil"))
        assertEquals(VoiceCommand.ShowMode("settings"), parse("va dans les réglages"))
    }

    @Test
    fun `son et micro`() {
        assertEquals(VoiceCommand.Volume(level = 40), parse("volume à 40"))
        assertEquals(VoiceCommand.Volume(delta = 10), parse("plus fort"))
        assertEquals(VoiceCommand.Mic(true), parse("coupe le micro"))
        assertEquals(VoiceCommand.Mute(true), parse("coupe le son"))
    }

    @Test
    fun `focus avec une duree`() {
        assertEquals(VoiceCommand.Focus("start", 25), parse("lance un focus de 25 minutes"))
        assertEquals(VoiceCommand.Focus("pause"), parse("mets le focus en pause"))
    }

    @Test
    fun `la dictee garde le texte tel quel`() {
        assertEquals(VoiceCommand.Type("coupe le micro"), parse("écris : coupe le micro"))
    }

    @Test
    fun `ajoute une tache avec ses accents`() {
        assertEquals(VoiceCommand.AddTask("Appeler le médecin"), parse("ajoute une tâche appeler le médecin"))
    }

    @Test
    fun `rappel a une heure ou dans un delai`() {
        assertEquals(VoiceCommand.Remind("Appeler Paul", "15:30"), parse("rappelle-moi d'appeler Paul à 15 h 30"))
        val now = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0) }
        assertEquals(VoiceCommand.Remind("Sortir le linge", "10:10"), parse("rappelle-moi dans 10 minutes de sortir le linge", now))
    }

    @Test
    fun `touche du deck par son nom`() {
        assertEquals(VoiceCommand.Tile(tiles[0]), parse("lance Spotify"))
        assertEquals(VoiceCommand.Tile(tiles[1]), parse("quitter l'appel"))
    }

    @Test
    fun `fenetres de l'ordinateur`() {
        assertEquals(VoiceCommand.Window("place", "chrome", layout = "left"), parse("mets chrome à gauche"))
        assertEquals(VoiceCommand.Window("close", "word"), parse("ferme word"))
        assertEquals(VoiceCommand.Window("focus", "discord", launch = true), parse("ouvre discord"))
    }

    @Test
    fun `phrase vide ou inconnue`() {
        assertNull(parse("   "))
        assertNull(parse("blablabla"))
    }

    @Test
    fun `nom d'appli le plus proche a l'oreille`() {
        assertEquals("Ankama Launcher", VoiceCommands.closest(listOf("en cabane launcher"), listOf("Ankama Launcher", "Steam", "Discord")))
        assertNull(VoiceCommands.closest(listOf("xyz"), listOf("Steam", "Discord")))
    }

    @Test
    fun `agents Claude autoriser refuser choisir valider relancer`() {
        assertEquals(VoiceCommand.Agent("allow"), parse("Autorise"))
        assertEquals(VoiceCommand.Agent("allow"), parse("approuve le plan"))
        assertEquals(VoiceCommand.Agent("allow"), parse("vas-y"))
        assertEquals(VoiceCommand.Agent("deny"), parse("refuse la commande"))
        assertEquals(VoiceCommand.Agent("send"), parse("valide"))
        assertEquals(VoiceCommand.Agent("send"), parse("question suivante"))
        assertEquals(VoiceCommand.Agent("continue"), parse("Continue"))
        assertEquals(VoiceCommand.Agent("choice", 2), parse("option deux"))
        assertEquals(VoiceCommand.Agent("choice", 3), parse("choix 3"))
        assertEquals(VoiceCommand.Agent("choice", 1), parse("le premier choix"))
        assertEquals(VoiceCommand.Agent("choice", 2), parse("la deuxième option"))
    }

    @Test
    fun `refuse seul reste celui de l'appel et une option hors bornes n'est pas une option`() {
        assertEquals(VoiceCommand.Call("decline"), parse("refuse"))
        assertEquals(VoiceCommand.Call("answer"), parse("accepte"))
        assertNull((parse("option neuf") as? VoiceCommand.Agent))
    }
}
