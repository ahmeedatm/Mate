package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test
    fun `compagnon a jour en HTTPS sur tlsPort`() {
        val f = Discovery.parse("""{"service":"mate","id":"a","name":"MAW","os":"mac","port":3000,"tlsPort":3001}""", "192.168.3.17")!!
        assertEquals("https://192.168.3.17:3001", f.url)
        assertFalse(f.outdated)
    }

    @Test
    fun `compagnon d'avant le HTTPS sans tlsPort est a mettre a jour`() {
        val f = Discovery.parse("""{"service":"mate","id":"a","name":"MAW","os":"mac","port":3000}""", "192.168.3.17")!!
        assertEquals("http://192.168.3.17:3000", f.url)
        assertTrue(f.outdated)
    }

    @Test
    fun `reponse etrangere ou illisible ignoree`() {
        assertNull(Discovery.parse("""{"service":"autre","id":"a"}""", "192.168.3.17"))
        assertNull(Discovery.parse("""{"service":"mate"}""", "192.168.3.17"))
        assertNull(Discovery.parse("MATE?", "192.168.3.17"))
    }

    @Test
    fun `l'ordinateur dit s'il attend une tablette, un ancien compagnon jamais`() {
        val waiting = Discovery.parse(
            """{"service":"mate","id":"a","name":"MAW","os":"mac","port":3000,"tlsPort":3001,"waiting":true,"waitingMs":4200}""",
            "192.168.3.17",
        )!!
        assertTrue(waiting.waiting)
        assertEquals(4_200L, waiting.waitingMs)
        val idle = Discovery.parse("""{"service":"mate","id":"b","port":3000,"tlsPort":3001,"waiting":false,"waitingMs":0}""", "192.168.3.18")!!
        assertFalse(idle.waiting)
        val old = Discovery.parse("""{"service":"mate","id":"c","port":3000,"tlsPort":3001}""", "192.168.3.19")!!
        assertFalse(old.waiting)
        assertEquals(Long.MAX_VALUE, old.waitingMs)
    }

    private fun computer(id: String, waiting: Boolean, waitingMs: Long, outdated: Boolean = false) =
        FoundComputer(id, id, "mac", "https://$id:3001", outdated, waiting, waitingMs)

    @Test
    fun `pickWaiting prend la page ouverte en dernier`() {
        val list = listOf(computer("vieux", true, 60_000), computer("neuf", true, 3_000), computer("libre", false, 0))
        assertEquals("neuf", Discovery.pickWaiting(list)?.id)
    }

    @Test
    fun `pickWaiting ignore ceux qui n'attendent pas, les anciens compagnons et les compagnons a mettre a jour`() {
        assertNull(Discovery.pickWaiting(emptyList()))
        assertNull(Discovery.pickWaiting(listOf(computer("libre", false, 0))))
        assertNull(Discovery.pickWaiting(listOf(computer("ancien", false, Long.MAX_VALUE))))
        assertNull(Discovery.pickWaiting(listOf(computer("trop-vieux", true, 1_000, outdated = true))))
        assertEquals("ok", Discovery.pickWaiting(listOf(computer("trop-vieux", true, 1_000, outdated = true), computer("ok", true, 9_000)))?.id)
    }
}
