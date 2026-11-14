package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInboxTest {
    private fun thread(hash: String, unread: Int = 0, canReply: Boolean = false) =
        CyberpunkThread("n$hash", null, unread, null, hash = hash, canReply = canReply)

    @Test
    fun keepsUnreadAndThreadsWaitingForAnAnswer() {
        val all = listOf(thread("1"), thread("2", unread = 1), thread("3", canReply = true))
        assertEquals(listOf("3", "2"), PhoneInbox.messages(all, emptySet()).map { it.hash })
    }

    @Test
    fun threadsOpenedSinceTheDrawerOpenedStayListedOnceRead() {
        val all = listOf(thread("1"), thread("2"))
        assertEquals(listOf("2"), PhoneInbox.messages(all, setOf("2")).map { it.hash })
    }

    @Test
    fun ordersLikeTheGameAnswerableFirstThenUnreadThenOpened() {
        val all = listOf(thread("opened"), thread("unread", unread = 3), thread("reply", canReply = true), thread("both", unread = 1, canReply = true))
        assertEquals(listOf("reply", "both", "unread", "opened"), PhoneInbox.messages(all, setOf("opened")).map { it.hash })
    }

    @Test
    fun reloadsEveryTwoSecondsForTwelveSecondsUntilNewChoicesShowUp() {
        assertTrue(PhoneInbox.shouldReload("3:7", "3:7", false, 0))
        // Sa réponse est arrivée, les messages du contact suivent : on continue.
        assertTrue(PhoneInbox.shouldReload("3:7", "4:9", false, 2_000))
        assertTrue(PhoneInbox.shouldReload("3:7", "6:2", false, 11_999))
        assertFalse(PhoneInbox.shouldReload("3:7", "6:2", false, 12_000))
        // Nouveaux choix : au joueur.
        assertFalse(PhoneInbox.shouldReload("3:7", "6:2", true, 4_000))
        // Fil inchangé, anciens choix encore là : on attend encore.
        assertTrue(PhoneInbox.shouldReload("3:7", "3:7", true, 4_000))
        assertEquals(2_000L, PhoneInbox.RELOAD_MS)
    }
}
