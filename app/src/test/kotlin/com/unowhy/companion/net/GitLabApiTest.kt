package com.unowhy.companion.net

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
}
