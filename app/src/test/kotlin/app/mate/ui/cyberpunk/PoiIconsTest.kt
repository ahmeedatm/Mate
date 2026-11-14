package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkPhoneLook
import app.mate.net.CyberpunkPhoneStatus
import app.mate.net.CyberpunkPoi
import app.mate.net.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class PoiIconsTest {
    private fun poi(icon: String?) = CyberpunkPoi("id-$icon", 0.0, 0.0, PoiCategory.SHOP, icon, null, false)

    @Test fun `nom de fichier d'une icone`() {
        assertEquals("poi_ripperdoc.png", PoiIcons.fileName("ripperdoc"))
    }

    @Test fun `icones voulues, celles des points que l'ordinateur sert`() {
        val available = listOf("poi_ripperdoc.png", "poi_shop.png", "icon_message.png")
        val wanted = PoiIcons.wanted(listOf(poi("ripperdoc"), poi("ripperdoc"), poi("bar"), poi(null)), available)
        assertEquals(setOf("ripperdoc"), wanted)
    }

    @Test fun `etat des icones`() {
        fun look(status: CyberpunkPhoneStatus, vararg assets: String) = CyberpunkPhoneLook(status, assets.toList())
        assertEquals(PoiIconsState.UNKNOWN, PoiIcons.state(null))
        assertEquals(PoiIconsState.LOADING, PoiIcons.state(look(CyberpunkPhoneStatus.LOADING)))
        assertEquals(PoiIconsState.READY, PoiIcons.state(look(CyberpunkPhoneStatus.READY, "poi_shop.png")))
        assertEquals(PoiIconsState.MISSING, PoiIcons.state(look(CyberpunkPhoneStatus.READY, "icon_message.png")))
        assertEquals(PoiIconsState.MISSING, PoiIcons.state(look(CyberpunkPhoneStatus.ABSENT)))
        assertEquals(PoiIconsState.MISSING, PoiIcons.state(look(CyberpunkPhoneStatus.ERROR)))
    }
}
