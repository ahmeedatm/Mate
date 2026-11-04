package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLabApiTest {

    private val full = """{"mine":[
        {"project":"softwares/products/uem","iid":42,"title":"Corrige le jeton","draft":false,"author":"Ahmed Atmane","pipeline":"success",
         "approvals":{"given":1,"required":2,"by":["Eliot","Sam"]},"status":"mergeable","statusLabel":"Prête à fusionner","mergeable":true,
         "sha":"0123456789abcdef0123456789abcdef01234567","updatedAt":"2026-10-29T10:00:00Z","column":"merge",
         "stages":[{"name":"build","status":"success"},{"name":"test","status":"running"},{"name":"deploy","status":"inconnu"}]}],
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
                MrColumn.MERGE,
                listOf(
                    GitLabStage("build", Pipeline.SUCCESS),
                    GitLabStage("test", Pipeline.RUNNING),
                    GitLabStage("deploy", Pipeline.NONE),
                ),
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
    fun `colonne absente (ancien compagnon), brouillon sinon prete pour review, la liste a relire reste a review`() {
        val old = """{"mine":[
            {"project":"a/b","iid":1,"draft":true,"pipeline":"none"},
            {"project":"a/b","iid":2,"draft":false,"pipeline":"success"},
            {"project":"a/b","iid":3,"column":"inconnue"}],
            "review":[{"project":"a/b","iid":4}]}"""
        val mrs = parseGitLabMrs(old)
        assertEquals(listOf(MrColumn.DRAFT, MrColumn.READY, MrColumn.READY), mrs.mine.map { it.column })
        assertEquals(MrColumn.REVIEW, mrs.review.single().column)
        assertTrue(mrs.mine.all { it.stages.isEmpty() })
    }

    @Test
    fun `chaque colonne a sa cle`() {
        for (c in MrColumn.entries) assertEquals(c, MrColumn.of(c.key))
        assertEquals(null, MrColumn.of(null))
        assertEquals(null, MrColumn.of("autre"))
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
    fun `les pipelines gardent leurs champs, planifications et MR, une duree nulle reste nulle`() {
        val json = """{"connected":true,"schedules":[
            {"project":"equipe-demo/facturation","title":"Nightly","mrIid":null,"ref":"main","id":8841,"pipeline":"running","durationSec":null,
             "ageSec":240,"failedJobs":[],"stages":[{"name":"build","status":"success"},{"name":"test","status":"running"}]},
            {"project":"a/b","title":"","mrIid":null,"ref":"main","id":1,"pipeline":"inconnu","durationSec":0,"ageSec":0}],
            "mrs":[{"project":"equipe-demo/facturation","title":"Corriger la TVA","mrIid":409,"ref":"fix/x","id":8839,"pipeline":"failed",
             "durationSec":148,"ageSec":1320,"failedJobs":["tests-unitaires","tests-e2e"],"stages":[{"name":"test","status":"failed"}]}]}"""
        val answer = parseGitLabPipelines(json)
        assertTrue(answer.connected)
        val (running, unknown) = answer.schedules
        assertEquals(
            GitLabPipeline(
                "equipe-demo/facturation", "main", 8841, Pipeline.RUNNING, null, 240, emptyList(), "Nightly", null,
                listOf(GitLabStage("build", Pipeline.SUCCESS), GitLabStage("test", Pipeline.RUNNING)),
            ),
            running,
        )
        assertEquals(
            GitLabPipeline(
                "equipe-demo/facturation", "fix/x", 8839, Pipeline.FAILED, 148, 1320, listOf("tests-unitaires", "tests-e2e"),
                "Corriger la TVA", 409, listOf(GitLabStage("test", Pipeline.FAILED)),
            ),
            answer.mrs.single(),
        )
        assertEquals(Pipeline.NONE, unknown.status)
        assertEquals(0, unknown.durationSec)
        assertTrue(unknown.failedJobs.isEmpty() && unknown.stages.isEmpty())
    }

    @Test
    fun `la liste a plat des runs GitHub est lue`() {
        val answer = parseGitLabPipelines(
            """{"connected":true,"pipelines":[{"project":"org/app","ref":"main","id":7,"pipeline":"success","durationSec":30,"ageSec":60,"failedJobs":[]}]}""",
        )
        assertEquals(GitLabPipeline("org/app", "main", 7, Pipeline.SUCCESS, 30, 60, emptyList()), answer.pipelines.single())
        assertTrue(answer.schedules.isEmpty() && answer.mrs.isEmpty())
    }

    @Test
    fun `pas connecte, listes vides et expired lu`() {
        val answer = parseGitLabPipelines("""{"connected":false,"expired":true,"schedules":[],"mrs":[]}""")
        assertFalse(answer.connected)
        assertTrue(answer.expired)
        assertTrue(answer.schedules.isEmpty() && answer.mrs.isEmpty())
        // Ancien compagnon : sans « connected », la réponse est lue comme connectée.
        assertTrue(parseGitLabPipelines("""{"schedules":[],"mrs":[]}""").connected)
    }

    @Test
    fun `les planifications proposees et le choix en cours`() {
        val json = """{"connected":true,"projects":[
            {"project":"dev/robot-framework","schedules":[
                {"id":276,"description":"Sqool staging","ref":"master","active":true},
                {"id":268,"description":"UEM Smoke test","ref":"master","active":false}]}],
            "selected":[{"project":"dev/robot-framework","id":276}]}"""
        val choices = parseScheduleChoices(json)
        assertTrue(choices.connected)
        val project = choices.projects.single()
        assertEquals("dev/robot-framework", project.project)
        assertEquals(
            listOf(ScheduleChoice(276, "Sqool staging", "master", true), ScheduleChoice(268, "UEM Smoke test", "master", false)),
            project.schedules,
        )
        assertEquals(listOf(ScheduleRef("dev/robot-framework", 276)), choices.selected)
    }

    @Test
    fun `pas connecte, aucune planification`() {
        val choices = parseScheduleChoices("""{"connected":false,"expired":true,"projects":[],"selected":[]}""")
        assertFalse(choices.connected)
        assertTrue(choices.expired)
        assertTrue(choices.projects.isEmpty() && choices.selected.isEmpty())
    }
}
