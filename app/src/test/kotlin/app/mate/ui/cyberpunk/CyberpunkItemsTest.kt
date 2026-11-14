package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CyberpunkItemsTest {
    private fun item(name: String, type: String, usable: Boolean = false, quality: String? = null) =
        CyberpunkItem("tdb.$name", name, type, quality, 1, usable)

    @Test
    fun groupsByCategoryHealFirst() {
        val groups = CyberpunkItems.grouped(
            listOf(item("Munitions", "Con_Ammo"), item("Veste", "Clo_Outer"), item("MaxDoc", "Con_Injector", usable = true)),
        )
        assertEquals(listOf(CyberpunkItems.Category.HEAL, CyberpunkItems.Category.AMMO, CyberpunkItems.Category.CLOTHES), groups.map { it.first })
    }

    @Test
    fun unknownTypeFallsInOther() {
        assertEquals(CyberpunkItems.Category.OTHER, CyberpunkItems.category("???"))
        assertEquals(CyberpunkItems.Category.OTHER, CyberpunkItems.category(null))
    }

    @Test
    fun healItemIsTheFirstUsableHealer() {
        val items = listOf(item("Pomme", "Con_Edible"), item("Clé", "Gen_Misc", usable = true), item("MaxDoc", "Con_Injector", usable = true), item("Bounce", "Con_Inhaler", usable = true))
        assertEquals("MaxDoc", CyberpunkItems.healItem(items)?.name)
        assertNull(CyberpunkItems.healItem(listOf(item("Pomme", "Con_Edible"))))
    }

    @Test
    fun rarityIgnoresCaseAndKnowsIconic() {
        assertEquals(CyberpunkItems.Rarity.EPIC, CyberpunkItems.rarity("epic"))
        assertEquals(CyberpunkItems.Rarity.LEGENDARY, CyberpunkItems.rarity("Iconic"))
        assertEquals(CyberpunkItems.Rarity.COMMON, CyberpunkItems.rarity(null))
    }

    @Test
    fun zonesAreOrderedAndNamedLoosely() {
        assertEquals(CyberpunkItems.Zone.EYES, CyberpunkItems.zone("EyesCW"))
        assertEquals(CyberpunkItems.Zone.OS, CyberpunkItems.zone("OperatingSystemCW"))
        // Noms réels lus en jeu par le spike du 2026-11-14.
        assertEquals(CyberpunkItems.Zone.OS, CyberpunkItems.zone("SystemReplacementCW"))
        assertEquals(CyberpunkItems.Zone.SKELETON, CyberpunkItems.zone("MusculoskeletalSystemCW"))
        assertEquals(CyberpunkItems.Zone.SKIN, CyberpunkItems.zone("IntegumentarySystemCW"))
        assertEquals(CyberpunkItems.Zone.CARDIO, CyberpunkItems.zone("CardiovascularSystemCW"))
        assertNull(CyberpunkItems.zone("zzz"))
    }
}
