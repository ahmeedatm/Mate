package com.unowhy.companion.agents

import com.unowhy.companion.net.AgentAnswer
import com.unowhy.companion.net.AgentOption
import com.unowhy.companion.net.AgentQuestion
import com.unowhy.companion.net.parseAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerDraftTest {

    private val single = AgentQuestion("Couleur", "Quelle couleur ?", false, listOf(AgentOption("Rouge", ""), AgentOption("Bleu", "")))
    private val multi = AgentQuestion("Fruits", "Quels fruits ?", true, listOf(AgentOption("Pomme", ""), AgentOption("Poire", ""), AgentOption("Kiwi", "")))

    @Test
    fun `rien n'est repondu au depart`() {
        val d = AnswerDraft(listOf(single, multi))
        assertEquals(2, d.size)
        assertFalse(d.isAnswered(0))
        assertFalse(d.isComplete())
    }

    @Test
    fun `une question simple garde une seule option`() {
        val d = AnswerDraft(listOf(single))
        d.toggle(0, 0)
        d.toggle(0, 1)
        assertEquals(setOf(1), d.choices(0))
        assertTrue(d.isComplete())
    }

    @Test
    fun `une question multiple ajoute et retire`() {
        val d = AnswerDraft(listOf(multi))
        d.toggle(0, 2)
        d.toggle(0, 0)
        d.toggle(0, 1)
        d.toggle(0, 1)
        assertEquals(listOf(AgentAnswer(listOf(0, 2), null)), d.build())
    }

    @Test
    fun `le texte libre remplace l'option d'une question simple et s'ajoute sur une multiple`() {
        val d = AnswerDraft(listOf(single, multi))
        d.toggle(0, 1)
        d.setOther(0, "  Violet foncé ")
        assertEquals(emptySet<Int>(), d.choices(0))
        assertEquals("Violet foncé", d.other(0))
        // Choisir une option efface le texte libre d'une question simple.
        d.toggle(0, 0)
        assertNull(d.other(0))
        d.toggle(1, 0)
        d.setOther(1, "Fraise")
        assertEquals(setOf(0), d.choices(1))
        assertEquals(listOf(AgentAnswer(listOf(0), null), AgentAnswer(listOf(0), "Fraise")), d.build())
    }

    @Test
    fun `un texte libre vide est retire`() {
        val d = AnswerDraft(listOf(single))
        d.setOther(0, "x")
        d.setOther(0, "   ")
        assertNull(d.other(0))
        assertFalse(d.isAnswered(0))
    }

    @Test
    fun `refuse une option qui n'existe pas`() {
        val d = AnswerDraft(listOf(single))
        val failed = runCatching { d.toggle(0, 5) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun `lit une demande de permission et une question du compagnon`() {
        val permission = parseAsk("""{"id":"a1","kind":"permission","session":"docs","dir":"docs","tool":"Bash","text":"npm test","risky":true,"questions":null,"expiresInMs":42000}""")
        assertEquals("permission", permission.kind)
        assertTrue(permission.risky)
        assertEquals("npm test", permission.text)
        assertEquals(42_000L, permission.expiresInMs)
        assertTrue(permission.questions.isEmpty())

        val question = parseAsk(
            """{"id":"q1","kind":"question","session":"docs","dir":"docs","tool":"AskUserQuestion","text":"","risky":false,
              "questions":[{"question":"Couleur ?","header":"C","multi":false,"options":[{"label":"Rouge","description":"Le rouge"},{"label":"Bleu","description":""}]}],"expiresInMs":1000}""",
        )
        assertEquals(1, question.questions.size)
        assertEquals("Couleur ?", question.questions[0].question)
        assertFalse(question.questions[0].multi)
        assertEquals(listOf(AgentOption("Rouge", "Le rouge"), AgentOption("Bleu", "")), question.questions[0].options)
    }

    @Test
    fun `lit un plan a approuver`() {
        val plan = parseAsk("""{"id":"p1","kind":"plan","session":"docs","dir":"docs","tool":"ExitPlanMode","text":"# Plan\n1. Écrire","risky":false,"questions":null,"expiresInMs":5000}""")
        assertEquals("plan", plan.kind)
        assertEquals("# Plan\n1. Écrire", plan.text)
        assertTrue(plan.questions.isEmpty())
    }
}
