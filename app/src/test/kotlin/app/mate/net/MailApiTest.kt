package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailApiTest {
    @Test
    fun readsEachAccountWithItsUnreadCount() {
        val mail = MailApi.parse(
            """{"accounts":[{"provider":"google","label":"Google","email":"a@exemple.fr","unread":3},
                {"provider":"microsoft","label":"Microsoft","email":"b@exemple.fr","unread":4}]}""",
        )
        assertEquals(listOf("google", "microsoft"), mail.accounts.map { it.provider })
        assertEquals(7, mail.total)
    }

    @Test
    fun noAccountMeansEmptyList() {
        val mail = MailApi.parse("""{"accounts":[]}""")
        assertTrue(mail.accounts.isEmpty())
        assertEquals(0, mail.total)
    }

    @Test
    fun anAccountInErrorKeepsItsPhraseAndCountsForNothing() {
        val mail = MailApi.parse(
            """{"accounts":[{"provider":"google","label":"Google","email":"a@exemple.fr","unread":null,"error":"Reconnecte le compte"},
                {"provider":"microsoft","label":"Microsoft","email":"b@exemple.fr","unread":2}]}""",
        )
        assertNull(mail.accounts[0].unread)
        assertEquals("Reconnecte le compte", mail.accounts[0].error)
        assertEquals(2, mail.total)
    }

    @Test
    fun opensTheFirstAccountWithUnreadMailElseTheFirstOne() {
        val mail = MailApi.parse(
            """{"accounts":[{"provider":"google","label":"G","email":"a@x.fr","unread":0},
                {"provider":"microsoft","label":"M","email":"b@x.fr","unread":5}]}""",
        )
        assertEquals("microsoft", mail.target?.provider)
        val none = MailApi.parse("""{"accounts":[{"provider":"google","label":"G","email":"a@x.fr","unread":0}]}""")
        assertEquals("google", none.target?.provider)
        assertNull(MailApi.parse("""{"accounts":[]}""").target)
    }
}
