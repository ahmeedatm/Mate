package app.mate.ui

import app.mate.net.PcNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class NotificationsModelTest {

    private fun n(title: String = "Marie Dupont", body: String = "Réunion déplacée à 15 h", app: String = "Outlook") =
        PcNotification("1", app, title, body, 0)

    @Test
    fun `bandeau appli titre puis texte`() {
        assertEquals("Outlook · Marie Dupont\nRéunion déplacée à 15 h", notificationBannerText(n(), discreet = false))
    }

    @Test
    fun `bandeau en mode discret sans le texte`() {
        assertEquals("Outlook · Marie Dupont", notificationBannerText(n(), discreet = true))
    }

    @Test
    fun `titre vide omis proprement`() {
        assertEquals("Outlook\nRéunion déplacée à 15 h", notificationBannerText(n(title = "  "), discreet = false))
        assertEquals("Outlook", notificationBannerText(n(title = ""), discreet = true))
    }

    @Test
    fun `texte vide pas de seconde ligne`() {
        assertEquals("Outlook · Marie Dupont", notificationBannerText(n(body = " \n "), discreet = false))
    }

    @Test
    fun `texte du bandeau sur une ligne et coupe`() {
        assertEquals("Teams · A\nun deux trois", notificationBannerText(n(app = "Teams", title = "A", body = "un\n\ndeux   trois"), false))
        val long = notificationBannerText(n(body = "x".repeat(500)), discreet = false).substringAfter('\n')
        assertEquals("x".repeat(160) + "…", long)
    }

    @Test
    fun `texte de la liste masque en mode discret`() {
        assertEquals("Réunion déplacée à 15 h", notificationBody(n(), discreet = false))
        assertNull(notificationBody(n(), discreet = true))
        assertNull(notificationBody(n(body = ""), discreet = false))
    }

    @Test
    fun `bandeau en direct appli au dessus titre et texte`() {
        assertEquals(BannerContent("Marie Dupont", "Réunion déplacée à 15 h", "Outlook", BannerIcon.App("Outlook")), notificationBanner(n(), discreet = false))
        assertEquals(BannerContent("Marie Dupont", null, "Outlook", BannerIcon.App("Outlook")), notificationBanner(n(), discreet = true))
    }

    @Test
    fun `bandeau en direct sans titre le texte devient l objet`() {
        assertEquals(BannerContent("Réunion déplacée à 15 h", null, "Outlook", BannerIcon.App("Outlook")), notificationBanner(n(title = " "), discreet = false))
        // Discret et sans titre : le nom de l'appli seul, sans le répéter au-dessus.
        assertEquals(BannerContent("Outlook", null, null, BannerIcon.App("Outlook")), notificationBanner(n(title = ""), discreet = true))
    }

    @Test
    fun `heure seule le jour meme et jour en plus la veille`() {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        // 29/10/2026 14:32 et 28/10/2026 23:05, heure de Paris (UTC+1).
        val now = 1793280720000L + 3_600_000
        val today = 1793280720000L
        val yesterday = 1793225100000L
        assertEquals("14:32", notificationTime(today, now, Locale.FRANCE, paris))
        assertEquals("mer. 28 oct. · 23:05", notificationTime(yesterday, now, Locale.FRANCE, paris))
    }
}
