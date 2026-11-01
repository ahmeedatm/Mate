package app.mate.voice

import app.mate.net.DeckTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `les profils ouvrent l'ecran des profils`() {
        assertEquals(VoiceCommand.ShowMode("home"), parse("ouvre les profils"))
        assertEquals(VoiceCommand.ShowMode("home"), parse("va aux profils"))
    }

    @Test
    fun `ambiances`() {
        assertEquals(VoiceCommand.Noise("STORM"), parse("mets l'ambiance"))
        assertEquals(VoiceCommand.Noise("STORM"), parse("lance le bruit de pluie"))
        assertEquals(VoiceCommand.Noise("FOREST"), parse("ambiance forêt"))
        assertEquals(VoiceCommand.Noise("JAPAN"), parse("mets l'ambiance du Japon"))
        assertEquals(VoiceCommand.Noise("JAPAN"), parse("ambiance samouraï"))
        assertEquals(VoiceCommand.Noise("SEA"), parse("ambiance mer"))
        assertEquals(VoiceCommand.Noise("LOFI"), parse("mets du lofi"))
        assertEquals(VoiceCommand.Noise(null), parse("coupe l'ambiance"))
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
    fun `copie sur l'ordi garde le texte tel quel`() {
        assertEquals(VoiceCommand.CopyToComputer("bonjour"), parse("copie sur l'ordi bonjour"))
        assertEquals(VoiceCommand.CopyToComputer("Bonjour à Élise"), parse("Copie sur l'ordi Bonjour à Élise"))
        assertEquals(VoiceCommand.CopyToComputer("coupe le micro"), parse("copie sur l'ordi : coupe le micro"))
        assertEquals(VoiceCommand.CopyToComputer("bonjour"), parse("copie sur l'ordinateur bonjour"))
    }

    @Test
    fun `copie ca sur l'ordi`() {
        assertEquals(VoiceCommand.CopyToComputer("bonjour"), parse("copie ça sur l'ordi bonjour"))
        assertEquals(VoiceCommand.CopyToComputer("bonjour"), parse("copie ca sur l ordi bonjour"))
    }

    @Test
    fun `copie sur l'ordi sans texte`() {
        assertEquals(VoiceCommand.CopyToComputer(""), parse("copie sur l'ordi"))
        assertEquals(VoiceCommand.CopyToComputer(""), parse("copie ça sur l'ordi"))
    }

    @Test
    fun `copie sur l'ordi sans lettre ni chiffre compte comme vide`() {
        assertEquals(VoiceCommand.CopyToComputer(""), parse("copie sur l'ordi ."))
        assertEquals(VoiceCommand.CopyToComputer(""), parse("copie sur l'ordi : ?!"))
        assertEquals(VoiceCommand.CopyToComputer("42"), parse("copie sur l'ordi 42"))
    }

    @Test
    fun `copie seul ou autre objet n'est pas une copie sur l'ordi`() {
        assertFalse(parse("copie le fichier") is VoiceCommand.CopyToComputer)
        assertFalse(parse("copie") is VoiceCommand.CopyToComputer)
        assertFalse(parse("copie sur l'ordinal") is VoiceCommand.CopyToComputer)
    }

    @Test
    fun `ajoute une tache avec ses accents`() {
        assertEquals(VoiceCommand.AddTask("Appeler le médecin"), parse("ajoute une tâche appeler le médecin"))
    }

    @Test
    fun `ajoute des articles a la liste de courses`() {
        assertEquals(VoiceCommand.AddShopping(listOf("Lait")), parse("ajoute du lait à la liste de courses"))
        assertEquals(VoiceCommand.AddShopping(listOf("Pain", "Œufs")), parse("Ajoute du pain et des œufs sur la liste de courses"))
        assertEquals(VoiceCommand.AddShopping(listOf("Pommes de terre")), parse("mets des pommes de terre dans les courses"))
        assertEquals(VoiceCommand.AddShopping(listOf("Farine")), parse("rajoute de la farine à la liste de course"))
        assertEquals(VoiceCommand.AddShopping(listOf("Citrons")), parse("note des citrons aux courses"))
    }

    @Test
    fun `la liste de courses ne prend pas les taches ni les autres phrases`() {
        assertEquals(VoiceCommand.AddTask("Appeler le médecin"), parse("ajoute une tâche appeler le médecin"))
        assertFalse(parse("ajoute du lait") is VoiceCommand.AddShopping)
        assertFalse(parse("ajoute à la liste de courses") is VoiceCommand.AddShopping)
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
