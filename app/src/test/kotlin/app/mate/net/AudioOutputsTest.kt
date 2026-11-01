package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOutputsTest {

    @Test
    fun `parseAudioOutputs lit les sorties et celle qui sert`() {
        val outputs = parseAudioOutputs("""{"outputs":[{"id":"{a}","name":"Haut-parleurs","current":true},{"id":"{b}","name":"Casque USB","current":false}]}""")
        assertEquals(listOf("Haut-parleurs", "Casque USB"), outputs.map { it.name })
        assertEquals(listOf(true, false), outputs.map { it.current })
        assertEquals("{b}", outputs[1].id)
    }

    @Test
    fun `parseAudioOutputs sans sortie donne une liste vide`() {
        assertEquals(emptyList<AudioOutput>(), parseAudioOutputs("""{"outputs":[]}"""))
    }

    @Test
    fun `currentIndex donne la place de la sortie qui sert, -1 si aucune`() {
        val list = listOf(AudioOutput("a", "A", false), AudioOutput("b", "B", true))
        assertEquals(1, currentOutputIndex(list))
        assertEquals(-1, currentOutputIndex(list.map { it.copy(current = false) }))
        assertNull(list.firstOrNull { it.id == "zzz" })
    }
}
