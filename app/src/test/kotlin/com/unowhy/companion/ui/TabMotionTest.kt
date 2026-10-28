package com.unowhy.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TabMotionTest {
    @Test
    fun `vers la droite, le bord droit mene`() {
        // Pastille de [0, 100] vers [200, 300] : bord avant à mi-chemin, bord arrière encore au départ.
        assertEquals(Pair(0f, 200f), TabMotion.edges(0f, 100f, 200f, 300f, lead = 0.5f, trail = 0f))
    }

    @Test
    fun `vers la gauche, le bord gauche mene`() {
        assertEquals(Pair(100f, 300f), TabMotion.edges(200f, 300f, 0f, 100f, lead = 0.5f, trail = 0f))
    }

    @Test
    fun `arrivee, la pastille couvre l'onglet choisi`() {
        assertEquals(Pair(200f, 300f), TabMotion.edges(0f, 100f, 200f, 300f, lead = 1f, trail = 1f))
    }

    @Test
    fun `le contenu entre du cote de l'onglet choisi`() {
        assertEquals(1, TabMotion.direction(from = 1, to = 3))
        assertEquals(-1, TabMotion.direction(from = 3, to = 1))
    }

    @Test
    fun `sans onglet de depart, le contenu arrive sans glisser`() {
        assertEquals(0, TabMotion.direction(from = -1, to = 2))
    }
}
