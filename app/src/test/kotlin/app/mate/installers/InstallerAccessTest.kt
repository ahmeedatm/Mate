package app.mate.installers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class InstallerAccessTest {

    private val tokens = listOf("jeton-bureau", "jeton-maison")

    private fun resolve(path: String, auth: String? = null, code: String? = "K7M2Q") = InstallerAccess.resolve(path, auth, code, tokens)

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

    @Test
    fun `un jeton d'ordinateur appaire passe sans code, sans prefixe`() {
        val granted = resolve("/installers.json", "Bearer jeton-maison", code = null)!!
        assertEquals("/installers.json", granted.path)
        assertEquals("", granted.prefix)
        assertNotNull(resolve("/Mate-Mac.zip", "Bearer jeton-bureau"))
    }

    @Test
    fun `jeton inconnu, vide ou mal forme, refuse`() {
        assertNull(resolve("/installers.json", "Bearer autre"))
        assertNull(resolve("/installers.json", "Bearer "))
        assertNull(resolve("/installers.json", "Bearer"))
        assertNull(resolve("/installers.json", "jeton-bureau"))
        assertNull(resolve("/installers.json", "Basic jeton-bureau"))
        assertNull(resolve("/installers.json", "Bearer jeton-bureau-"))
        assertNull(InstallerAccess.resolve("/installers.json", "Bearer ", null, listOf("")))
        assertNull(InstallerAccess.resolve("/installers.json", "Bearer jeton-bureau", null, emptyList()))
    }
}
