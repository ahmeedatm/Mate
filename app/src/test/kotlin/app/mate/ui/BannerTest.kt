package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Quand le bandeau repart : après son délai, ou jamais pour une alerte (il reste jusqu'à un toucher). */
class BannerTest {

    @Test
    fun `un bandeau normal repart apres son delai`() {
        assertEquals(10_000L, Banner.hideDelayMs(10))
    }

    @Test
    fun `une alerte reste jusqu'a un toucher`() {
        assertNull(Banner.hideDelayMs(Banner.UNTIL_TAPPED))
    }
}
