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
