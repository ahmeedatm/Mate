package com.unowhy.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailApiTest {

    @Test
    fun `sans compte`() {
        assertEquals(MailSummary.NoAccount, parseMail("""{"status":"no-account"}"""))
    }

    @Test
    fun `a reconnecter`() {
        assertEquals(MailSummary.Reconnect, parseMail("""{"status":"reconnect"}"""))
    }

    @Test
    fun `etat inconnu d'un compagnon plus recent devient a reconnecter`() {
        assertEquals(MailSummary.Reconnect, parseMail("""{"status":"autre"}"""))
        assertEquals(MailSummary.Reconnect, parseMail("""{}"""))
    }

    @Test
    fun `compteurs, derniers non lus et prochain rendez-vous`() {
        val json = """{"status":"ok","unread":12,"junk":3,"drafts":2,"sent":1200,
            "recent":[{"from":"Léa Martin","subject":"Budget","receivedMs":1761730200000},{"from":"","subject":"","receivedMs":1}],
            "next":{"title":"Point d'équipe","startMs":1761735600000,"endMs":1761739200000}}"""
        val ok = parseMail(json) as MailSummary.Ok
        assertEquals(listOf(12, 3, 2, 1200), listOf(ok.unread, ok.junk, ok.drafts, ok.sent))
        assertEquals(listOf(MailMessage("Léa Martin", "Budget", 1761730200000), MailMessage("", "", 1)), ok.recent)
        assertEquals(MailEvent("Point d'équipe", 1761735600000, 1761739200000), ok.next)
    }

    @Test
    fun `sans rendez-vous ni message`() {
        val ok = parseMail("""{"status":"ok","unread":0,"junk":0,"drafts":0,"sent":0,"recent":[],"next":null}""") as MailSummary.Ok
        assertEquals(emptyList<MailMessage>(), ok.recent)
        assertNull(ok.next)
    }
}
