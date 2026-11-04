package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JiraApiTest {

    @Test
    fun `colonnes dans l'ordre de l'ordinateur avec leurs tickets`() {
        val result = parseJiraIssues(
            """{"boardsChosen":true,"columns":[
                {"title":"À faire","issues":[{"key":"QA-1","summary":"Écrire le test","type":"Task","priority":"High","done":false}]},
                {"title":"En cours","issues":[
                    {"key":"QA-2","summary":"Corriger","type":"Bug","priority":null,"done":false},
                    {"key":"QA-3","summary":"Relire","type":"Story","done":false}]}
            ]}""",
        )
        assertTrue(result.boardsChosen)
        assertEquals(listOf("À faire", "En cours"), result.columns.map { it.title })
        assertEquals(JiraIssue("QA-1", "Écrire le test", "Task", "High", false), result.columns[0].issues.single())
        assertEquals(listOf("QA-2", "QA-3"), result.columns[1].issues.map { it.key })
    }

    @Test
    fun `ticket termine marque done, absent vaut faux`() {
        val issues = parseJiraIssues(
            """{"boardsChosen":true,"columns":[{"title":"Done","issues":[
                {"key":"QA-1","summary":"a","type":"Bug","priority":"Low","done":true},
                {"key":"QA-2","summary":"b","type":"Bug","priority":"Low"}]}]}""",
        ).columns[0].issues
        assertTrue(issues[0].done)
        assertFalse(issues[1].done)
    }

    @Test
    fun `boardsChosen faux ou absent`() {
        assertFalse(parseJiraIssues("""{"boardsChosen":false,"columns":[{"title":"S","issues":[]}]}""").boardsChosen)
        assertFalse(parseJiraIssues("""{"columns":[]}""").boardsChosen)
    }

    @Test
    fun `priorite null ou absente donne null et pas la chaine null`() {
        val issues = parseJiraIssues(
            """{"boardsChosen":true,"columns":[{"title":"S","issues":[
                {"key":"QA-2","summary":"a","type":"Bug","priority":null,"done":false},
                {"key":"QA-3","summary":"b","type":"Bug","done":false}]}]}""",
        ).columns[0].issues
        assertNull(issues[0].priority)
        assertNull(issues[1].priority)
    }

    @Test
    fun `aucune colonne ou colonne vide gardee avec zero ticket`() {
        assertTrue(parseJiraIssues("""{"boardsChosen":true,"columns":[]}""").columns.isEmpty())
        assertTrue(parseJiraIssues("{}").columns.isEmpty())
        val empty = parseJiraIssues("""{"boardsChosen":true,"columns":[{"title":"Backlog","issues":[]},{"title":"Bloqué"}]}""").columns
        assertEquals(listOf("Backlog", "Bloqué"), empty.map { it.title })
        assertTrue(empty.all { it.issues.isEmpty() })
    }

    @Test
    fun `tableaux et ids coches`() {
        val result = parseJiraBoards(
            """{"boards":[{"id":12,"name":"Équipe QA","project":"QA"},{"id":7,"name":"Sprint","project":"TECH"}],"selected":[7,12]}""",
        )
        assertEquals(listOf(JiraBoard(12, "Équipe QA", "QA"), JiraBoard(7, "Sprint", "TECH")), result.boards)
        assertEquals(listOf(7, 12), result.selected)
    }

    @Test
    fun `aucun tableau ni selection`() {
        val result = parseJiraBoards("{}")
        assertTrue(result.boards.isEmpty())
        assertTrue(result.selected.isEmpty())
        assertTrue(parseJiraBoards("""{"boards":[],"selected":[]}""").selected.isEmpty())
    }

    @Test
    fun `tableaux groupes par projet dans l'ordre d'apparition`() {
        val boards = listOf(JiraBoard(1, "a", "QA"), JiraBoard(2, "b", "TECH"), JiraBoard(3, "c", "QA"))
        val groups = groupBoardsByProject(boards)
        assertEquals(listOf("QA", "TECH"), groups.map { it.first })
        assertEquals(listOf(1, 3), groups[0].second.map { it.id })
    }

    @Test
    fun `ids envoyes dans l'ordre de la liste affichee parmi les coches`() {
        val boards = listOf(JiraBoard(1, "a", "QA"), JiraBoard(2, "b", "TECH"), JiraBoard(3, "c", "QA"))
        // Affiché : QA (1, 3) puis TECH (2). Coché dans le désordre.
        assertEquals(listOf(1, 3, 2), checkedBoardIds(boards, setOf(2, 3, 1)))
        assertEquals(listOf(3, 2), checkedBoardIds(boards, setOf(2, 3)))
        assertTrue(checkedBoardIds(boards, emptySet()).isEmpty())
        assertTrue(checkedBoardIds(boards, setOf(99)).isEmpty())
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

    @Test
    fun `assigne lu seulement quand l'ordinateur le donne`() {
        val issues = parseJiraIssues(
            """{"boardsChosen":true,"columns":[{"title":"Go Validate","issues":[
                {"key":"QA-1","summary":"a","type":"Bug","priority":"Low","done":false,"assignee":"Léa"},
                {"key":"QA-2","summary":"b","type":"Bug","priority":"Low","done":false},
                {"key":"QA-3","summary":"c","type":"Bug","priority":"Low","done":false,"assignee":null}]}]}""",
        ).columns[0].issues
        assertEquals("Léa", issues[0].assignee)
        assertNull(issues[1].assignee)
        assertNull(issues[2].assignee)
    }

    @Test
    fun `reglages de colonnes, affichee par defaut et sans tous les tickets`() {
        val result = parseJiraColumnChoices(
            """{"boardsChosen":true,"columns":[
                {"key":"todo","title":"TO DO","shown":true,"everyone":false},
                {"key":"goqa","title":"Go Validate","shown":true,"everyone":true},
                {"key":"backlog","title":"Backlog","shown":false,"everyone":false},
                {"key":"done","title":"DONE"}]}""",
        )
        assertTrue(result.boardsChosen)
        assertEquals(
            listOf(
                JiraColumnChoice("todo", "TO DO", true, false),
                JiraColumnChoice("goqa", "Go Validate", true, true),
                JiraColumnChoice("backlog", "Backlog", false, false),
                JiraColumnChoice("done", "DONE", true, false),
            ),
            result.columns,
        )
    }

    @Test
    fun `aucun tableau choisi, aucune colonne a regler`() {
        assertFalse(parseJiraColumnChoices("""{"boardsChosen":false,"columns":[]}""").boardsChosen)
        assertTrue(parseJiraColumnChoices("{}").columns.isEmpty())
    }

    @Test
    fun `cles envoyees a l'enregistrement, masquees puis tous les tickets`() {
        val columns = listOf(
            JiraColumnChoice("todo", "TO DO", true, false),
            JiraColumnChoice("backlog", "Backlog", false, false),
            JiraColumnChoice("goqa", "Go Validate", true, true),
            JiraColumnChoice("rejected", "REJECTED", false, true),
        )
        val (hidden, everyone) = jiraColumnChoiceKeys(columns)
        assertEquals(listOf("backlog", "rejected"), hidden)
        assertEquals(listOf("goqa", "rejected"), everyone)
        assertEquals(emptyList<String>() to emptyList<String>(), jiraColumnChoiceKeys(emptyList()))
    }
}
