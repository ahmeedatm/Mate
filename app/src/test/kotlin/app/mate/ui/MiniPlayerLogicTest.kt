package app.mate.ui

import app.mate.net.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerLogicTest {
    private fun session(app: String, playing: Boolean, current: Boolean = false) =
        MediaSession(app, app, current, "Titre", "", playing, true, true, true, false, null, null, null)

    @Test
    fun `ce qui joue passe devant la session en avant`() {
        val spotify = session("Spotify", playing = false, current = true)
        val opera = session("Opera GX", playing = true)
        assertEquals(opera, MiniPlayerLogic.pick(listOf(spotify, opera)))
        assertEquals(spotify, MiniPlayerLogic.pick(listOf(session("Edge", false), spotify)))
        assertNull(MiniPlayerLogic.pick(emptyList()))
    }

    @Test
    fun `visible en lecture, puis dix minutes de pause`() {
        val paused = session("Opera GX", playing = false)
        assertTrue(MiniPlayerLogic.visible(session("Opera GX", playing = true), null, 0))
        assertTrue(MiniPlayerLogic.visible(paused, 1_000, 1_000 + MiniPlayerLogic.PAUSE_KEEP_MS))
        assertFalse(MiniPlayerLogic.visible(paused, 1_000, 1_001 + MiniPlayerLogic.PAUSE_KEEP_MS))
        // Pause trouvée au démarrage, jamais vue jouer : rien.
        assertFalse(MiniPlayerLogic.visible(paused, null, 0))
        assertFalse(MiniPlayerLogic.visible(null, 0, 0))
    }

    @Test
    fun `l'ilot se referme seul apres cinq secondes, sauf sous TalkBack`() {
        assertEquals(5_000L, MiniPlayerLogic.autoCloseMs(touchExploration = false))
        // Exploration au toucher : on lit les boutons un à un, l'îlot ne doit pas partir pendant ce temps.
        assertNull(MiniPlayerLogic.autoCloseMs(touchExploration = true))
    }

    @Test
    fun `le temps s'ecrit en minutes, et en heures au-dela`() {
        assertEquals("0:00", MiniPlayerLogic.clock(0))
        assertEquals("1:33", MiniPlayerLogic.clock(93_999))
        assertEquals("1:02:05", MiniPlayerLogic.clock(3_725_000))
    }

    @Test
    fun `on avance seulement dans un morceau qui a une duree et le permet`() {
        val seekable = session("Spotify", playing = true).copy(canSeek = true, durationMs = 255_000)
        assertTrue(MiniPlayerLogic.seekable(seekable))
        assertFalse(MiniPlayerLogic.seekable(seekable.copy(canSeek = false)))
        // Direct (Twitch) : pas de durée.
        assertFalse(MiniPlayerLogic.seekable(seekable.copy(durationMs = null)))
        assertFalse(MiniPlayerLogic.seekable(seekable.copy(durationMs = 0)))
    }

    @Test
    fun `l'ilot se centre sous la pastille sans sortir de l'ecran`() {
        // Pastille au centre : l'îlot centré sur elle.
        assertEquals(400, MiniPlayerLogic.islandLeft(chipCenterX = 630, width = 460, parentWidth = 1280, margin = 16))
        // Pastille collée à droite : l'îlot s'arrête à la marge.
        assertEquals(1280 - 16 - 460, MiniPlayerLogic.islandLeft(chipCenterX = 1200, width = 460, parentWidth = 1280, margin = 16))
        assertEquals(16, MiniPlayerLogic.islandLeft(chipCenterX = 50, width = 460, parentWidth = 1280, margin = 16))
    }

    @Test
    fun `le contenu apparait dans la seconde moitie de l'ouverture`() {
        assertEquals(0f, MiniPlayerLogic.contentAlpha(0f), 0f)
        assertEquals(0f, MiniPlayerLogic.contentAlpha(0.4f), 0f)
        assertEquals(1f, MiniPlayerLogic.contentAlpha(1f), 0f)
        // Ressort qui dépasse un peu : on reste à 1.
        assertEquals(1f, MiniPlayerLogic.contentAlpha(1.05f), 0f)
        assertTrue(MiniPlayerLogic.contentAlpha(0.7f) in 0.4f..0.6f)
    }

    @Test
    fun `le bandeau s'efface pendant que l'îlot s'ouvre et revient à la fermeture`() {
        assertEquals(1f, MiniPlayerLogic.barAlpha(0f), 0f)
        // Effacé avant que le contenu de l'îlot n'apparaisse : les deux textes ne se croisent pas.
        assertEquals(0f, MiniPlayerLogic.barAlpha(0.4f), 0f)
        assertEquals(0f, MiniPlayerLogic.barAlpha(1.05f), 0f)
        assertTrue(MiniPlayerLogic.barAlpha(0.2f) in 0.4f..0.6f)
        // Ressort qui repart un peu sous 0 à la fermeture : le bandeau reste plein.
        assertEquals(1f, MiniPlayerLogic.barAlpha(-0.05f), 0f)
    }

    @Test
    fun `l'îlot prend l'encre foncée du thème`() {
        // Thème clair (fond crème) : l'îlot prend la couleur du texte, le texte de l'îlot celle du fond.
        assertTrue(MiniPlayerLogic.islandInverted(0.95))
        // Thème sombre : l'îlot garde le fond, déjà foncé.
        assertFalse(MiniPlayerLogic.islandInverted(0.01))
    }
}
