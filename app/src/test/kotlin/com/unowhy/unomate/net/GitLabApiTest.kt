package com.unowhy.unomate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLabApiTest {

    private val full = """{"mine":[
        {"project":"softwares/products/uem","iid":42,"title":"Corrige le jeton","draft":false,"author":"Ahmed Atmane","pipeline":"success",
         "approvals":{"given":1,"required":2,"by":["Eliot","Sam"]},"status":"mergeable","statusLabel":"Prête à fusionner","mergeable":true,
         "sha":"0123456789abcdef0123456789abcdef01234567","updatedAt":"2026-10-29T10:00:00Z"}],
        "review":[
        {"project":"a/b","iid":7,"title":"Brouillon","pipeline":"inconnu","approvals":{"given":0,"required":0,"by":[]},
         "statusLabel":"Brouillon","mergeable":false,"sha":"","author":"Sam"}]}"""

    @Test
    fun `une MR garde tous ses champs`() {
        val mr = parseGitLabMrs(full).mine.single()
        assertEquals(
            GitLabMr(
                "softwares/products/uem", 42, "Corrige le jeton", false, "Ahmed Atmane", Pipeline.SUCCESS,
                1, 2, listOf("Eliot", "Sam"), "Prête à fusionner", true, "0123456789abcdef0123456789abcdef01234567",
            ),
            mr,
        )
    }

    @Test
    fun `brouillon absent vaut faux, pipeline inconnu vaut none, personne n'a approuve`() {
        val mr = parseGitLabMrs(full).review.single()
        assertFalse(mr.draft)
        assertEquals(Pipeline.NONE, mr.pipeline)
        assertTrue(mr.approvedBy.isEmpty())
        assertFalse(mr.mergeable)
    }

    @Test
    fun `listes vides`() {
        val mrs = parseGitLabMrs("""{"mine":[],"review":[]}""")
        assertTrue(mrs.mine.isEmpty())
        assertTrue(mrs.review.isEmpty())
    }

    @Test
    fun `connected absent vaut vrai (ancien compagnon)`() {
        val mrs = parseGitLabMrs(full)
        assertTrue(mrs.connected)
        assertFalse(mrs.expired)
    }

    @Test
    fun `pas connecte, listes vides, connected faux et expired lu`() {
        val none = parseGitLabMrs("""{"connected":false,"expired":false,"mine":[],"review":[]}""")
        assertFalse(none.connected)
        assertFalse(none.expired)
        assertTrue(none.mine.isEmpty() && none.review.isEmpty())
        val expired = parseGitLabMrs("""{"connected":false,"expired":true,"mine":[],"review":[]}""")
        assertFalse(expired.connected)
        assertTrue(expired.expired)
        assertTrue(parseGitLabMrs("""{"connected":true,"mine":[],"review":[]}""").connected)
    }

    @Test
    fun `canMerge lu, absent vaut vrai (ancien compagnon)`() {
        assertTrue(parseGitLabMrs(full).canMerge)
        assertTrue(parseGitLabMrs("""{"connected":true,"canMerge":true,"mine":[],"review":[]}""").canMerge)
        assertFalse(parseGitLabMrs("""{"connected":true,"canMerge":false,"mine":[],"review":[]}""").canMerge)
        assertFalse(parseGitLabMrs("""{"connected":false,"expired":false,"canMerge":false,"mine":[],"review":[]}""").canMerge)
    }

    @Test
    fun `chaque pipeline a sa cle, une cle inconnue ou absente vaut none`() {
        for (p in Pipeline.entries) assertEquals(p, Pipeline.of(p.key))
        assertEquals(Pipeline.NONE, Pipeline.of(null))
        assertEquals(Pipeline.NONE, Pipeline.of("manual"))
    }

    @Test
    fun `gitlab absent de features vaut faux, present vaut vrai`() {
        assertFalse(parseHomeFeatures("""{"agenda":true,"meetings":true,"linked":false,"teams":false,"agents":true}""").gitlab)
        assertTrue(parseHomeFeatures("""{"gitlab":true}""").gitlab)
    }

    @Test
    fun `les pipelines gardent leurs champs, une duree nulle reste nulle`() {
        val json = """{"connected":true,"pipelines":[
            {"project":"equipe-demo/facturation","ref":"main","id":8841,"pipeline":"running","durationSec":null,"ageSec":240,"failedJobs":[]},
            {"project":"equipe-demo/facturation","ref":"fix/x","id":8839,"pipeline":"failed","durationSec":148,"ageSec":1320,"failedJobs":["tests-unitaires","tests-e2e"]},
            {"project":"a/b","ref":"main","id":1,"pipeline":"inconnu","durationSec":0,"ageSec":0}]}"""
        val answer = parseGitLabPipelines(json)
        assertTrue(answer.connected)
        val (running, failed, unknown) = answer.pipelines
        assertEquals(GitLabPipeline("equipe-demo/facturation", "main", 8841L, Pipeline.RUNNING, null, 240, emptyList()), running)
        assertEquals(GitLabPipeline("equipe-demo/facturation", "fix/x", 8839, Pipeline.FAILED, 148, 1320, listOf("tests-unitaires", "tests-e2e")), failed)
        assertEquals(Pipeline.NONE, unknown.status)
        assertEquals(0, unknown.durationSec)
        assertTrue(unknown.failedJobs.isEmpty())
    }

    @Test
    fun `pas connecte, liste vide et expired lu`() {
        val answer = parseGitLabPipelines("""{"connected":false,"expired":true,"pipelines":[]}""")
        assertFalse(answer.connected)
        assertTrue(answer.expired)
        assertTrue(answer.pipelines.isEmpty())
        // Ancien compagnon : sans « connected », la réponse est lue comme connectée.
        assertTrue(parseGitLabPipelines("""{"pipelines":[]}""").connected)
    }
}
