package app.mate.kiosk

import app.mate.net.AppUpdate
import app.mate.net.UpdateSource
import app.mate.net.UpdatesApi
import app.mate.net.parseUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePlanTest {

    private val companion = "app.mate"
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
    fun `auto ne prend que le Mate plus recent`() {
        val updates = listOf(update(webview, 120, UpdateSource.COMPUTER), update(companion, 10300, UpdateSource.SERVER))
        assertEquals(update(companion, 10300, UpdateSource.SERVER), UpdatePlan.auto(updates, companion, 10200))
        assertNull(UpdatePlan.auto(updates, companion, 10300))
        assertNull(UpdatePlan.auto(updates, companion, 10400))
        assertNull(UpdatePlan.auto(listOf(update(webview, 120, UpdateSource.COMPUTER)), companion, 0))
    }

    @Test
    fun `auto ignore un autre paquet, meme plus recent`() {
        val trial = AppUpdate("app.mate.essai", "Mate essai", 999_999, "0.2.2-essai.4", "e.apk", 10, "ab")
        assertNull(UpdatePlan.auto(listOf(trial), companion, 202_000))
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
        val json = """{"updates":[{"package":"app.mate","label":"Mate","versionCode":10300,"versionName":"1.3.0",
            "file":"companion-1.3.0.apk","size":4200000,"sha256":"0f"}]}"""
        assertEquals(
            listOf(AppUpdate(companion, "Mate", 10300, "1.3.0", "companion-1.3.0.apk", 4_200_000, "0f", UpdateSource.SERVER)),
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
