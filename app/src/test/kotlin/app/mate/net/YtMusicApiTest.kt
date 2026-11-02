package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicApiTest {

    @Test
    fun `titre joue, file et appli de bureau connectee`() {
        val json = """{"open":true,"source":"desktop","desktop":{"status":"connected"},"playing":true,
            "track":{"title":"Le pacte","artist":"Orelsan","album":"La fuite en avant","cover":"https://yt3.googleusercontent.com/a=w544-h544",
                     "position":10.7,"duration":268,"like":"like"},
            "repeat":"all","queue":[{"title":"Le pacte","subtitle":"Orelsan","cover":null,"current":true},
                                     {"title":"Ailleurs","subtitle":"Orelsan","cover":"https://i.ytimg.com/x.jpg","current":false}],
            "query":null,"results":[]}"""
        val s = parseYtMusic(json)
        assertTrue(s.open)
        assertEquals("desktop", s.source)
        assertEquals(YtTrack("Le pacte", "Orelsan", "La fuite en avant", "https://yt3.googleusercontent.com/a=w544-h544", 10.7, 268.0, "like"), s.track)
        assertEquals("all", s.repeat)
        assertEquals(
            listOf(YtQueueItem("Le pacte", "Orelsan", null, true), YtQueueItem("Ailleurs", "Orelsan", "https://i.ytimg.com/x.jpg", false)),
            s.queue,
        )
        assertEquals(YtDesktop("connected", null, null), s.desktop)
    }

    @Test
    fun `rien d ouvert, connexion en cours avec son code`() {
        val s = parseYtMusic("""{"open":false,"desktop":{"status":"connecting","code":"4821"}}""")
        assertFalse(s.open)
        assertNull(s.track)
        assertTrue(s.queue.isEmpty())
        assertEquals(YtDesktop("connecting", "4821", null), s.desktop)
    }

    @Test
    fun `paroles synchronisees et non synchronisees`() {
        val synced = parseYtLyrics("""{"found":true,"title":"Ailleurs","synced":true,"instrumental":false,
            "lines":[{"t":8.2,"text":"Si vous me cherchez"},{"t":10.78,"text":"J'suis loin"},{"t":12.0,"text":""}]}""")
        assertTrue(synced.synced)
        assertEquals(listOf(YtLyricLine(8.2, "Si vous me cherchez"), YtLyricLine(10.78, "J'suis loin"), YtLyricLine(12.0, "")), synced.lines)
        val plain = parseYtLyrics("""{"found":true,"synced":false,"lines":[{"t":null,"text":"Faut qu'j'vous raconte"}]}""")
        assertEquals(listOf(YtLyricLine(null, "Faut qu'j'vous raconte")), plain.lines)
        assertFalse(parseYtLyrics("""{"found":false,"title":"Boss"}""").found)
    }

    @Test
    fun `ligne chantee - avant la premiere, pendant, avec un peu d avance, apres la derniere`() {
        val lines = listOf(YtLyricLine(8.2, "a"), YtLyricLine(10.78, "b"), YtLyricLine(20.0, "c"))
        assertEquals(-1, currentLyricIndex(lines, 3.0))
        assertEquals(0, currentLyricIndex(lines, 9.0))
        // 10,5 + 0,3 d'avance dépasse 10,78 : la ligne suivante s'allume déjà.
        assertEquals(1, currentLyricIndex(lines, 10.5))
        assertEquals(2, currentLyricIndex(lines, 300.0))
        assertEquals(-1, currentLyricIndex(listOf(YtLyricLine(null, "non synchronisée")), 50.0))
    }

    @Test
    fun `titre vide ou null traite comme rien en lecture, repetition null gardee null`() {
        val s = parseYtMusic("""{"open":true,"source":"browser","playing":false,"track":{"title":"","like":"none"},"repeat":null,"queue":[]}""")
        assertNull(s.track)
        assertNull(s.repeat)
        // Compagnon sans champ desktop (onglet seulement) : off.
        assertEquals("off", s.desktop.status)
    }
}
