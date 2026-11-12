package app.mate.net

import app.mate.FakeContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HomeLightsTest {

    private fun e(id: String) = HaEntity(id, id, isScene = false, on = false, available = true)

    @Test
    fun `par defaut, les WLED en direct et les lumieres HA qui ne sont pas des WLED, jamais une prise`() {
        assertTrue(HomeChoice.defaultShown("wled:a1b2c3"))
        assertTrue(HomeChoice.defaultShown("light.salon"))
        assertFalse(HomeChoice.defaultShown("light.wled_bureau"))
        assertFalse(HomeChoice.defaultShown("switch.wled_bureau_sync_send"))
        assertFalse(HomeChoice.defaultShown("scene.film"))
        assertFalse(HomeChoice.defaultShown("media_player.tele"))
    }

    @Test
    fun `un choix coche ou decoche l'emporte sur le defaut, et reste`() {
        val context = FakeContext()
        HomeChoice.set(context, "switch.prise", true)
        HomeChoice.set(context, "light.salon", false)
        assertEquals(listOf("switch.prise", "wled:x"), HomeChoice.shown(context, listOf(e("switch.prise"), e("light.salon"), e("wled:x"), e("switch.wled_gel"))).map { it.id })
    }

    /** Source de test : ses entités, ou une panne ; garde les ordres reçus. */
    private class FakeSource(private val list: List<HaEntity>?) : LightSource {
        val calls = mutableListOf<String>()
        override suspend fun entities() = list ?: throw IOException("injoignable")
        override suspend fun toggle(id: String) {
            calls += "toggle $id"
        }
        override suspend fun setLight(id: String, brightness: Int?, rgb: Int?) {
            calls += "set $id $brightness $rgb"
        }
    }

    @Test
    fun `Home Assistant eteint, les WLED en direct repondent encore`() = runBlocking {
        val source = HomeLightSource(FakeContext(), FakeSource(null), mapOf("wled:bureau" to FakeSource(listOf(e("wled:bureau")))))
        assertEquals(listOf("wled:bureau"), source.entities().map { it.id })
    }

    @Test
    fun `tout est injoignable, l'erreur remonte au widget`() {
        val source = HomeLightSource(FakeContext(), FakeSource(null), mapOf("wled:bureau" to FakeSource(null)))
        val failed = runCatching { runBlocking { source.entities() } }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `chaque ordre part vers la source de l'entite`() = runBlocking {
        val ha = FakeSource(listOf(e("light.salon")))
        val wled = FakeSource(listOf(e("wled:bureau")))
        val source = HomeLightSource(FakeContext(), ha, mapOf("wled:bureau" to wled))
        source.toggle("light.salon")
        source.setLight("wled:bureau", 40, 0x0050FF)
        assertEquals(listOf("toggle light.salon"), ha.calls)
        assertEquals(listOf("set wled:bureau 40 20735"), wled.calls)
    }

    @Test
    fun `sans Home Assistant configure, seules les WLED comptent`() = runBlocking {
        val source = HomeLightSource(FakeContext(), null, mapOf("wled:bureau" to FakeSource(listOf(e("wled:bureau")))))
        assertEquals(listOf("wled:bureau"), source.entities().map { it.id })
    }
}
