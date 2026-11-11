package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class BlinkerTest {
    @Test
    fun `le clignotement tourne seulement widget actif, mouvement permis et une etiquette qui clignote`() {
        // actif, reduceMotion, étiquettes qui clignotent → tourne ?
        val cases = listOf(
            Triple(true, false, 1) to true,
            Triple(true, true, 3) to false, // Réglages > réduire les animations : jamais
            Triple(false, false, 2) to false, // setActive(false) : arrêté
            Triple(true, false, 0) to false, // rien ne clignote : pas de minuterie pour rien
        )
        for ((input, expected) in cases) {
            val (active, reduceMotion, count) = input
            assertEquals("$input", expected, Blinker.shouldRun(active, reduceMotion, count))
        }
    }
}
