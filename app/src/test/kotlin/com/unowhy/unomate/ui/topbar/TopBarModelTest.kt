package com.unowhy.unomate.ui.topbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarModelTest {
    @Test
    fun `jamais regle, le bandeau d'avant, vide reste vide, inconnu et doublon ignores`() {
        assertEquals(listOf(TopItem.MUSIC, TopItem.CLOCK, TopItem.WIFI, TopItem.BATTERY), TopBarModel.parse(null))
        assertEquals(emptyList<TopItem>(), TopBarModel.parse(""))
        assertEquals(listOf(TopItem.WEATHER, TopItem.CLOCK), TopBarModel.parse("weather,game, clock,weather"))
    }

    @Test
    fun `relu tel qu'il a ete enregistre`() {
        val items = listOf(TopItem.FOCUS, TopItem.DATE, TopItem.BATTERY)
        assertEquals(items, TopBarModel.parse(TopBarModel.serialize(items)))
    }

    @Test
    fun `ajout au debut, sans doublon, Agents seulement si l'ordinateur les offre`() {
        val items = TopBarModel.DEFAULT
        assertEquals(TopItem.DATE, TopBarModel.add(items, TopItem.DATE).first())
        assertEquals(items, TopBarModel.add(items, TopItem.CLOCK))
        assertFalse(TopItem.AGENTS in TopBarModel.addable(items, agents = false))
        assertTrue(TopItem.AGENTS in TopBarModel.addable(items, agents = true))
        assertFalse(TopItem.CLOCK in TopBarModel.addable(items, agents = true))
    }

    @Test
    fun `retrait et rangement`() {
        val items = listOf(TopItem.MUSIC, TopItem.CLOCK, TopItem.WIFI, TopItem.BATTERY)
        assertEquals(listOf(TopItem.MUSIC, TopItem.WIFI, TopItem.BATTERY), TopBarModel.remove(items, TopItem.CLOCK))
        assertEquals(listOf(TopItem.CLOCK, TopItem.WIFI, TopItem.BATTERY, TopItem.MUSIC), TopBarModel.move(items, TopItem.MUSIC, 3))
        assertEquals(listOf(TopItem.BATTERY, TopItem.MUSIC, TopItem.CLOCK, TopItem.WIFI), TopBarModel.move(items, TopItem.BATTERY, -5))
        assertEquals(items, TopBarModel.move(items, TopItem.DATE, 0))
    }

    @Test
    fun `place qui manque, ceux du debut sautent, les caches ne comptent pas`() {
        assertEquals(0, TopBarModel.firstShown(listOf(100, 50, 50), gap = 10, room = 220))
        assertEquals(1, TopBarModel.firstShown(listOf(100, 50, 50), gap = 10, room = 200))
        assertEquals(0, TopBarModel.firstShown(listOf(100, 0, 50, 50), gap = 10, room = 220))
        assertEquals(3, TopBarModel.firstShown(listOf(100, 50, 50), gap = 10, room = 40))
    }
}
