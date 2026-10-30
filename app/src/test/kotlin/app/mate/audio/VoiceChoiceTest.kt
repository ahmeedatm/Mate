package app.mate.audio

import app.mate.FakeContext
import app.mate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceChoiceTest {

    private fun voice(name: String, language: String = "fr", network: Boolean = false, notInstalled: Boolean = false) =
        VoiceInfo(name, language, network, notInstalled)

    @Test
    fun `seules les voix francaises hors ligne et installees, triees par nom`() {
        val voices = listOf(
            voice("fr-fr-x-frd-local"),
            voice("fr-fr-x-frd-network", network = true),
            voice("en-us-x-sfg-local", language = "en"),
            voice("fr-fr-x-fra-local"),
            voice("fr-fr-x-vlf-local", notInstalled = true),
            voice("fr-CA-language"),
            voice("fr-fr-x-frc-local"),
        )
        assertEquals(
            listOf("fr-CA-language", "fr-fr-x-fra-local", "fr-fr-x-frc-local", "fr-fr-x-frd-local"),
            VoiceChoice.offlineFrench(voices),
        )
    }

    @Test
    fun `ordre stable quel que soit l'ordre du moteur, sans doublon`() {
        val a = listOf(voice("fr-fr-x-frb-local"), voice("fr-fr-x-fra-local"), voice("fr-fr-x-fra-local"))
        assertEquals(listOf("fr-fr-x-fra-local", "fr-fr-x-frb-local"), VoiceChoice.offlineFrench(a))
        assertEquals(VoiceChoice.offlineFrench(a), VoiceChoice.offlineFrench(a.reversed()))
    }

    @Test
    fun `aucune voix francaise hors ligne`() {
        assertEquals(emptyList<String>(), VoiceChoice.offlineFrench(listOf(voice("fr-fr-x-fra-network", network = true))))
    }

    @Test
    fun `voix enregistree disparue ou automatique, voix du moteur`() {
        val available = listOf("fr-fr-x-fra-local", "fr-fr-x-frb-local")
        assertEquals("fr-fr-x-frb-local", VoiceChoice.voiceToApply("fr-fr-x-frb-local", available))
        assertNull(VoiceChoice.voiceToApply("fr-fr-x-vlf-local", available))
        assertNull(VoiceChoice.voiceToApply(null, available))
    }

    @Test
    fun `debits Lent, Doux, Normal et pastille la plus proche`() {
        assertEquals(listOf(0.8f, 0.9f, 1f), VoiceChoice.RATES)
        assertEquals(0.9f, VoiceChoice.rateChoice(0.9f))
        assertEquals(0.8f, VoiceChoice.rateChoice(0.5f))
        assertEquals(1f, VoiceChoice.rateChoice(1.4f))
        assertEquals(0.9f, VoiceChoice.rateChoice(0.92f))
    }

    @Test
    fun `voix plus douce par defaut`() {
        assertEquals(0.9f, VoiceChoice.DEFAULT_RATE)
        assertEquals(0.95f, VoiceChoice.PITCH)
    }

    @Test
    fun `reglages de la voix enregistres`() {
        val context = FakeContext()
        assertNull(Prefs.ttsVoice(context))
        assertEquals(VoiceChoice.DEFAULT_RATE, Prefs.ttsRate(context))
        Prefs.setTtsVoice(context, "fr-fr-x-frc-local")
        Prefs.setTtsRate(context, 0.8f)
        assertEquals("fr-fr-x-frc-local", Prefs.ttsVoice(context))
        assertEquals(0.8f, Prefs.ttsRate(context))
        Prefs.setTtsVoice(context, null)
        assertNull(Prefs.ttsVoice(context))
    }
}
