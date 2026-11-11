package app.mate.device

import org.junit.Assert.assertEquals
import org.junit.Test

class TalkBackRouteTest {

    @Test
    fun `TalkBack absent, rien a regler`() {
        assertEquals(TalkBackRoute.MISSING, TalkBackRoute.of(installed = false, canWrite = true, kiosk = true))
        assertEquals(TalkBackRoute.MISSING, TalkBackRoute.of(installed = false, canWrite = false, kiosk = false))
    }

    @Test
    fun `permission donnee a l'installation, l'interrupteur agit directement`() {
        assertEquals(TalkBackRoute.DIRECT, TalkBackRoute.of(installed = true, canWrite = true, kiosk = true))
        assertEquals(TalkBackRoute.DIRECT, TalkBackRoute.of(installed = true, canWrite = true, kiosk = false))
    }

    @Test
    fun `hors kiosque sans permission, Parametres d'Android est ouvert`() {
        assertEquals(TalkBackRoute.SYSTEM_SETTINGS, TalkBackRoute.of(installed = true, canWrite = false, kiosk = false))
    }

    @Test
    fun `en kiosque sans permission, Parametres est inaccessible, l'interrupteur reste bloque`() {
        assertEquals(TalkBackRoute.LOCKED, TalkBackRoute.of(installed = true, canWrite = false, kiosk = true))
    }
}
