package app.mate.installers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class InstallerAccessTest {

    private fun resolve(path: String, code: String? = "K7M2Q") = InstallerAccess.resolve(path, code)

    @Test
    fun `le code a 5 caracteres sans ambiguite`() {
        repeat(200) {
            val code = InstallerAccess.newCode(Random(it.toLong()))
            assertEquals(InstallerAccess.CODE_LENGTH, code.length)
            assertTrue(code, code.all { c -> c in InstallerAccess.ALPHABET })
        }
        assertTrue(InstallerAccess.ALPHABET.none { it in "01OIL" })
    }

    @Test
    fun `deux tirages ne donnent pas le meme code`() {
        assertTrue(InstallerAccess.newCode() != InstallerAccess.newCode())
    }

    @Test
    fun `le bon code ouvre la racine et les fichiers sous son prefixe`() {
        for (path in listOf("/K7M2Q", "/K7M2Q/")) {
            val granted = resolve(path)!!
            assertEquals("/", granted.path)
            assertEquals("/K7M2Q", granted.prefix)
        }
        assertEquals("/Mate-Mac.zip", resolve("/K7M2Q/Mate-Mac.zip")!!.path)
        assertEquals("/installers.json", resolve("/k7m2q/installers.json")!!.path)
    }

    @Test
    fun `sans code, avec un mauvais code ou sans code tire, refuse`() {
        assertNull(resolve("/"))
        assertNull(resolve("/installers.json"))
        assertNull(resolve("/Mate-Mac.zip"))
        assertNull(resolve("/AAAAA/installers.json"))
        assertNull(resolve("/K7M2/installers.json"))
        assertNull(resolve("/K7M2QQ/installers.json"))
        assertNull(resolve("/K7M2Q.zip"))
        assertNull(resolve("/", code = null))
        assertNull(resolve("//", code = null))
        assertNull(resolve("/", code = ""))
        assertNull(resolve("K7M2Q/installers.json"))
    }
}
