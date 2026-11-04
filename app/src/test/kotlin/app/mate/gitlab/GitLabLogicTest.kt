package app.mate.gitlab

import app.mate.github.Forge
import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.MrColumn
import app.mate.net.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLabLogicTest {

    private val ready = GitLabMr("a/b", 1, "t", false, "x", Pipeline.SUCCESS, 2, 2, emptyList(), "Prête à fusionner", true, "abc")

    @Test
    fun `fusion possible seulement si tout est reuni`() {
        assertTrue(canMerge(ready, busy = false))
    }

    @Test
    fun `chaque condition seule bloque la fusion`() {
        assertFalse(canMerge(ready.copy(mergeable = false), busy = false))
        assertFalse(canMerge(ready.copy(draft = true), busy = false))
        assertFalse(canMerge(ready.copy(sha = ""), busy = false))
        assertFalse(canMerge(ready, busy = true))
    }

    @Test
    fun `bouton de fusion, retenu avec acces en ecriture, vers l'ordi en lecture seule, rien si pas prete`() {
        assertEquals(MergeAction.HOLD, mergeAction(ready, canMerge = true))
        assertEquals(MergeAction.OPEN_ON_COMPUTER, mergeAction(ready, canMerge = false))
        assertEquals(MergeAction.NONE, mergeAction(ready.copy(mergeable = false), canMerge = true))
        assertEquals(MergeAction.NONE, mergeAction(ready.copy(mergeable = false), canMerge = false))
        assertEquals(MergeAction.NONE, mergeAction(ready.copy(draft = true), canMerge = false))
        assertEquals(MergeAction.NONE, mergeAction(ready.copy(sha = ""), canMerge = false))
    }

    @Test
    fun `une fusion en cours grise le bouton retenu, le bouton ordi reste actif`() {
        assertFalse(mergeEnabled(MergeAction.HOLD, busy = true))
        assertTrue(mergeEnabled(MergeAction.HOLD, busy = false))
        assertTrue(mergeEnabled(MergeAction.OPEN_ON_COMPUTER, busy = false))
    }

    @Test
    fun `approbations`() {
        assertEquals("1/2", approvalsText(1, 2))
        assertEquals("0/1", approvalsText(0, 1))
        assertNull(approvalsText(0, 0))
        assertNull(approvalsText(2, 0))
    }

    @Test
    fun `cle de carte`() {
        assertEquals("softwares/products/uem!42", mrKey(ready.copy(project = "softwares/products/uem", iid = 42)))
    }

    @Test
    fun `nom court du projet`() {
        assertEquals("uem", shortProject("softwares/products/uem"))
        assertEquals("a", shortProject("a"))
        assertEquals("b", shortProject("a/b"))
    }

    @Test
    fun `les colonnes sont toutes la, dans l'ordre de priorite, vides comprises`() {
        val columns = columnsOf(GitLabMrs(emptyList(), emptyList()))
        assertEquals(listOf(MrColumn.DRAFT, MrColumn.FIX, MrColumn.MERGE, MrColumn.RUNNING, MrColumn.READY, MrColumn.REVIEW), columns.map { it.first })
        assertTrue(columns.all { it.second.isEmpty() })
    }

    @Test
    fun `une MR est dans la colonne que le compagnon lui a donnee, a review reprend la liste a relire`() {
        val mrs = GitLabMrs(
            mine = listOf(ready.copy(iid = 1, column = MrColumn.DRAFT), ready.copy(iid = 2, column = MrColumn.FIX), ready.copy(iid = 3, column = MrColumn.MERGE), ready.copy(iid = 4, column = MrColumn.MERGE)),
            review = listOf(ready.copy(iid = 5, column = MrColumn.REVIEW)),
        )
        val byColumn = columnsOf(mrs).associate { (column, list) -> column to list.map { it.iid } }
        assertEquals(
            mapOf(
                MrColumn.DRAFT to listOf(1),
                MrColumn.FIX to listOf(2),
                MrColumn.MERGE to listOf(3, 4),
                MrColumn.RUNNING to emptyList(),
                MrColumn.READY to emptyList(),
                MrColumn.REVIEW to listOf(5),
            ),
            byColumn,
        )
    }

    @Test
    fun `une MR a moi n'apparait jamais dans deux colonnes`() {
        val mine = MrColumn.entries.filter { it != MrColumn.REVIEW }.mapIndexed { i, c -> ready.copy(iid = i + 1, column = c) }
        val shown = columnsOf(GitLabMrs(mine, emptyList())).flatMap { it.second }
        assertEquals(mine.map { it.iid }.sorted(), shown.map { it.iid }.sorted())
    }

    @Test
    fun `GitHub garde deux colonnes, toutes ses PR dans la premiere`() {
        val mrs = GitLabMrs(
            mine = listOf(ready.copy(iid = 1, draft = true), ready.copy(iid = 2)),
            review = listOf(ready.copy(iid = 3)),
        )
        val byColumn = columnsOf(mrs, Forge.GITHUB).map { (column, list) -> column to list.map { it.iid } }
        assertEquals(listOf(MrColumn.READY to listOf(1, 2), MrColumn.REVIEW to listOf(3)), byColumn)
    }
}
