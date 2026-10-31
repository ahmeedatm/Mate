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
}
