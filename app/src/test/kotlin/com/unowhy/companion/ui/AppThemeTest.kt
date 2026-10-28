package com.unowhy.companion.ui

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    private val context = FakeContext()

    @Test
    fun `Cadran, Cadran nuit et Retro`() {
        assertEquals(listOf(AppTheme.CADRAN, AppTheme.CADRAN_NUIT, AppTheme.RETRO), AppTheme.entries.toList())
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

    @Test
    fun `Retro enregistre est garde`() {
        Prefs.setTheme(context, "RETRO")
        assertEquals(AppTheme.RETRO, AppTheme.current(context))
    }
}
