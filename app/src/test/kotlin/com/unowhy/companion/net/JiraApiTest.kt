package com.unowhy.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JiraApiTest {

    @Test
    fun `colonnes dans l'ordre de l'ordinateur avec leurs tickets`() {
        val columns = parseJiraIssues(
            """{"columns":[
                {"status":"À faire","category":"new","issues":[{"key":"QA-1","summary":"Écrire le test","type":"Task","priority":"High"}]},
                {"status":"En cours","category":"indeterminate","issues":[
                    {"key":"QA-2","summary":"Corriger","type":"Bug","priority":null},
                    {"key":"QA-3","summary":"Relire","type":"Story"}]}
            ]}""",
        )
        assertEquals(listOf("À faire", "En cours"), columns.map { it.status })
        assertEquals(listOf("new", "indeterminate"), columns.map { it.category })
        assertEquals(JiraIssue("QA-1", "Écrire le test", "Task", "High"), columns[0].issues.single())
        assertEquals(listOf("QA-2", "QA-3"), columns[1].issues.map { it.key })
    }

    @Test
    fun `priorite null ou absente donne null et pas la chaine null`() {
        val issues = parseJiraIssues(
            """{"columns":[{"status":"S","category":"new","issues":[
                {"key":"QA-2","summary":"a","type":"Bug","priority":null},
                {"key":"QA-3","summary":"b","type":"Bug"}]}]}""",
        )[0].issues
        assertNull(issues[0].priority)
        assertNull(issues[1].priority)
    }

    @Test
    fun `aucune colonne ou colonne sans ticket`() {
        assertTrue(parseJiraIssues("""{"columns":[]}""").isEmpty())
        assertTrue(parseJiraIssues("{}").isEmpty())
        assertTrue(parseJiraIssues("""{"columns":[{"status":"S","category":"new","issues":[]}]}""")[0].issues.isEmpty())
    }

    @Test
    fun `transitions avec et sans champ a finir dans Jira`() {
        val transitions = parseJiraTransitions(
            """{"transitions":[
                {"id":"31","name":"Terminer","to":"Terminé","needsJira":false},
                {"id":"41","name":"Bloquer","to":"Bloqué","needsJira":true}]}""",
        )
        assertEquals(JiraTransition("31", "Terminer", "Terminé", false), transitions[0])
        assertTrue(transitions[1].needsJira)
    }

    @Test
    fun `needsJira absent vaut faux, aucune transition donne une liste vide`() {
        assertEquals(false, parseJiraTransitions("""{"transitions":[{"id":"1","name":"A","to":"B"}]}""")[0].needsJira)
        assertTrue(parseJiraTransitions("""{"transitions":[]}""").isEmpty())
    }
}
