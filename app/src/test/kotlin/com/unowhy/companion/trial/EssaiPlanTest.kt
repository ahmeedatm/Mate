package com.unowhy.companion.trial

import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.BackendArchive
import com.unowhy.companion.net.BackendState
import com.unowhy.companion.net.Essai
import com.unowhy.companion.net.UpdateSource
import com.unowhy.companion.net.UpdatesApi
import com.unowhy.companion.net.parseEssais
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EssaiPlanTest {

    @Test
    fun `install une MR sans essai installe, au meme code ne fait rien, plus haut remplace`() {
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_100, null))
        assertEquals(EssaiPlan.Install.NONE, EssaiPlan.install(5_100, 5_100))
        assertEquals(EssaiPlan.Install.INSTALL, EssaiPlan.install(5_101, 5_100))
    }

    @Test
    fun `install une MR au code plus bas desinstalle d'abord`() {
        // Android refuserait de descendre : pipeline plus ancien que celui de l'essai installé.
        assertEquals(EssaiPlan.Install.UNINSTALL_FIRST, EssaiPlan.install(5_099, 5_100))
    }

    private val origin = BackendState(packaged = true, mode = "origin", mr = null, sha256 = null)
    private fun onEssai(mr: Int, sha256: String? = "aa") = BackendState(packaged = true, mode = "essai", mr = mr, sha256 = sha256)

    @Test
    fun `backend d'une MR qui le touche est envoye sauf si cette archive est deja posee`() {
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "aa", origin))
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "aa", onEssai(110)))
        assertEquals(EssaiPlan.Backend.NOTHING, EssaiPlan.backend(true, 109, "AA", onEssai(109)))
    }

    @Test
    fun `MR republiee avec un autre backend le renvoie`() {
        // Nouveau pipeline de la même MR : l'ordinateur garde l'archive du précédent tant qu'on ne renvoie pas.
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "bb", onEssai(109, "aa")))
        // Ordinateur d'avant ce correctif : pas d'empreinte connue, on renvoie.
        assertEquals(EssaiPlan.Backend.SEND, EssaiPlan.backend(true, 109, "aa", onEssai(109, null)))
    }

    @Test
    fun `MR sans backend restaure l'origine seulement si l'ordinateur est sur un essai`() {
        assertEquals(EssaiPlan.Backend.RESTORE, EssaiPlan.backend(false, 109, null, onEssai(110)))
        assertEquals(EssaiPlan.Backend.NOTHING, EssaiPlan.backend(false, 109, null, origin))
    }

    @Test
    fun `parseEssais lit le manifest, la MR la plus recente d'abord, backend par systeme`() {
        val json = """{"essais":[
            {"mr":98,"title":"Ouvertes plus rapide","package":"com.unowhy.companion.essai","label":"Companion essai","versionCode":2188,
             "versionName":"0.0.2-essai.187","file":"companion-0.0.2-essai.187.apk","size":4200000,"sha256":"0f"},
            {"mr":109,"title":"Essais par MR","package":"com.unowhy.companion.essai","label":"Companion essai","versionCode":202014,
             "versionName":"0.2.2-essai.14","file":"companion-0.2.2-essai.14.apk","size":4300000,"sha256":"1a",
             "backend":{"mac":{"file":"backend-mr109-mac.tar.gz","size":900,"sha256":"2b"},
                        "windows":{"file":"backend-mr109-windows.tar.gz","size":950,"sha256":"3c"}}}]}"""
        val essais = parseEssais(json)
        assertEquals(listOf(109, 98), essais.map { it.mr })
        val mr109 = essais[0]
        assertEquals("Essais par MR", mr109.title)
        assertEquals(AppUpdate("com.unowhy.companion.essai", "Companion essai", 202014, "0.2.2-essai.14", "companion-0.2.2-essai.14.apk", 4_300_000, "1a", UpdateSource.TRIALS), mr109.update)
        assertTrue(mr109.hasBackend)
        assertEquals(BackendArchive("backend-mr109-windows.tar.gz", 950, "3c"), mr109.backends["windows"])
        assertFalse(essais[1].hasBackend)
    }

    @Test
    fun `essaisUrl remplace latest par essais`() {
        assertEquals("https://g/api/v4/projects/1/packages/generic/companion-updates/essais", UpdatesApi.essaisUrl("https://g/api/v4/projects/1/packages/generic/companion-updates/latest"))
        assertEquals("http://10.0.2.2:8099/essais", UpdatesApi.essaisUrl("http://10.0.2.2:8099/"))
        assertEquals("", UpdatesApi.essaisUrl(""))
    }

    @Test
    fun `essai sans backend n'a pas de backend`() {
        val update = AppUpdate("p", "l", 1, "v", "f", 1, "s", UpdateSource.TRIALS)
        assertFalse(Essai(1, "t", update, emptyMap()).hasBackend)
    }
}
