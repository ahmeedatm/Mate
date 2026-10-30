package com.unowhy.unomate.installers

import com.unowhy.unomate.installers.BackendPlan.Decision
import com.unowhy.unomate.net.BackendState
import com.unowhy.unomate.net.Identity
import com.unowhy.unomate.net.UpdateSource
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendPlanTest {

    private val sha = "a".repeat(64)
    private fun release(os: String, version: String, source: UpdateSource = UpdateSource.SERVER) =
        BackendRelease(os, "backend-$version-$os.tar.gz", 5_000_000, sha, version, source)

    private fun mac(version: String?, backendUpdate: Boolean = true) = Identity("id", "Mac", "mac", true, version = version, backendUpdate = backendUpdate)
    private val origin = BackendState(true, "origin", null, null)
    private val releases = listOf(release("mac", "1.3.0"), release("windows", "1.3.0"))

    @Test
    fun `parse lit les archives et ecarte les entrees suspectes`() {
        val json = """{"updates":[],"backends":[
            {"os":"mac","file":"backend-1.3.0-mac.tar.gz","size":5000000,"sha256":"$sha","version":"1.3.0"},
            {"os":"windows","file":"../x.tar.gz","size":5,"sha256":"$sha","version":"1.3.0"},
            {"os":"windows","file":"b.tar.gz","size":5,"sha256":"$sha","version":"dev"},
            {"os":"windows","file":"b.tar.gz","size":5,"sha256":"court","version":"1.3.0"}]}"""
        assertEquals(listOf(release("mac", "1.3.0", UpdateSource.COMPUTER)), BackendPlan.parse(json, UpdateSource.COMPUTER))
        assertEquals(emptyList<BackendRelease>(), BackendPlan.parse("""{"updates":[]}""", UpdateSource.SERVER))
    }

    @Test
    fun `latest garde la plus recente des deux sources pour le systeme`() {
        val local = release("mac", "1.4.0", UpdateSource.COMPUTER)
        assertEquals(local, BackendPlan.latest("mac", releases + local))
        assertEquals(release("windows", "1.3.0"), BackendPlan.latest("windows", releases + local))
    }

    @Test
    fun `propose la release plus recente`() {
        assertEquals(Decision.Available(release("mac", "1.3.0")), BackendPlan.decide(mac("1.2.0"), origin, releases, trial = false))
        // Version 1.3.0 contre 1.10.0 : comparaison par nombres, pas par texte.
        val big = release("mac", "1.10.0")
        assertEquals(Decision.Available(big), BackendPlan.decide(mac("1.3.0"), origin, listOf(big), trial = false))
    }

    @Test
    fun `a jour, ou ordinateur plus recent que la release`() {
        assertEquals(Decision.UpToDate, BackendPlan.decide(mac("1.3.0"), origin, releases, trial = false))
        assertEquals(Decision.ComputerNewer(release("mac", "1.3.0")), BackendPlan.decide(mac("1.4.0"), origin, releases, trial = false))
    }

    @Test
    fun `injoignable, lance depuis le depot, rien de publie`() {
        assertEquals(Decision.Unreachable, BackendPlan.decide(null, null, releases, trial = false))
        assertEquals(Decision.FromRepo, BackendPlan.decide(mac("dev", backendUpdate = false), BackendState(false, "origin", null, null), releases, trial = false))
        assertEquals(Decision.NothingPublished, BackendPlan.decide(mac("1.2.0"), origin, listOf(release("windows", "1.3.0")), trial = false))
    }

    @Test
    fun `compagnon d'avant la feature, avec ou sans version, recoit l'installeur`() {
        assertEquals(Decision.NeedsInstaller(release("mac", "1.3.0")), BackendPlan.decide(mac("1.2.0", backendUpdate = false), null, releases, trial = false))
        assertEquals(Decision.NeedsInstaller(release("mac", "1.3.0")), BackendPlan.decide(mac(null, backendUpdate = false), null, releases, trial = false))
    }

    @Test
    fun `essai de MR pose, rien dans la stable, propose dans l'appli d'essai`() {
        val essai = BackendState(true, "essai", 120, sha)
        assertEquals(Decision.OnEssai, BackendPlan.decide(mac("0.0.0"), essai, releases, trial = false))
        assertEquals(Decision.Available(release("mac", "1.3.0")), BackendPlan.decide(mac("0.0.0"), essai, releases, trial = true))
    }
}
