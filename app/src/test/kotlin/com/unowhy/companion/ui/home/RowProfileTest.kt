package com.unowhy.companion.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RowProfileTest {

    @Test
    fun `le profil Cadran a 740 de haut donne exactement ses lignes`() {
        assertArrayEquals(intArrayOf(232, 92, 184, 184), RowProfile.rowHeights(740, 16, RowProfile.CADRAN_ROWS))
    }

    @Test
    fun `somme des lignes et des gouttieres egale la hauteur`() {
        for (total in listOf(600, 641, 739, 800)) {
            val rows = RowProfile.rowHeights(total, 16, RowProfile.CADRAN_ROWS)
            assertEquals("total $total", total, rows.sum() + 16 * (rows.size - 1))
        }
    }

    @Test
    fun `le reste va a la derniere ligne`() {
        val rows = RowProfile.rowHeights(601, 16, intArrayOf(1, 1, 1, 1))
        assertArrayEquals(intArrayOf(138, 138, 138, 139), rows)
    }

    @Test
    fun `poids egaux donnent des lignes egales`() {
        assertArrayEquals(intArrayOf(135, 135, 135, 135), RowProfile.rowHeights(600, 20, intArrayOf(1, 1, 1, 1)))
    }
}
