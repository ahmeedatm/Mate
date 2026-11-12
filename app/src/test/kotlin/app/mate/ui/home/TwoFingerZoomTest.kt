package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerZoomTest {
    @Test
    fun bothFingersOnTheSameWidget() {
        assertEquals("transports", TwoFingerZoom.target("transports", "transports"))
    }

    @Test
    fun twoWidgetsOrOneFingerOutside() {
        assertNull(TwoFingerZoom.target("transports", "horloge"))
        assertNull(TwoFingerZoom.target("transports", null))
        assertNull(TwoFingerZoom.target(null, "transports"))
        assertNull(TwoFingerZoom.target<String>(null, null))
    }

    @Test
    fun everyZoomableWidgetButThePad() {
        // Transports et Fenêtres ouvertes gardent le toucher pour eux : à deux doigts, ils s'agrandissent quand même.
        assertTrue(WidgetType.TRANSPORT.twoFingerZoom)
        assertTrue(WidgetType.OPEN.twoFingerZoom)
        // Le pavé tactile se sert déjà de deux doigts (défiler, pincer).
        assertTrue(WidgetType.PAD.zoomable)
        assertFalse(WidgetType.PAD.twoFingerZoom)
        // Rien de plus qu'un agrandissable : pas les widgets sans carte, ni la page web.
        assertFalse(WidgetType.HERO.twoFingerZoom)
        assertFalse(WidgetType.WEB.twoFingerZoom)
        for (type in WidgetType.entries) if (type.twoFingerZoom) assertTrue(type.zoomable)
    }
}
