package app.mate.installers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureLimiterTest {

    @Test
    fun `bloque une adresse apres max echecs dans la fenetre`() {
        val limiter = FailureLimiter(max = 3, windowMs = 60_000)
        repeat(3) {
            assertFalse(limiter.blocked("a", 1_000L + it))
            limiter.fail("a", 1_000L + it)
        }
        assertTrue(limiter.blocked("a", 2_000))
    }

    @Test
    fun `une autre adresse n'est pas touchee`() {
        val limiter = FailureLimiter(max = 1, windowMs = 60_000)
        limiter.fail("a", 0)
        assertTrue(limiter.blocked("a", 1))
        assertFalse(limiter.blocked("b", 1))
    }

    @Test
    fun `les echecs sortent de la fenetre`() {
        val limiter = FailureLimiter(max = 2, windowMs = 60_000)
        limiter.fail("a", 0)
        limiter.fail("a", 10)
        assertTrue(limiter.blocked("a", 59_999))
        assertFalse(limiter.blocked("a", 60_011))
    }

    @Test
    fun `les adresses sans echec recent sont oubliees`() {
        val limiter = FailureLimiter(max = 2, windowMs = 1_000)
        limiter.fail("a", 0)
        limiter.blocked("b", 5_000)
        assertTrue(limiter.tracked() == 0)
    }
}
