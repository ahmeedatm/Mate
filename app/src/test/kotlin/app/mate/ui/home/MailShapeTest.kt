package app.mate.ui.home

import app.mate.net.Mail
import app.mate.net.MailAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailShapeTest {

    @Test
    fun `pastille, le nombre seul, l'icone a cote`() {
        val pill = MailShape.of(WidgetSize.S)
        assertTrue(pill.side)
        assertFalse(pill.accounts)
    }

    @Test
    fun `ligne, le detail par boite des 3 cases de large`() {
        assertFalse(MailShape.of(WidgetSize.M).accounts)
        assertTrue(MailShape.of(WidgetSize.M3).accounts)
        assertTrue(MailShape.of(WidgetSize.M3).side)
    }

    @Test
    fun `colonne et plus grand, le detail par boite`() {
        assertTrue(MailShape.of(WidgetSize.V).accounts)
        assertFalse(MailShape.of(WidgetSize.V).side)
        assertTrue(MailShape.of(WidgetSize.L).title)
    }

    @Test
    fun `plus grand, le nombre plus grand, tres grand en plein ecran`() {
        val sp = WidgetSize.entries.associateWith { MailShape.of(it).numberSp }
        assertTrue(sp.getValue(WidgetSize.S) < sp.getValue(WidgetSize.L))
        assertTrue(sp.getValue(WidgetSize.L) < sp.getValue(WidgetSize.XL))
        assertTrue(sp.getValue(WidgetSize.XL) < sp.getValue(WidgetSize.FULL))
    }

    @Test
    fun `une ligne par boite, son nombre ou son erreur, la plus chargee d'abord`() {
        val mail = Mail(listOf(MailAccount("google", "Gmail", 3, null), MailAccount("microsoft", "Outlook", 9, null), MailAccount("google", "Perso", null, "Reconnecte ce compte")))
        assertEquals(listOf("Outlook", "Gmail", "Perso"), mailBoxes(mail).map { it.label })
        assertEquals(listOf("9", "3", null), mailBoxes(mail).map { it.count })
        assertEquals("Reconnecte ce compte", mailBoxes(mail).last().error)
    }
}
