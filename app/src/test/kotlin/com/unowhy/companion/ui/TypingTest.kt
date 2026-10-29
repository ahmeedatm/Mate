package com.unowhy.companion.ui

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTest {

    @After
    fun reset() = Typing.reset()

    @Test
    fun `une boite de saisie ouverte compte comme une saisie`() {
        assertFalse(Typing.active(focusIsEditText = false))
        Typing.opened()
        assertTrue(Typing.active(focusIsEditText = false))
        Typing.closed()
        assertFalse(Typing.active(focusIsEditText = false))
    }

    @Test
    fun `un champ de l'ecran a le focus, ou une fermeture en trop ne casse rien`() {
        assertTrue(Typing.active(focusIsEditText = true))
        Typing.closed()
        Typing.opened()
        assertTrue(Typing.active(focusIsEditText = false))
    }
}
