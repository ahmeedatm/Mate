package app.mate.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyJsonTest {
    private fun step(text: String) = JSONObject().put("type", "text").put("text", text)

    @Test
    fun `un cycle de plusieurs etapes envoie cycle true`() {
        val key = keyJson("Theme", listOf(step("Jour"), step("Nuit")), glyph = null, color = null, cycle = true)
        assertTrue(key.getBoolean("cycle"))
        assertEquals(2, key.getJSONArray("steps").length())
    }

    @Test
    fun `une seule etape ne fait pas de cycle`() {
        assertFalse(keyJson("Un", listOf(step("Jour")), glyph = null, color = null, cycle = true).has("cycle"))
    }

    @Test
    fun `sans cycle demande rien n'est envoye`() {
        assertFalse(keyJson("Macro", listOf(step("a"), step("b")), glyph = null, color = null, cycle = false).has("cycle"))
    }

    @Test
    fun `glyphe et couleur ne sont envoyes que remplis`() {
        val key = keyJson("T", listOf(step("a")), glyph = "  ", color = "#112233", cycle = false)
        assertFalse(key.has("glyph"))
        assertEquals("#112233", key.getString("color"))
        assertEquals(JSONArray(listOf(step("a"))).toString(), key.getJSONArray("steps").toString())
    }

    @Test
    fun `la seconde action est envoyee seulement si elle a des etapes`() {
        val hold = listOf(step("Nuit"))
        val key = keyJson("T", listOf(step("Jour")), glyph = null, color = null, cycle = false, hold = hold)
        assertEquals(JSONArray(hold).toString(), key.getJSONArray("hold").toString())
        assertFalse(keyJson("T", listOf(step("Jour")), glyph = null, color = null, cycle = false, hold = emptyList()).has("hold"))
    }

    private fun pushStep() = JSONObject().put("type", "keys").put("keys", "f13").put("push", true)

    @Test
    fun `une touche a maintenir reste seule, sans cycle ni seconde action`() {
        val key = keyJson("Parler", listOf(step("a"), pushStep()), glyph = null, color = null, cycle = true, hold = listOf(step("Nuit")))
        assertEquals(1, key.getJSONArray("steps").length())
        assertTrue(key.getJSONArray("steps").getJSONObject(0).getBoolean("push"))
        assertFalse(key.has("cycle"))
        assertFalse(key.has("hold"))
    }
}
