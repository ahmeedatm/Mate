package com.unowhy.companion.ui

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    private val context = FakeContext()

    @Test
    fun `seuls Cadran et Cadran nuit restent`() {
        assertEquals(listOf(AppTheme.CADRAN, AppTheme.CADRAN_NUIT), AppTheme.entries.toList())
    }

    @Test
    fun `sans choix enregistre, Cadran`() {
        assertEquals(AppTheme.CADRAN, AppTheme.current(context))
    }

    @Test
    fun `un ancien theme enregistre retombe sur Cadran`() {
        for (old in listOf("NUIT", "CLAIR", "NEON", "CHAUD")) {
            Prefs.setTheme(context, old)
            assertEquals(AppTheme.CADRAN, AppTheme.current(context))
        }
    }

    @Test
    fun `Cadran nuit enregistre est garde`() {
        Prefs.setTheme(context, "CADRAN_NUIT")
        assertEquals(AppTheme.CADRAN_NUIT, AppTheme.current(context))
    }
}
