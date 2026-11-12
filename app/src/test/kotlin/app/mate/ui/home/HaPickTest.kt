package app.mate.ui.home

import app.mate.R
import app.mate.net.HaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaPickTest {

    private val scene = HaEntity("scene.film", "Soirée film", isScene = true, on = false, available = true)
    private val salon = HaEntity("light.salon", "Salon", isScene = false, on = true, available = true)
    private val prise = HaEntity("switch.cafe", "Cafetière", isScene = false, on = false, available = true)
    private val tele = HaEntity("media_player.tv", "Télé", isScene = false, on = false, available = true)

    @Test
    fun `la pastille montre l'entite choisie`() {
        assertEquals(prise, haPick(listOf(scene, salon, prise), "switch.cafe"))
    }

    @Test
    fun `sans choix, ou choix disparu, la premiere lumiere ou prise`() {
        assertEquals(salon, haPick(listOf(scene, salon, prise), null))
        assertEquals(salon, haPick(listOf(scene, salon, prise), "light.disparue"))
    }

    @Test
    fun `que des scenes, la premiere, et rien sans entite`() {
        assertEquals(scene, haPick(listOf(scene), null))
        assertNull(haPick(emptyList(), null))
    }

    @Test
    fun `l'icone vient du type de l'entite`() {
        assertEquals(R.drawable.ic_lightbulb, haIcon(salon))
        assertEquals(R.drawable.ic_bolt, haIcon(prise))
        assertEquals(R.drawable.ic_palette, haIcon(scene))
        assertEquals(R.drawable.ic_tv, haIcon(tele))
    }
}
