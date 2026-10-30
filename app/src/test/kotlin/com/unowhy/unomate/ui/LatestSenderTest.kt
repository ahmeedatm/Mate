package com.unowhy.unomate.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestSenderTest {

    @Test
    fun `une requete a la fois, puis seulement la plus recente`() = runBlocking {
        val sent = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val sender = LatestSender<Int>(this) { value ->
            sent += value
            if (value == 10) gate.await()
        }
        sender.offer(10)
        yield()
        // Pendant l'envoi de 10 : 20 et 30 arrivent, seul 30 part ensuite.
        sender.offer(20)
        sender.offer(30)
        gate.complete(Unit)
        repeat(5) { yield() }
        assertEquals(listOf(10, 30), sent)

        sender.offer(40)
        repeat(5) { yield() }
        assertEquals(listOf(10, 30, 40), sent)
    }

    @Test
    fun `une erreur n'arrete pas les envois suivants`() = runBlocking {
        val sent = mutableListOf<Int>()
        val sender = LatestSender<Int>(this) { value ->
            sent += value
            if (value == 1) error("ordinateur injoignable")
        }
        sender.offer(1)
        repeat(3) { yield() }
        sender.offer(2)
        repeat(3) { yield() }
        assertEquals(listOf(1, 2), sent)
    }
}
