package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordVoiceTest {

    @Test
    fun `appel avec le RPC, participants et photos`() {
        val v = parseVoice(
            """{"voice":{"found":true,"connected":true,"channel":"Général","people":["alice"],"muted":false,"deafened":null,
                "photos":"on","members":[{"id":"2","name":"alice","avatar":true,"speaking":true},{"id":"3","name":"bob"}]}}""",
        )!!
        assertEquals("on", v.photos)
        assertEquals(listOf(VoiceMember("2", "alice", avatar = true, speaking = true), VoiceMember("3", "bob", avatar = false, speaking = false)), v.members)
        assertEquals(listOf("alice"), v.people)
        assertNull(v.deafened)
    }

    @Test
    fun `ordinateur d'avant ou sans RPC, repli sur les noms`() {
        val v = parseVoice("""{"voice":{"found":true,"connected":true,"channel":"Général","people":["alice","bob"]}}""")!!
        assertNull(v.photos)
        assertNull(v.members)
        assertEquals(listOf("alice", "bob"), v.people)
    }

    @Test
    fun `appli fermee ou illisible`() {
        assertNull(parseVoice("""{"voice":null}"""))
        assertNull(parseVoice("""{"voice":{"found":false}}"""))
    }

    @Test
    fun `qui parle`() {
        assertEquals(setOf("2", "3"), parseSpeaking("""{"speaking":["2","3"]}"""))
        assertEquals(emptySet<String>(), parseSpeaking("""{}"""))
    }
}
