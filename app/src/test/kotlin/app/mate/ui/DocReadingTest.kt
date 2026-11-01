package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocReadingTest {

    @Test
    fun `readStart ne lit que le debut d'un gros fichier`() {
        val file = File.createTempFile("doc", ".log").apply { deleteOnExit() }
        file.writeText("é".repeat(300_000))
        assertEquals("é".repeat(200_000), DocReading.readStart(file, 200_000))
        file.writeText("court")
        assertEquals("court", DocReading.readStart(file, 200_000))
    }

    @Test
    fun `sampleSize reduit une image haute meme si sa largeur tient`() {
        val sample = DocReading.sampleSize(1000, 40_000, 1400)
        assertTrue("$sample", (1000L / sample) * (40_000L / sample) <= DocReading.MAX_PIXELS)
    }

    @Test
    fun `sampleSize garde une image ordinaire et reduit une large`() {
        assertEquals(1, DocReading.sampleSize(1200, 900, 1400))
        assertEquals(2, DocReading.sampleSize(4000, 3000, 1400))
    }
}
