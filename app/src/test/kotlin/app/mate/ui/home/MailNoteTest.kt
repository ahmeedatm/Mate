package app.mate.ui.home

import app.mate.net.Mail
import app.mate.net.MailAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailNoteTest {
    private fun account(unread: Int?, error: String? = null) = MailAccount("google", "Google", unread, error)

    @Test
    fun failedReadWinsOverEverything() {
        assertEquals(MailNote.ERROR, mailNote(Mail(listOf(account(3))), hasError = true))
        assertEquals(MailNote.ERROR, mailNote(null, hasError = true))
    }

    @Test
    fun noAccountAsksToConnectOne() {
        assertEquals(MailNote.NO_ACCOUNT, mailNote(Mail(emptyList()), hasError = false))
        assertEquals(MailNote.NO_ACCOUNT, mailNote(null, hasError = false))
    }

    @Test
    fun everyAccountInErrorShowsTheirPhrase() {
        assertEquals(MailNote.ACCOUNT_ERROR, mailNote(Mail(listOf(account(null, "Reconnecte"))), hasError = false))
    }

    @Test
    fun aWorkingAccountLetsTheNumberSpeak() {
        assertNull(mailNote(Mail(listOf(account(null, "Reconnecte"), account(2))), hasError = false))
    }
}
