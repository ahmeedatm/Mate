package com.unowhy.unomate.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WakeLogicTest {

    private fun result(vararg words: Pair<String, Double>): String {
        val list = words.joinToString(",") { (w, c) -> """{"conf": $c, "end": 1.2, "start": 0.9, "word": "$w"}""" }
        return """{"result": [$list], "text": "${words.joinToString(" ") { it.first }}"}"""
    }

    @Test
    fun `mate entendu avec assez de confiance réveille`() {
        assertTrue(WakeLogic.isWake(result("mate" to 0.92)))
        assertTrue(WakeLogic.isWake(result("hey" to 0.4, "mate" to 0.81)))
        assertTrue(WakeLogic.isWake(result("hé" to 0.9, "mate" to 0.7)))
    }

    @Test
    fun `confiance trop basse ne réveille pas`() {
        assertFalse(WakeLogic.isWake(result("mate" to 0.69)))
        assertFalse(WakeLogic.isWake(result("mate" to 0.2)))
    }

    @Test
    fun `unk seul ou autre mot ne réveille pas`() {
        assertFalse(WakeLogic.isWake("""{"text": ""}"""))
        assertFalse(WakeLogic.isWake(result("[unk]" to 1.0)))
        assertFalse(WakeLogic.isWake(result("hey" to 1.0)))
    }

    @Test
    fun `résultat partiel ignoré`() {
        assertFalse(WakeLogic.isWake("""{"partial": "mate"}"""))
        assertFalse(WakeLogic.isWake("""{"partial": "hey mate", "partial_result": [{"conf": 1.0, "word": "mate"}]}"""))
    }

    @Test
    fun `json illisible ne réveille pas`() {
        assertFalse(WakeLogic.isWake(""))
        assertFalse(WakeLogic.isWake("mate"))
        assertFalse(WakeLogic.isWake("""{"result": "mate"}"""))
        assertFalse(WakeLogic.isWake("""{"result": [{"word": "mate"}]}"""))
        assertFalse(WakeLogic.isWake("""{"result": [{"word": "mate", "conf": 0.9}"""))
    }

    @Test
    fun `entrées du zip gardées dans le dossier`() {
        val root = Files.createTempDirectory("wake").toFile()
        try {
            assertEquals(File(root, "vosk-model-small-fr-0.22/am/final.mdl").canonicalFile, WakeLogic.zipTarget(root, "vosk-model-small-fr-0.22/am/final.mdl"))
            assertNotNull(WakeLogic.zipTarget(root, "vosk-model-small-fr-0.22/"))
            assertNull(WakeLogic.zipTarget(root, "../evil.so"))
            assertNull(WakeLogic.zipTarget(root, "vosk-model-small-fr-0.22/../../evil.so"))
            assertNull(WakeLogic.zipTarget(root, "/etc/passwd"))
            assertNull(WakeLogic.zipTarget(root, "\\Windows\\evil.dll"))
            assertNull(WakeLogic.zipTarget(root, ""))
            assertNull(WakeLogic.zipTarget(root, "."))
            // Dossier voisin dont le nom commence pareil : refusé aussi.
            assertNull(WakeLogic.zipTarget(root, "../${root.name}-bis/x"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `micro libre seulement quand personne ne le tient`() {
        val gate = MicGate()
        var changes = 0
        gate.onChange = { changes++ }
        assertTrue(gate.free)
        gate.hold(Mic.VOICE)
        assertFalse(gate.free)
        assertEquals(1, changes)
        gate.hold(Mic.CAMERA)
        assertEquals(1, changes)
        gate.release(Mic.VOICE)
        assertFalse(gate.free)
        assertEquals(1, changes)
        gate.release(Mic.CAMERA)
        assertTrue(gate.free)
        assertEquals(2, changes)
    }

    @Test
    fun `un nom pris deux fois se rend une fois, rendre un nom libre ne fait rien`() {
        val gate = MicGate()
        var changes = 0
        gate.onChange = { changes++ }
        gate.release(Mic.CALL)
        assertEquals(0, changes)
        gate.hold(Mic.CALL)
        gate.hold(Mic.CALL)
        gate.release(Mic.CALL)
        assertTrue(gate.free)
        assertEquals(2, changes)
    }

    @Test
    fun `réveil puis commande vocale gardent le micro jusqu'à la fin de la commande`() {
        val gate = MicGate()
        gate.hold(Mic.WAKE)
        gate.hold(Mic.VOICE)
        gate.release(Mic.WAKE)
        assertFalse(gate.free)
        assertTrue(gate.holds(Mic.VOICE))
        gate.release(Mic.VOICE)
        assertTrue(gate.free)
    }

    @Test
    fun `set suit l'état et clear libère tout`() {
        val gate = MicGate()
        var changes = 0
        gate.onChange = { changes++ }
        gate.set(Mic.CAMERA, true)
        gate.set(Mic.CAMERA, true)
        gate.hold(Mic.CLIPBOARD)
        assertEquals(1, changes)
        gate.set(Mic.CAMERA, false)
        assertFalse(gate.free)
        gate.clear()
        assertTrue(gate.free)
        assertEquals(2, changes)
        gate.clear()
        assertEquals(2, changes)
    }
}
