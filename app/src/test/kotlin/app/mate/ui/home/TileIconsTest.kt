package app.mate.ui.home

import app.mate.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TileIconsTest {

    /** Emoji des touches sans `symbol` d'une config du deck. */
    private fun glyphsOf(file: String): Set<String> {
        val found = mutableSetOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.optString("glyph").isNotEmpty() && value.optString("symbol").isEmpty()) found += value.getString("glyph")
                    value.keys().forEach { walk(value.get(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.get(i))
            }
        }
        walk(JSONObject(File("../backend/config/$file").readText()))
        return found
    }

    @Test
    fun `chaque emoji des configs du deck a son icone`() {
        for (file in listOf("deck.example.json", "deck.example.windows.json")) {
            val missing = glyphsOf(file).filter { TileIcons.forGlyph(it) == null }
            assertTrue("emoji sans icône dans $file : $missing (les ajouter à TileIcons.glyphs)", missing.isEmpty())
        }
    }

    @Test
    fun `le selecteur de variante ne change rien`() {
        assertEquals(TileIcons.forGlyph("↩"), TileIcons.forGlyph("↩️"))
        assertEquals(R.drawable.ic_tile_looks_one, TileIcons.forGlyph("1️⃣"))
    }

    @Test
    fun `le symbole passe avant l'emoji, un emoji inconnu reste`() {
        assertEquals(R.drawable.ic_mic_off, TileIcons.of("mic_off", "🎤"))
        assertEquals(R.drawable.ic_tile_save, TileIcons.of(null, "💾"))
        assertNull(TileIcons.of(null, "🦄"))
        assertNull(TileIcons.of(null, null))
    }
}
