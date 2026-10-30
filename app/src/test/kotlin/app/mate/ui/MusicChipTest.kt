package app.mate.ui

import app.mate.net.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicChipTest {

    private fun session(app: String, title: String, current: Boolean = false, playing: Boolean = false) =
        MediaSession(app, app, current, title, "", playing, true, true, true, false, null, null, null)

    @Test
    fun `ce qui joue passe devant la session courante (regle du petit lecteur, MiniPlayerLogic)`() {
        val sessions = listOf(session("chrome", "Vidéo", current = true), session("spotify", "Lo-fi", playing = true))
        assertEquals("spotify", MusicChip.pick(sessions)?.app)
    }

    @Test
    fun `sans session courante, celle qui joue`() {
        val sessions = listOf(session("chrome", "Vidéo"), session("spotify", "Lo-fi", playing = true))
        assertEquals("spotify", MusicChip.pick(sessions)?.app)
    }

    @Test
    fun `la provenance est le nom de l'appli ou du site, sauf s'il redit le titre`() {
        assertEquals("YouTube", MusicChip.source(session("chrome", "Lo-fi").copy(name = "YouTube")))
        assertNull(MusicChip.source(session("spotify", "Spotify").copy(name = "Spotify")))
        assertNull(MusicChip.source(session("x", "Titre").copy(name = " ")))
    }

    @Test
    fun `apres le titre, l'artiste, sinon la provenance`() {
        assertEquals("Major Lazer", MusicChip.subtitle(session("spotify", "Cold Water").copy(name = "Spotify", artist = "Major Lazer")))
        assertEquals("YouTube", MusicChip.subtitle(session("chrome", "Clip").copy(name = "YouTube")))
        assertNull(MusicChip.subtitle(session("x", "Titre").copy(name = "")))
    }

    @Test
    fun `une session sans titre ne s'affiche pas`() {
        assertNull(MusicChip.pick(listOf(session("chrome", "", current = true, playing = true))))
        assertNull(MusicChip.pick(emptyList()))
    }
}
