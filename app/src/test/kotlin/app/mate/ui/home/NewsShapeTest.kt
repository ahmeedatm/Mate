package app.mate.ui.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsShapeTest {

    @Test
    fun `pastille, le nombre de titres pas encore vus`() {
        assertEquals(0, NewsShape.of(WidgetSize.S).titles)
        assertTrue(NewsShape.of(WidgetSize.S).side)
    }

    @Test
    fun `ligne, un titre par case en plus, sur deux lignes`() {
        assertEquals(1, NewsShape.of(WidgetSize.M).titles)
        assertEquals(3, NewsShape.of(WidgetSize.W).titles)
        assertEquals(2, NewsShape.of(WidgetSize.W).maxLines)
    }

    @Test
    fun `colonne, un titre par case de haut, sur trois lignes`() {
        assertEquals(2, NewsShape.of(WidgetSize.V).titles)
        assertEquals(4, NewsShape.of(WidgetSize.V4).titles)
        assertEquals(3, NewsShape.of(WidgetSize.V).maxLines)
    }

    @Test
    fun `plus grand, plus de texte par titre`() {
        assertTrue(NewsShape.of(WidgetSize.L).maxLines < NewsShape.of(WidgetSize.XL).maxLines)
        assertTrue(NewsShape.of(WidgetSize.XL).maxLines < NewsShape.of(WidgetSize.FULL).maxLines)
        assertEquals(5, NewsShape.of(WidgetSize.FULL).titles)
    }

    @Test
    fun `pas encore vus, et ce qu'on garde des titres vus`() {
        assertEquals(2, unseenCount(listOf("a", "b", "c"), setOf("b")))
        assertEquals(0, unseenCount(emptyList(), setOf("b")))
        // On ne garde que les titres encore dans le flux.
        assertEquals(setOf("b", "c"), seenAfter(setOf("b", "z"), listOf("a", "b", "c"), opened = "c"))
    }

    @Test
    fun `la taille du texte des titres suit la forme`() {
        assertTrue(NewsShape.of(WidgetSize.M).sp < NewsShape.of(WidgetSize.L).sp)
        assertTrue(NewsShape.of(WidgetSize.L).sp < NewsShape.of(WidgetSize.FULL).sp)
    }

    @Test
    fun `les titres vus se gardent dans la config du widget`() {
        val config = JSONObject()
        assertEquals(emptySet<String>(), readSeen(config))
        writeSeen(config, linkedSetOf("a", "b"))
        assertEquals(setOf("a", "b"), readSeen(config))
        // Un titre sorti du flux est oublié à la relecture suivante.
        writeSeen(config, seenAfter(readSeen(config), listOf("b", "c"), opened = null))
        assertEquals(setOf("b"), readSeen(config))
        // Une config abîmée ne casse rien.
        assertEquals(emptySet<String>(), readSeen(JSONObject().put("seen", "pas un tableau")))
    }
}
