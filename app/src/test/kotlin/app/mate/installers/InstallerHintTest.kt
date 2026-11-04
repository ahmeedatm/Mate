package app.mate.installers

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallerHintTest {

    private val link = "http://192.168.1.20:8080/K7M2Q"

    @Test
    fun `appli d'essai, jamais d'adresse, meme si elle en a une`() {
        assertEquals(InstallerHint.Trial, InstallerHint.of(trial = true, link = link))
        assertEquals(InstallerHint.Trial, InstallerHint.of(trial = true, link = null))
    }

    @Test
    fun `stable sans reseau, il faut le WiFi`() {
        assertEquals(InstallerHint.NoWifi, InstallerHint.of(trial = false, link = null))
    }

    @Test
    fun `stable avec reseau, l'adresse avec son code`() {
        assertEquals(InstallerHint.Address(link), InstallerHint.of(trial = false, link = link))
    }
}
