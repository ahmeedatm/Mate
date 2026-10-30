package com.unowhy.unomate.ui

import com.unowhy.unomate.FakeContext
import com.unowhy.unomate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSpeechTest {
    private val failed = "%1\$s n'a pas marché"

    @Test
    fun `succes, le nom de la touche`() {
        assertEquals("Courrier type", ActionSpeech.text("Courrier type", ok = true, failed, enabled = true, talkBack = false))
    }

    @Test
    fun `echec, une phrase courte avec le nom, jamais le texte de l'erreur`() {
        assertEquals("Courrier type n'a pas marché", ActionSpeech.text("Courrier type", ok = false, failed, enabled = true, talkBack = false))
    }

    @Test
    fun `rien quand le reglage est coupe ou que TalkBack est actif`() {
        assertNull(ActionSpeech.text("Courrier type", ok = true, failed, enabled = false, talkBack = false))
        assertNull(ActionSpeech.text("Courrier type", ok = true, failed, enabled = true, talkBack = true))
        assertNull(ActionSpeech.text("Courrier type", ok = false, failed, enabled = true, talkBack = true))
    }

    @Test
    fun `rien pour une touche sans nom, espaces retires sinon`() {
        assertNull(ActionSpeech.text("  ", ok = true, failed, enabled = true, talkBack = false))
        assertEquals("Verrouiller", ActionSpeech.text(" Verrouiller ", ok = true, failed, enabled = true, talkBack = false))
    }

    @Test
    fun `reglage coupe par defaut`() {
        val context = FakeContext()
        assertFalse(Prefs.speakActions(context))
        Prefs.setSpeakActions(context, true)
        assertTrue(Prefs.speakActions(context))
    }
}
