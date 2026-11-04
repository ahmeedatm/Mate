package app.mate.ui.mixer

import app.mate.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** Applis masquées : gardées sur la tablette par ordinateur, communes à toutes les tables. */
class MixerHiddenTest {
    private val context = FakeContext()
    private val firefox = MixerApps.App("firefox", "Firefox")

    @Test
    fun `masquer puis faire revenir, par ordinateur`() {
        assertEquals(emptyList<MixerApps.App>(), MixerHidden.read(context, "pc-1"))
        MixerHidden.hide(context, "pc-1", firefox)
        assertEquals(listOf(firefox), MixerHidden.read(context, "pc-1"))
        assertEquals(emptyList<MixerApps.App>(), MixerHidden.read(context, "pc-2"))
        MixerHidden.unhide(context, "pc-1", "firefox")
        assertEquals(emptyList<MixerApps.App>(), MixerHidden.read(context, "pc-1"))
    }

    @Test
    fun `une valeur abimee ne casse pas la table`() {
        context.getSharedPreferences("mixer", 0).edit().putString("hidden", "pas du json").apply()
        assertEquals(emptyList<MixerApps.App>(), MixerHidden.read(context, "pc-1"))
        MixerHidden.hide(context, "pc-1", firefox)
        assertEquals(listOf(firefox), MixerHidden.read(context, "pc-1"))
    }
}
