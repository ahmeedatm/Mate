package app.mate.ui.home

import app.mate.net.MediaSession
import app.mate.net.YtDesktop
import app.mate.net.YtMusicState
import app.mate.net.YtTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowTrackTest {
    private fun session(app: String, playing: Boolean, current: Boolean = false, thumb: String? = null, position: Long? = null, duration: Long? = null) = MediaSession(
        app = app, name = app, current = current, title = "Titre $app", artist = "Artiste", playing = playing,
        canPlayPause = true, canNext = true, canPrevious = true, canSeek = true,
        positionMs = position, durationMs = duration, thumb = thumb,
    )

    @Test
    fun `rien ne joue sur l'ordinateur`() {
        assertNull(NowTrack.ofMedia(emptyList()))
    }

    @Test
    fun `ce qui joue passe avant ce que Windows met en avant`() {
        val track = NowTrack.ofMedia(listOf(session("muet", playing = false, current = true), session("spotify", playing = true)))
        assertEquals("spotify", track?.app)
        assertEquals("Titre spotify", track?.title)
        assertEquals(true, track?.playing)
    }

    @Test
    fun `sans lecture, la session en avant puis la premiere`() {
        assertEquals("b", NowTrack.ofMedia(listOf(session("a", false), session("b", false, current = true)))?.app)
        assertEquals("a", NowTrack.ofMedia(listOf(session("a", false), session("b", false)))?.app)
    }

    @Test
    fun `avancement entre 0 et 1, inconnu sans duree`() {
        assertEquals(0.25f, NowTrack.ofMedia(listOf(session("a", true, position = 60_000, duration = 240_000)))!!.progress!!, 0.001f)
        assertNull(NowTrack.ofMedia(listOf(session("a", true, position = 60_000, duration = null)))!!.progress)
        assertNull(NowTrack.ofMedia(listOf(session("a", true, position = 60_000, duration = 0)))!!.progress)
        assertEquals(1f, NowTrack.ofMedia(listOf(session("a", true, position = 300_000, duration = 240_000)))!!.progress!!, 0.001f)
    }

    @Test
    fun `la cle de pochette change avec l'appli et la vignette`() {
        assertEquals("a|k1", NowTrack.ofMedia(listOf(session("a", true, thumb = "k1")))?.coverKey)
        assertNull(NowTrack.ofMedia(listOf(session("a", true, thumb = null)))?.coverKey)
    }

    private fun yt(open: Boolean = true, track: YtTrack? = YtTrack("Reunion", "M83", "Album", "https://lh3/x", 30.0, 120.0, "none")) =
        YtMusicState(open = open, source = "browser", playing = true, track = track, repeat = null, queue = emptyList(), desktop = YtDesktop("off", null, null))

    @Test
    fun `YouTube Music ferme ou sans titre`() {
        assertNull(NowTrack.ofYtMusic(yt(open = false)))
        assertNull(NowTrack.ofYtMusic(yt(track = null)))
    }

    @Test
    fun `YouTube Music en lecture`() {
        val track = NowTrack.ofYtMusic(yt())!!
        assertEquals("Reunion", track.title)
        assertEquals("M83", track.artist)
        assertEquals(0.25f, track.progress!!, 0.001f)
        assertEquals("https://lh3/x", track.coverKey)
        assertNull(NowTrack.ofYtMusic(yt(track = YtTrack("Direct", "", "", null, 0.0, 0.0, "none")))!!.progress)
    }
}
