package app.mate.trial

import app.mate.net.AppUpdate
import app.mate.net.BackendArchive
import app.mate.net.BackendState
import app.mate.net.Essai
import app.mate.net.UpdateSource
import app.mate.net.UpdatesApi
import app.mate.net.parseEssais
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EssaiPlanTest {

    @Test
    fun `install une PR sans essai installe, au meme code ne fait rien, plus haut remplace`() {
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_100, null, 109, 0))
        assertEquals(EssaiPlan.Install.NONE, EssaiPlan.install(5_100, 5_100, 109, 109))
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_101, 5_100, 109, 109))
    }

    @Test
    fun `install une autre PR n'est jamais installee, meme au meme code`() {
        // Après l'essai de la PR B, la ligne de A ne doit plus passer pour installée.
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_100, 5_100, 109, 110))
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_100, 5_100, 109, 0))
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_200, 5_100, 109, 110))
    }

    @Test
    fun `install une PR au code plus bas desinstalle d'abord`() {
        // Android refuserait de descendre : pipeline plus ancien que celui de l'essai installé.
        assertEquals(EssaiPlan.Install.UNINSTALL_FIRST, EssaiPlan.install(5_099, 5_100, 109, 110))
    }

    private val origin = BackendState(packaged = true, mode = "origin", mr = null, sha256 = null)
    private fun onEssai(mr: Int, sha256: String? = "aa") = BackendState(packaged = true, mode = "essai", mr = mr, sha256 = sha256)

    @Test
    fun `backend d'une PR qui le touche est envoye sauf si cette archive est deja posee`() {
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "aa", origin))
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "aa", onEssai(110)))
        assertEquals(EssaiPlan.Backend.NOTHING, EssaiPlan.backend(true, 109, "AA", onEssai(109)))
    }

    @Test
    fun `PR republiee avec un autre backend le renvoie`() {
        // Nouveau pipeline de la même PR : l'ordinateur garde l'archive du précédent tant qu'on ne renvoie pas.
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "bb", onEssai(109, "aa")))
        // Ordinateur d'avant ce correctif : pas d'empreinte connue, on renvoie.
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "aa", onEssai(109, null)))
    }

    @Test
    fun `PR sans backend restaure l'origine seulement si l'ordinateur est sur un essai`() {
        assertEquals(EssaiPlan.Backend.RESTORE, EssaiPlan.backend(false, 109, null, onEssai(110)))
        assertEquals(EssaiPlan.Backend.NOTHING, EssaiPlan.backend(false, 109, null, origin))
    }

    @Test
    fun `parseEssais lit le manifest, la PR la plus recente d'abord, backend par systeme`() {
        val json = """{"essais":[
            {"mr":98,"title":"Ouvertes plus rapide","package":"app.mate.essai","label":"Mate essai","versionCode":2188,
             "versionName":"0.0.2-essai.187","file":"companion-0.0.2-essai.187.apk","size":4200000,"sha256":"0f"},
            {"mr":109,"title":"Essais par PR","package":"app.mate.essai","label":"Mate essai","versionCode":202014,
             "versionName":"0.2.2-essai.14","file":"companion-0.2.2-essai.14.apk","size":4300000,"sha256":"1a",
             "backend":{"mac":{"file":"backend-mr109-mac.tar.gz","size":900,"sha256":"2b"},
                        "windows":{"file":"backend-mr109-windows.tar.gz","size":950,"sha256":"3c"}}}]}"""
        val essais = parseEssais(json)
        assertEquals(listOf(109, 98), essais.map { it.mr })
        val mr109 = essais[0]
        assertEquals("Essais par PR", mr109.title)
        assertEquals(AppUpdate("app.mate.essai", "Mate essai", 202014, "0.2.2-essai.14", "companion-0.2.2-essai.14.apk", 4_300_000, "1a", UpdateSource.TRIALS), mr109.update)
        assertTrue(mr109.hasBackend)
        assertEquals(BackendArchive("backend-mr109-windows.tar.gz", 950, "3c"), mr109.backends["windows"])
        assertFalse(essais[1].hasBackend)
    }

    @Test
    fun `source des mises a jour selon le build`() {
        assertNull(UpdatesApi.server("", "", "jeton"))
        assertNull(UpdatesApi.essais("", "  ", "jeton"))
        assertNotNull(UpdatesApi.server("", "ahmeedatm/Mate", ""))
        assertNotNull(UpdatesApi.essais("", "ahmeedatm/Mate", "jeton"))
        assertNotNull(UpdatesApi.server("http://10.0.2.2:8099/", "", ""))
    }

    @Test
    fun `essai sans backend n'a pas de backend`() {
        val update = AppUpdate("p", "l", 1, "v", "f", 1, "s", UpdateSource.TRIALS)
        assertFalse(Essai(1, "t", update, emptyMap()).hasBackend)
    }
}
