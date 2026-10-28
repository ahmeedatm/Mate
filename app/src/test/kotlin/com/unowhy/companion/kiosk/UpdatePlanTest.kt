package com.unowhy.companion.kiosk

import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.UpdateSource
import com.unowhy.companion.net.UpdatesApi
import com.unowhy.companion.net.parseUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePlanTest {

    private val companion = "com.unowhy.companion"
    private val webview = "com.google.android.webview"

    private fun update(pkg: String, code: Long, source: UpdateSource) =
        AppUpdate(pkg, pkg, code, "v$code", "$pkg-$code.apk", 10, "ab", source)

    @Test
    fun `merge garde la plus haute versionCode de chaque paquet`() {
        val computer = listOf(update(companion, 10200, UpdateSource.COMPUTER), update(webview, 81, UpdateSource.COMPUTER))
        val server = listOf(update(companion, 10300, UpdateSource.SERVER))
        assertEquals(
            listOf(update(companion, 10300, UpdateSource.SERVER), update(webview, 81, UpdateSource.COMPUTER)),
            UpdatePlan.merge(computer, server),
        )
    }

    @Test
    fun `merge a egalite garde la premiere source`() {
        val merged = UpdatePlan.merge(listOf(update(companion, 10200, UpdateSource.COMPUTER)), listOf(update(companion, 10200, UpdateSource.SERVER)))
        assertEquals(listOf(update(companion, 10200, UpdateSource.COMPUTER)), merged)
    }

    @Test
    fun `merge avec une source vide`() {
        val server = listOf(update(companion, 10300, UpdateSource.SERVER))
        assertEquals(server, UpdatePlan.merge(emptyList(), server))
    }

    @Test
    fun `auto ne prend que le Companion plus recent`() {
        val updates = listOf(update(webview, 120, UpdateSource.COMPUTER), update(companion, 10300, UpdateSource.SERVER))
        assertEquals(update(companion, 10300, UpdateSource.SERVER), UpdatePlan.auto(updates, companion, 10200))
        assertNull(UpdatePlan.auto(updates, companion, 10300))
        assertNull(UpdatePlan.auto(updates, companion, 10400))
        assertNull(UpdatePlan.auto(listOf(update(webview, 120, UpdateSource.COMPUTER)), companion, 0))
    }

    private fun trial(code: Long, n: Int, source: UpdateSource = UpdateSource.COMPUTER) =
        AppUpdate(companion, "Companion", code, "0.2.2-essai.$n", "companion-0.2.2-essai.$n.apk", 10, "ab", source)

    @Test
    fun `isTrial reconnait une version d'essai`() {
        assertTrue(UpdatePlan.isTrial(trial(202_005, 4)))
        assertFalse(UpdatePlan.isTrial(update(companion, 202_000, UpdateSource.SERVER)))
        assertFalse(UpdatePlan.isTrial(AppUpdate(companion, "Companion", 202_000, "0.2.2", "c.apk", 10, "ab")))
    }

    @Test
    fun `merge garde l'essai a cote de la release plus recente`() {
        // Release 0.2.2 taguée pendant qu'un essai 0.2.1-essai.N attend sur l'ordinateur : les deux restent, main d'abord.
        val oldTrial = AppUpdate(companion, "Companion", 201_188, "0.2.1-essai.187", "e.apk", 10, "ab")
        val release = update(companion, 202_000, UpdateSource.SERVER)
        val web = update(webview, 81, UpdateSource.COMPUTER)
        assertEquals(listOf(release, oldTrial, web), UpdatePlan.merge(listOf(oldTrial, web), listOf(release)))
    }

    @Test
    fun `merge garde le plus haut essai`() {
        val merged = UpdatePlan.merge(listOf(trial(202_005, 4)), listOf(trial(202_010, 9, UpdateSource.SERVER)))
        assertEquals(listOf(trial(202_010, 9, UpdateSource.SERVER)), merged)
    }

    @Test
    fun `auto n'installe jamais un essai`() {
        assertNull(UpdatePlan.auto(listOf(trial(202_005, 4)), companion, 202_000))
        val release = update(companion, 203_000, UpdateSource.SERVER)
        assertEquals(release, UpdatePlan.auto(listOf(trial(203_500, 4), release), companion, 202_000))
    }

    @Test
    fun `state compare a la version installee`() {
        val u = update(companion, 202_000, UpdateSource.SERVER)
        assertEquals(UpdatePlan.State.NEWER, UpdatePlan.state(u, null))
        assertEquals(UpdatePlan.State.NEWER, UpdatePlan.state(u, 201_000))
        assertEquals(UpdatePlan.State.INSTALLED, UpdatePlan.state(u, 202_000))
        // Essai installé plus haut que la release : Android refuserait la release.
        assertEquals(UpdatePlan.State.OLDER, UpdatePlan.state(u, 202_005))
    }

    @Test
    fun `isDue au demarrage puis toutes les 6 h`() {
        val h = 3_600_000L
        assertTrue(UpdatePlan.isDue(null, 0))
        assertFalse(UpdatePlan.isDue(1_000 * h, 1_000 * h + 6 * h - 1))
        assertTrue(UpdatePlan.isDue(1_000 * h, 1_000 * h + 6 * h))
        // Horloge remise en arrière (réglage de l'heure) : on revérifie.
        assertTrue(UpdatePlan.isDue(1_000 * h, 999 * h))
    }

    @Test
    fun `reachedAnySource faux quand toutes les sources echouent`() {
        // Démarrage hors ligne : la seule source tentée (serveur central) est en erreur réseau.
        assertFalse(UpdatePlan.reachedAnySource(attempted = 1, errored = 1))
        // Ordinateur + serveur, les deux injoignables.
        assertFalse(UpdatePlan.reachedAnySource(attempted = 2, errored = 2))
    }

    @Test
    fun `reachedAnySource vrai des qu'une source repond`() {
        // Une source a répondu (même « rien à installer », même 401/404 hors erreurs).
        assertTrue(UpdatePlan.reachedAnySource(attempted = 1, errored = 0))
        // Ordinateur injoignable mais serveur central joignable.
        assertTrue(UpdatePlan.reachedAnySource(attempted = 2, errored = 1))
        // Aucune source configurée (build debug sans URL ni ordinateur) : rien à réessayer.
        assertTrue(UpdatePlan.reachedAnySource(attempted = 0, errored = 0))
    }

    @Test
    fun `parseUpdates lit le manifest du serveur central`() {
        val json = """{"updates":[{"package":"com.unowhy.companion","label":"Companion","versionCode":10300,"versionName":"1.3.0",
            "file":"companion-1.3.0.apk","size":4200000,"sha256":"0f"}]}"""
        assertEquals(
            listOf(AppUpdate(companion, "Companion", 10300, "1.3.0", "companion-1.3.0.apk", 4_200_000, "0f", UpdateSource.SERVER)),
            parseUpdates(json, UpdateSource.SERVER),
        )
    }

    @Test
    fun `parseHeader lit Nom deux-points valeur`() {
        assertEquals(mapOf("Deploy-Token" to "abc:def"), UpdatesApi.parseHeader("Deploy-Token: abc:def"))
        assertEquals(emptyMap<String, String>(), UpdatesApi.parseHeader(""))
        assertEquals(emptyMap<String, String>(), UpdatesApi.parseHeader("sans-separateur"))
    }

    @Test
    fun `version 0_0_1 plus recente que rien`() {
        // Premier tag v0.0.1 : versionCode 1.
        assertEquals(update(companion, 1, UpdateSource.SERVER), UpdatePlan.auto(listOf(update(companion, 1, UpdateSource.SERVER)), companion, 0))
    }
}
