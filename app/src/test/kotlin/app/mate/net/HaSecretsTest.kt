package app.mate.net

import app.mate.FakeContext
import app.mate.FakePrefs
import app.mate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class HaSecretsTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val other = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Test
    fun `le jeton chiffre se relit, et deux chiffrements ne se ressemblent pas`() {
        val a = HaSecrets.seal("jeton-tres-secret", key)
        val b = HaSecrets.seal("jeton-tres-secret", key)
        assertNotEquals(a, b)
        assertEquals("jeton-tres-secret", HaSecrets.open(a, key))
    }

    @Test
    fun `une autre cle ou un blob abime ne rend rien, sans planter`() {
        val a = HaSecrets.seal("jeton", key)
        assertNull(HaSecrets.open(a, other))
        assertNull(HaSecrets.open("abime", key))
        assertNull(HaSecrets.open(a.dropLast(4) + "AAAA", key))
    }

    @Test
    fun `les reglages gardes ne contiennent jamais le jeton en clair`() {
        val context = FakeContext()
        HaConfig.save(context, "http://192.168.1.10:8123/", "jeton-tres-secret", allowHttp = true) { key }
        val prefs = context.getSharedPreferences(Prefs.FILE, 0) as FakePrefs
        assertFalse(prefs.values.values.any { it.toString().contains("jeton-tres-secret") })
        val config = HaConfig.load(context) { key }!!
        assertEquals("http://192.168.1.10:8123", config.url)
        assertEquals("jeton-tres-secret", config.token)
        assertEquals(true, config.allowHttp)
        assertFalse(config.toString().contains("jeton-tres-secret"))
    }

    @Test
    fun `sans reglage, ou cle perdue, Home Assistant n'est pas configure`() {
        val context = FakeContext()
        assertNull(HaConfig.load(context) { key })
        HaConfig.save(context, "https://ha.exemple.fr", "jeton", allowHttp = false) { key }
        assertNull(HaConfig.load(context) { other })
    }
}
