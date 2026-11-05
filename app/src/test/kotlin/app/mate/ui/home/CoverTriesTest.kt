package app.mate.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTriesTest {
    @Test
    fun `une nouvelle pochette se charge, une pochette tenue ne se recharge pas`() {
        val tries = CoverTries()
        assertTrue(tries.shouldFetch("a", now = 0))
        tries.loaded("a")
        assertFalse(tries.shouldFetch("a", now = 1_000))
        assertTrue(tries.shouldFetch("b", now = 1_000))
    }

    @Test
    fun `un chargement interrompu se refait a la relecture suivante`() {
        val tries = CoverTries()
        assertTrue(tries.shouldFetch("a", now = 0))
        // Annulé pendant le téléchargement : ni loaded ni failed.
        assertTrue(tries.shouldFetch("a", now = 1_000))
    }

    @Test
    fun `apres un echec, un nouvel essai seulement apres le delai`() {
        val tries = CoverTries()
        tries.failed("a", now = 0)
        assertFalse(tries.shouldFetch("a", now = 1_000))
        assertFalse(tries.shouldFetch("a", now = CoverTries.RETRY_MS - 1))
        assertTrue(tries.shouldFetch("a", now = CoverTries.RETRY_MS))
        // Un autre morceau n'attend pas.
        assertTrue(tries.shouldFetch("b", now = 1_000))
    }

    @Test
    fun `sans pochette, rien a charger`() {
        assertFalse(CoverTries().shouldFetch(null, now = 0))
    }
}
