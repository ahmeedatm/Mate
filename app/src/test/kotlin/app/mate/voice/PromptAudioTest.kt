package app.mate.voice

import app.mate.voice.SilenceGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PromptAudioTest {

    @Test
    fun `wav = en-tête PCM 16 kHz mono puis les échantillons`() {
        val pcm = ByteArray(3200) { it.toByte() }
        val wav = PromptAudio.wav(pcm)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, b.getInt(40))
        assertEquals(pcm.toList(), wav.copyOfRange(44, wav.size).toList())
    }

    @Test
    fun `rms d'un signal carré = son amplitude`() {
        assertEquals(1000.0, PromptAudio.rms(ShortArray(160) { if (it % 2 == 0) 1000 else -1000 }), 0.001)
        assertEquals(0.0, PromptAudio.rms(ShortArray(0)), 0.0)
    }

    @Test
    fun `niveau du halo entre 0 et 1, 0 sous le seuil de la parole`() {
        assertEquals(0f, PromptAudio.level(100.0), 0f)
        assertEquals(0f, PromptAudio.level(SilenceGate.MIN_SPEECH), 0f)
        assertEquals(0.5f, PromptAudio.level(SilenceGate.MIN_SPEECH * Math.pow(10.0, 0.75)), 0.001f)
        assertEquals(1f, PromptAudio.level(32_000.0), 0f)
    }

    /** Envoie `ms` de niveau `rms` ; rend le dernier verdict. */
    private fun SilenceGate.during(ms: Int, rms: Double): Verdict {
        var v = Verdict.LISTEN
        repeat(ms / PromptAudio.FRAME_MS) { v = feed(rms) }
        return v
    }

    @Test
    fun `s'arrête après le silence qui suit la parole`() {
        val gate = SilenceGate()
        assertEquals(Verdict.LISTEN, gate.during(500, 80.0))
        assertEquals(Verdict.LISTEN, gate.during(2_000, 3_000.0))
        assertTrue(gate.heard)
        assertEquals(Verdict.LISTEN, gate.during(1_400, 90.0))
        assertEquals(Verdict.DONE, gate.feed(90.0))
    }

    @Test
    fun `une pause courte au milieu ne coupe pas`() {
        val gate = SilenceGate()
        gate.during(300, 80.0)
        gate.during(1_000, 2_500.0)
        assertEquals(Verdict.LISTEN, gate.during(1_000, 100.0))
        assertEquals(Verdict.LISTEN, gate.during(1_000, 2_500.0))
        assertEquals(Verdict.LISTEN, gate.during(1_400, 100.0))
    }

    @Test
    fun `rien dit pendant 8 s`() {
        val gate = SilenceGate()
        assertEquals(Verdict.LISTEN, gate.during(7_900, 120.0))
        assertEquals(Verdict.DONE, gate.feed(120.0))
        assertFalse(gate.heard)
    }

    @Test
    fun `un bruit de fond fort n'est pas de la parole`() {
        // Niveau constant : jamais 2,5 fois le plus bas entendu.
        val gate = SilenceGate()
        assertEquals(Verdict.DONE, gate.during(8_000, 1_500.0))
        assertFalse(gate.heard)
    }

    @Test
    fun `jamais plus de 60 s`() {
        val gate = SilenceGate()
        gate.during(200, 80.0)
        assertEquals(Verdict.LISTEN, gate.during(59_700, 3_000.0))
        assertEquals(Verdict.DONE, gate.feed(3_000.0))
    }
}
