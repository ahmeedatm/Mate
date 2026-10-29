package com.unowhy.unomate.ui

import com.unowhy.unomate.net.MediaSession
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
}
