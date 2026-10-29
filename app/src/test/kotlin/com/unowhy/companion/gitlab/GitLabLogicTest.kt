package com.unowhy.companion.gitlab

import com.unowhy.companion.net.GitLabMr
import com.unowhy.companion.net.Pipeline
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
}
