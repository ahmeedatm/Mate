package com.unowhy.companion.installers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerPageTest {

    private val mac = Installer(Os.MAC, "Compagnon-Unowhy-Mac.zip", 77_400_000, "aa", "1.2.0")
    private val windows = Installer(Os.WINDOWS, "Compagnon-Unowhy-Windows.exe", 23_000_000, "bb", "1.2.0")

    @Test
    fun `sans installeur la page dit qu'ils arrivent`() {
        val html = InstallerPage.render(Os.MAC, emptyList())
        assertTrue(html.contains("pas encore sur la tablette"))
        assertFalse(html.contains("href=\"/"))
    }

    @Test
    fun `le Mac voit l'installeur Mac en premier avec ses etapes et l'autre en lien`() {
        val html = InstallerPage.render(Os.MAC, listOf(mac, windows))
        assertTrue(html.indexOf("Télécharger Compagnon-Unowhy-Mac.zip (77 Mo)") in 0 until html.indexOf("Autre ordinateur"))
        assertTrue(html.contains("Ouvrir quand même"))
        assertTrue(html.contains("Accessibilité"))
        assertTrue(html.contains("<a href=\"/Compagnon-Unowhy-Windows.exe\">PC Windows"))
        assertFalse(html.contains("SmartScreen"))
    }

    @Test
    fun `Windows voit SmartScreen`() {
        val html = InstallerPage.render(Os.WINDOWS, listOf(mac, windows))
        assertTrue(html.contains("Télécharger Compagnon-Unowhy-Windows.exe (23 Mo)"))
        assertTrue(html.contains("SmartScreen"))
        assertTrue(html.contains("<a href=\"/Compagnon-Unowhy-Mac.zip\">Mac"))
    }

    @Test
    fun `OS inconnu, les deux a egalite`() {
        val html = InstallerPage.render(null, listOf(mac, windows))
        assertTrue(html.contains("Télécharger Compagnon-Unowhy-Mac.zip"))
        assertTrue(html.contains("Télécharger Compagnon-Unowhy-Windows.exe"))
        assertFalse(html.contains("Autre ordinateur"))
    }

    @Test
    fun `installeur de l'OS du visiteur pas encore la mais l'autre oui`() {
        val html = InstallerPage.render(Os.MAC, listOf(windows))
        assertTrue(html.contains("L'appli Mac n'est pas encore sur la tablette"))
        assertTrue(html.contains("Télécharger Compagnon-Unowhy-Windows.exe"))
    }

    @Test
    fun `la carte explique la mise a jour par le panneau d'un compagnon deja lance`() {
        val html = InstallerPage.render(Os.WINDOWS, listOf(windows))
        assertTrue(html.contains("Déjà lancé ?"))
        assertTrue(html.contains("Chercher une mise à jour"))
        assertTrue(html.contains("Retirer l&#39;ancienne installation") || html.contains("Retirer l'ancienne installation"))
    }

    @Test
    fun `rien n'est installe et Windows ouvre le pare-feu depuis le panneau`() {
        val windowsHtml = InstallerPage.render(Os.WINDOWS, listOf(windows))
        assertTrue(windowsHtml.contains("rien n'est installé") || windowsHtml.contains("rien n&#39;est installé"))
        assertTrue(windowsHtml.contains("pare-feu"))
        assertFalse(InstallerPage.render(Os.MAC, listOf(mac)).contains("Applications"))
    }
}
