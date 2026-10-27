package com.unowhy.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RollingTextTest {

    @Test
    fun `une minute qui passe change une cellule`() {
        assertEquals(listOf(4), changedCells("14:07", "14:08"))
    }

    @Test
    fun `de neuf a dix, deux cellules`() {
        assertEquals(listOf(3, 4), changedCells("14:09", "14:10"))
    }

    @Test
    fun `minuit change les quatre chiffres, jamais les deux points`() {
        assertEquals(listOf(0, 1, 3, 4), changedCells("23:59", "00:00"))
    }

    @Test
    fun `texte identique, aucune cellule`() {
        assertEquals(emptyList<Int>(), changedCells("14:07", "14:07"))
    }

    @Test
    fun `compteur de deux chiffres`() {
        assertEquals(listOf(1), changedCells("23", "22"))
    }

    @Test
    fun `compteur qui perd un chiffre, cellule de remplissage a gauche`() {
        assertEquals(listOf(0, 1), changedCells("10", "9"))
        assertEquals(listOf(0, 1), changedCells("9", "10"))
    }

    @Test
    fun `texte complete a gauche jusqu'a la largeur voulue`() {
        assertEquals("${FILL_CHAR}9", padCells("9", 2))
        assertEquals("10", padCells("10", 2))
        assertEquals("123", padCells("123", 2))
    }
}
