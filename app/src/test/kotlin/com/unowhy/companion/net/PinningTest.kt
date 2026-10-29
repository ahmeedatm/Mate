package com.unowhy.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinningTest {

    /** Certificat fabriqué par le backend (src/tls.ts) et son empreinte calculée par `fingerprintOf`. */
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBLTCB0wIQK0o7vVX1ZGCeT7Q4ET/k6jAKBggqhkjOPQQDAjAbMRkwFwYDVQQD
        DBBDb21wYWdub24gVW5vd2h5MB4XDTI2MTAyODEwNTMyNFoXDTQ5MTIzMTIzNTk1
        OVowGzEZMBcGA1UEAwwQQ29tcGFnbm9uIFVub3doeTBZMBMGByqGSM49AgEGCCqG
        SM49AwEHA0IABAzw2lBGQ62YV9xuw4enzCXfrsLR5XIsL8/xVbKy9DjsrEbetcWR
        Gu2K4mS9oMFNYBaThzzr7K4yzKr3urZTomIwCgYIKoZIzj0EAwIDSQAwRgIhAJTp
        1YtttguGmluIBeXLS/6yGsTEtJQ10WTnaqNiAUdxAiEAiW+R3FgFljspotVb+92v
        O3dpWI6VirSU7Pcb8rdcVjw=
        -----END CERTIFICATE-----
    """.trimIndent()
    private val fingerprint = "2ce9dbad773cd7771e33952d1831fdf5f3fd0ab59c0f983d9c709c352a6f8419"

    private val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    @Test
    fun `empreinte identique a celle du backend`() {
        assertEquals(fingerprint, fingerprintOf(cert))
    }

    @Test
    fun `accepte le certificat epingle`() {
        PinnedTrust(fingerprint).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test(expected = CertificateException::class)
    fun `refuse un autre certificat`() {
        PinnedTrust("00".repeat(32)).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test
    fun `pendant l'appairage, retient le premier certificat puis exige le meme`() {
        val trust = PinnedTrust(null)
        assertNull(trust.seen)
        trust.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertEquals(fingerprint, trust.seen)
        trust.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test(expected = CertificateException::class)
    fun `refuse une chaine vide`() {
        PinnedTrust(null).checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
    }

    @Test
    fun `une empreinte par ordinateur, partagee entre les clients`() {
        assertTrue(PinnedTrust.forPin(fingerprint) === PinnedTrust.forPin(fingerprint))
    }

    @Test
    fun `seule la tablette elle-meme est locale`() {
        assertTrue(isLocalUrl("http://127.0.0.1:3000"))
        assertTrue(isLocalUrl("http://localhost:3000"))
        assertFalse(isLocalUrl("http://192.168.1.20:3000"))
        assertFalse(isLocalUrl("http://10.0.2.2:3000"))
    }

    @Test(expected = InsecureComputerException::class)
    fun `le jeton ne part jamais en clair sur le WiFi`() {
        Http("http://192.168.1.20:3000", "jeton", 1_000).request("GET", "/health")
    }
}
