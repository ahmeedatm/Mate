package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardInsetTest {

    @Test
    fun `des API 30 le clavier est son propre inset`() {
        assertEquals(400, keyboardInset(sdk = 30, imeBottom = 400, systemBottom = 0, stableBottom = 24))
    }

    @Test
    fun `avant API 30 sans clavier, la barre de navigation visible ne compte pas`() {
        assertEquals(0, keyboardInset(sdk = 29, imeBottom = 0, systemBottom = 48, stableBottom = 48))
    }

    @Test
    fun `avant API 30 avec clavier, l'inset de fenetre est sa hauteur`() {
        assertEquals(400, keyboardInset(sdk = 29, imeBottom = 0, systemBottom = 400, stableBottom = 48))
    }

    @Test
    fun `avant API 30 barre de navigation cachee et sans clavier, rien`() {
        assertEquals(0, keyboardInset(sdk = 29, imeBottom = 0, systemBottom = 0, stableBottom = 48))
    }
}
