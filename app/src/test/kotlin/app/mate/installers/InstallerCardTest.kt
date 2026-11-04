package app.mate.installers

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallerCardTest {

    private val link = "http://192.168.1.20:8080/K7M2Q"

    @Test
    fun `sans WiFi la carte invite a connecter le WiFi, meme si l'installeur est la`() {
        assertEquals(InstallerCard.NoWifi, InstallerCard.of(wifi = false, ready = false, percent = null, link = null))
        assertEquals(InstallerCard.NoWifi, InstallerCard.of(wifi = false, ready = true, percent = 40, link = link))
    }

    @Test
    fun `installeur pas encore la, preparation, avec le pourcentage une fois le telechargement parti`() {
        assertEquals(InstallerCard.Preparing(null), InstallerCard.of(wifi = true, ready = false, percent = null, link = link))
        assertEquals(InstallerCard.Preparing(42), InstallerCard.of(wifi = true, ready = false, percent = 42, link = link))
    }

    @Test
    fun `installeur prete, l'adresse avec son code, meme si un autre fichier se telecharge encore`() {
        assertEquals(InstallerCard.Ready(link), InstallerCard.of(wifi = true, ready = true, percent = null, link = link))
        assertEquals(InstallerCard.Ready(link), InstallerCard.of(wifi = true, ready = true, percent = 10, link = link))
    }

    @Test
    fun `installeur prete mais adresse introuvable, encore en preparation`() {
        assertEquals(InstallerCard.Preparing(null), InstallerCard.of(wifi = true, ready = true, percent = null, link = null))
    }
}
