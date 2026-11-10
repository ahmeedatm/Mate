package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabTest {
    @Test
    fun `six onglets dans l'ordre de la rangée`() {
        assertEquals(
            listOf("APPARENCE", "TABLETTE", "NOTIFS", "VOIX", "ORDINATEURS", "SYSTEME"),
            SettingsTab.entries.map { it.name },
        )
    }

    @Test
    fun `Apparence s'ouvre d'abord, le technicien arrive sur Système`() {
        assertEquals(SettingsTab.APPARENCE, SettingsTab.DEFAULT)
        assertEquals(SettingsTab.SYSTEME, SettingsTab.TECHNICIAN)
    }

    @Test
    fun `onglet gardé relu, inconnu ou absent ramène au défaut`() {
        assertEquals(SettingsTab.VOIX, SettingsTab.fromSaved("VOIX"))
        assertEquals(SettingsTab.APPARENCE, SettingsTab.fromSaved("OPTIONS"))
        assertEquals(SettingsTab.APPARENCE, SettingsTab.fromSaved(null))
    }
}
