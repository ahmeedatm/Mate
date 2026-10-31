package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Valeurs calculées par le backend (src/pairing.ts) : les deux côtés doivent trouver le même code. */
class PairingCodeTest {

    @Test
    fun `code identique a celui du backend`() {
        assertEquals("459822", PairingCode.code("aa".repeat(32), "tablette", "ordinateur"))
        assertEquals("870519", PairingCode.code("", "tablette", "ordinateur"))
    }

    @Test
    fun `engagement identique a celui du backend`() {
        val secret = PairingCode.secretOf(ByteArray(32) { it.toByte() })
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", secret.nonce)
        assertEquals("6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A", secret.commit)
    }

    @Test
    fun `un autre certificat donne un autre code`() {
        assertNotEquals(PairingCode.code("aa".repeat(32), "t", "o"), PairingCode.code("bb".repeat(32), "t", "o"))
    }

    @Test
    fun `nombre secret de 43 caracteres base64url, jamais deux fois le meme`() {
        val a = PairingCode.newSecret()
        assertEquals(43, a.nonce.length)
        assert(a.nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(a.nonce, PairingCode.newSecret().nonce)
    }

    @Test
    fun `base64url sans remplissage`() {
        assertEquals("", PairingCode.base64Url(ByteArray(0)))
        assertEquals("_w", PairingCode.base64Url(byteArrayOf(-1)))
        assertEquals("__8", PairingCode.base64Url(byteArrayOf(-1, -1)))
        assertEquals("____", PairingCode.base64Url(byteArrayOf(-1, -1, -1)))
    }

    @Test
    fun `affichage en deux groupes`() {
        assertEquals("123 456", PairingCode.spaced("123456"))
    }
}
