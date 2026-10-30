package com.unowhy.unomate.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshStartTest {

    @Test
    fun `seuls les reglages du technicien restent dans les preferences`() {
        val values = mapOf(
            "allow_installs" to true,
            "exit_lock" to true,
            "power_menu" to false,
            "theme" to "SOMBRE",
            "computers" to "[...]",
            "home_layout_cadran" to "{}",
        )
        assertEquals(mapOf("allow_installs" to true, "exit_lock" to true, "power_menu" to false), FreshStart.kept("companion", values))
        // Autre fichier (eau…) : rien ne reste, même une clé du même nom.
        assertEquals(emptyMap<String, Any?>(), FreshStart.kept("water", mapOf("exit_lock" to true, "glasses" to 3)))
    }

    @Test
    fun `installeurs et modele du mot de reveil gardes, photos du cadre effacees`() {
        assertEquals(
            listOf("frame", "notes.json"),
            FreshStart.filesToWipe(listOf("installers", "frame", "vosk-model-small-fr-0.22", "notes.json")),
        )
    }
}
