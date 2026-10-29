package com.unowhy.companion.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameLogicTest {

    private val min = 60_000L
    private val idle = FrameLogic.Context(
        idleMs = 5 * min, delayMin = 5, night = false, batteryLevel = 80, charging = true, busy = false,
    )

    @Test
    fun `se lance apres le delai sans toucher`() {
        assertTrue(FrameLogic.shouldStart(idle))
        assertFalse(FrameLogic.shouldStart(idle.copy(idleMs = 4 * min)))
    }

    @Test
    fun `jamais, la nuit ou occupee`() {
        assertFalse(FrameLogic.shouldStart(idle.copy(delayMin = 0, idleMs = 60 * min)))
        assertFalse(FrameLogic.shouldStart(idle.copy(night = true)))
        assertFalse(FrameLogic.shouldStart(idle.copy(busy = true)))
    }

    @Test
    fun `batterie faible debranchee arrete le cadre, branchee non`() {
        assertFalse(FrameLogic.shouldStart(idle.copy(batteryLevel = 19, charging = false)))
        assertTrue(FrameLogic.shouldStart(idle.copy(batteryLevel = 20, charging = false)))
        assertTrue(FrameLogic.shouldStart(idle.copy(batteryLevel = 5, charging = true)))
        assertTrue(FrameLogic.shouldStart(idle.copy(batteryLevel = null, charging = false)))
    }

    @Test
    fun `luminosite jamais plus haute que celle choisie`() {
        assertEquals(0.35f, FrameLogic.level(0.8f, charging = true))
        assertEquals(0.2f, FrameLogic.level(0.8f, charging = false))
        assertEquals(0.1f, FrameLogic.level(0.1f, charging = true))
    }

    @Test
    fun `cache retelecharge une photo remplacee et efface les retirees`() {
        val a = FramePhoto("Cadre photo/a.jpg", 10, 1)
        val b = FramePhoto("Cadre photo/b.jpg", 20, 2)
        val sync = FrameLogic.plan(listOf(a, b), setOf(FrameLogic.cacheName(a), "ancienne.jpg"))
        assertEquals(listOf(b), sync.download)
        assertEquals(setOf("ancienne.jpg"), sync.delete)
        assertNotEquals(FrameLogic.cacheName(a), FrameLogic.cacheName(a.copy(mtimeMs = 3)))
    }

    @Test
    fun `reduction au decodage garde au moins la taille de l ecran`() {
        assertEquals(1, FrameLogic.sampleSize(1280, 800, 1280, 800))
        assertEquals(2, FrameLogic.sampleSize(4000, 3000, 1280, 800))
        assertEquals(1, FrameLogic.sampleSize(800, 600, 1280, 800))
        // 12 Mpx en portrait, gardée en 600×800 : réduite par 4 au décodage (756×1008).
        assertEquals(4, FrameLogic.sampleSize(3024, 4032, 600, 800))
    }

    @Test
    fun `paysage remplit l ecran, portrait reste entiere, jamais agrandie`() {
        assertEquals(Pair(1280, 960), FrameLogic.scaledSize(4000, 3000, 1280, 800))
        assertEquals(Pair(1422, 800), FrameLogic.scaledSize(1920, 1080, 1280, 800))
        assertEquals(Pair(600, 800), FrameLogic.scaledSize(3024, 4032, 1280, 800))
        assertEquals(Pair(640, 480), FrameLogic.scaledSize(640, 480, 1280, 800))
    }

    @Test
    fun `chaque photo passe une fois par tour, jamais deux fois de suite`() {
        val names = listOf("a", "b", "c", "d")
        val shuffle = FrameLogic.Shuffle(Random(7))
        var previous: String? = null
        repeat(10) {
            val round = (1..names.size).map { shuffle.next(names)!! }
            assertEquals(names.toSet(), round.toSet())
            assertNotEquals(previous, round.first())
            previous = round.last()
        }
    }

    @Test
    fun `photo retiree sautee, liste vide sans plantage`() {
        val shuffle = FrameLogic.Shuffle(Random(1))
        shuffle.next(listOf("a", "b", "c"))
        val rest = (1..2).map { shuffle.next(listOf("a", "b"))!! }
        assertTrue(rest.all { it in setOf("a", "b") })
        assertEquals(null, shuffle.next(emptyList()))
    }
}
