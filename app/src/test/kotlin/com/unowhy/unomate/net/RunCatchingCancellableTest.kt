package com.unowhy.unomate.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RunCatchingCancellableTest {

    @Test
    fun `rend le resultat d'un bloc qui reussit`() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    @Test
    fun `range une IOException dans un Result en echec`() {
        val result = runCatchingCancellable { throw IOException("réseau") }
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `relance CancellationException au lieu de la ranger`() {
        try {
            runCatchingCancellable { throw CancellationException("stop") }
            fail("la CancellationException aurait dû passer")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun `une boucle de relecture s'arrete quand sa coroutine est annulee`() = runBlocking {
        val job = launch {
            while (isActive) {
                runCatchingCancellable { delay(10) }
            }
        }
        delay(30)
        job.cancel()
        withTimeout(1000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
