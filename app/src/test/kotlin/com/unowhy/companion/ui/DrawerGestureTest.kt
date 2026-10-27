package com.unowhy.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerGestureTest {
    @Test
    fun `un toucher sans mouvement ouvre`() {
        assertTrue(DrawerGesture.shouldOpen(0f, 0f))
    }

    @Test
    fun `un glisser court et lent referme`() {
        assertFalse(DrawerGesture.shouldOpen(0.2f, 0f))
    }

    @Test
    fun `un glisser court mais rapide ouvre`() {
        assertTrue(DrawerGesture.shouldOpen(0.2f, 700f))
    }

    @Test
    fun `plus de 40 pourcent ouvre`() {
        assertTrue(DrawerGesture.shouldOpen(0.5f, 0f))
    }

    @Test
    fun `un geste vers la gauche referme meme loin`() {
        assertFalse(DrawerGesture.shouldOpen(0.5f, -400f))
    }

    @Test
    fun `un toucher suivi d'un retrait vers la gauche referme`() {
        assertFalse(DrawerGesture.shouldOpen(0f, -400f))
    }

    @Test
    fun `la progression est bornee entre 0 et 1`() {
        assertEquals(0f, DrawerGesture.progress(-50f, 330f), 0f)
        assertEquals(1f, DrawerGesture.progress(900f, 330f), 0f)
        assertEquals(0.5f, DrawerGesture.progress(165f, 330f), 0.0001f)
    }
}
