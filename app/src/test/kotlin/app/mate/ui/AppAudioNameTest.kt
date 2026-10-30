package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAudioNameTest {
    @Test
    fun `le nom de l'editeur saute, le reste ne change pas`() {
        assertEquals("Teams", shortAppName("Microsoft Teams"))
        assertEquals("Discord", shortAppName("Discord"))
        assertEquals("Microsoft", shortAppName("Microsoft"))
    }
}
