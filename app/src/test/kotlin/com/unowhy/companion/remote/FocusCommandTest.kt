package com.unowhy.companion.remote

import com.unowhy.companion.focus.FocusTimer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ordre `focus` envoyé par l'ordinateur (extension MCP). */
class FocusCommandTest {

    @Before
    fun reset() {
        FocusTimer.setPreset(25, 5)
    }

    private fun focus(action: String, vararg extra: Pair<String, Int>) =
        focusCommand(JSONObject().put("action", action).apply { extra.forEach { put(it.first, it.second) } })

    @Test
    fun `start puis start ne remet pas en pause`() {
        assertTrue(focus("start").getBoolean("running"))
        assertTrue(focus("start").getBoolean("running"))
        assertFalse(focus("pause").getBoolean("running"))
        assertFalse(focus("pause").getBoolean("running"))
    }

    @Test
    fun `preset applique avant l'action`() {
        val state = focus("start", "focusMinutes" to 50)
        assertEquals(50, state.getInt("focusMinutes"))
        assertEquals(5, state.getInt("breakMinutes"))
        assertEquals("50:00", state.getString("remaining"))
        assertTrue(state.getBoolean("running"))
    }

    @Test
    fun `skip donne la phase pause`() {
        assertEquals("pause", focus("skip").getString("phase"))
        assertEquals("focus", focus("reset").getString("phase"))
    }

    @Test
    fun `action inconnue renvoie l'etat sans rien changer`() {
        val state = focus("danse")
        assertEquals("focus", state.getString("phase"))
        assertFalse(state.getBoolean("running"))
        assertEquals(0, state.getInt("completed"))
    }
}
