package com.unowhy.unomate.ui.home

import com.unowhy.unomate.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutKeyTest {

    @Test
    fun `les cles different selon la famille de theme`() {
        assertNotEquals(HomeLayout.key(false), HomeLayout.key(true))
        assertEquals("home_layout", HomeLayout.key(false))
    }

    @Test
    fun `la disposition Cadran ne touche pas celle des autres themes`() {
        val context = FakeContext()
        val mine = HomeLayout(mutableListOf(Placed(WidgetType.NOTE, WidgetSize.S, 0, 3, 3)), 1)
        HomeLayout.save(context, mine, cadran = false)

        val cadran = HomeLayout.load(context, cadran = true)
        assertEquals(Template.defaultFor(true).build().widgets.map { it.type }, cadran.widgets.map { it.type })

        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.WATER, WidgetSize.S, 0, 0, 0)), 1), cadran = true)
        assertEquals(listOf(WidgetType.NOTE), HomeLayout.load(context, cadran = false).widgets.map { it.type })
        assertEquals(listOf(WidgetType.WATER), HomeLayout.load(context, cadran = true).widgets.map { it.type })
    }

    @Test
    fun `has lit la disposition du theme`() {
        val context = FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.WATER, WidgetSize.S, 0, 0, 0)), 1), cadran = true)
        assertTrue(HomeLayout.has(context, cadran = true, WidgetType.WATER))
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.NOTE, WidgetSize.S, 0, 0, 0)), 1), cadran = false)
        assertFalse(HomeLayout.has(context, cadran = false, WidgetType.WATER))
    }

    @Test
    fun `le defaut des autres themes reste le modele Travail`() {
        assertEquals(Template.WORK, Template.defaultFor(false))
        assertEquals(Template.WORK.build().widgets.size, HomeLayout.load(FakeContext(), cadran = false).widgets.size)
    }
}
