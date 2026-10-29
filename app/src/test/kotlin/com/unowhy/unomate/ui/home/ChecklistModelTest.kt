package com.unowhy.unomate.ui.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChecklistModelTest {

    private fun at(hour: Int, minute: Int, days: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 26, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun list(vararg steps: Pair<String, Boolean>, due: Long? = null) =
        Checklist("Clôture", due, steps.map { ChecklistStep(it.first, it.second) })

    @Test
    fun `plus d un jour, J moins le nombre de jours`() {
        assertEquals(Countdown.Days(3), list(due = at(18, 0, 3)).countdown(at(10, 0)))
        assertEquals(Countdown.Days(2), list(due = at(18, 0, 2)).countdown(at(20, 0)))
    }

    @Test
    fun `le lendemain a la meme heure ou plus tard reste J-1`() {
        assertEquals(Countdown.Days(1), list(due = at(18, 0, 1)).countdown(at(18, 0)))
        assertEquals(Countdown.Days(1), list(due = at(9, 0, 1)).countdown(at(8, 0)))
    }

    @Test
    fun `moins de 24 heures, heures et minutes`() {
        assertEquals(Countdown.Hours(5, 20), list(due = at(18, 0)).countdown(at(12, 40)))
        assertEquals(Countdown.Hours(23, 59), list(due = at(18, 0, 1)).countdown(at(18, 1)))
        assertEquals(Countdown.Hours(0, 45), list(due = at(18, 0)).countdown(at(17, 15)))
    }

    @Test
    fun `echeance passee, ou aucune`() {
        assertEquals(Countdown.Passed, list(due = at(18, 0)).countdown(at(18, 0)))
        assertEquals(Countdown.Passed, list(due = at(18, 0)).countdown(at(19, 0)))
        assertEquals(Countdown.None, list().countdown(at(10, 0)))
    }

    @Test
    fun `rouge sous 24 heures et apres l'echeance seulement`() {
        val now = at(12, 0)
        assertFalse(list(due = at(12, 0, 1)).urgent(now))
        assertTrue(list(due = at(11, 59, 1)).urgent(now))
        assertTrue(list(due = at(13, 0)).urgent(now))
        assertTrue(list(due = at(9, 0)).urgent(now))
        assertFalse(list().urgent(now))
        assertFalse(list(due = at(12, 0, 3)).urgent(now))
    }

    @Test
    fun `progression`() {
        val l = list("a" to true, "b" to false, "c" to true, "d" to false)
        assertEquals(2, l.doneCount)
        assertEquals(4, l.total)
        assertEquals(0.5f, l.fraction, 0f)
        assertEquals(0f, list().fraction, 0f)
        assertEquals(1f, list("a" to true).fraction, 0f)
    }

    @Test
    fun `cocher puis decocher une etape`() {
        val l = list("a" to false, "b" to false)
        val checked = l.toggle(1)
        assertEquals(listOf(false, true), checked.steps.map { it.done })
        assertEquals(listOf(false, false), checked.toggle(1).steps.map { it.done })
        // Hors liste : rien ne change.
        assertEquals(l, l.toggle(5))
        assertEquals(l, l.toggle(-1))
    }

    @Test
    fun `ajouter, renommer, retirer, tout decocher`() {
        var l = list("a" to true)
        l = l.add("  b  ")
        assertEquals(listOf("a", "b"), l.steps.map { it.text })
        assertFalse(l.steps[1].done)
        assertEquals(l, l.add("   "))
        assertEquals(listOf("a", "c"), l.rename(1, "c").steps.map { it.text })
        assertEquals(l, l.rename(1, " "))
        assertEquals(l, l.rename(9, "x"))
        assertEquals(listOf("b"), l.remove(0).steps.map { it.text })
        assertEquals(l, l.remove(2))
        assertEquals(listOf(false, false), l.uncheckAll().steps.map { it.done })
    }

    @Test
    fun `la liste a une taille maxi`() {
        var l = Checklist()
        repeat(Checklist.MAX_STEPS + 5) { l = l.add("étape $it") }
        assertEquals(Checklist.MAX_STEPS, l.total)
        assertEquals("x".repeat(Checklist.MAX_TEXT), Checklist().add("x".repeat(500)).steps.single().text)
    }

    @Test
    fun `titre et echeance`() {
        assertEquals("Fin d'année", Checklist().withTitle("  Fin d'année ").title)
        assertEquals("", Checklist("x").withTitle("  ").title)
        assertEquals(42L, Checklist().withDue(42L).dueMs)
        assertNull(Checklist(dueMs = 42L).withDue(null).dueMs)
    }

    @Test
    fun `ecrit puis relit la config a l'identique`() {
        val l = Checklist("Clôture du mois", at(18, 0, 2), listOf(ChecklistStep("Balance", true), ChecklistStep("TVA", false)))
        val config = JSONObject()
        l.writeTo(config)
        assertEquals(l, Checklist.read(JSONObject(config.toString())))
        // Sans échéance, la clé disparaît.
        l.withDue(null).writeTo(config)
        assertFalse(config.has("due"))
        assertNull(Checklist.read(config).dueMs)
    }

    @Test
    fun `une config vide ou abimee donne les valeurs par defaut`() {
        assertEquals(Checklist(), Checklist.read(JSONObject()))
        assertEquals(Checklist(), Checklist.read(JSONObject("""{"title":5,"due":"demain","steps":"non"}""").also { it.remove("title") }))
        val broken = JSONObject("""{"due":-4,"steps":[1,null,{"t":"  "},{"d":true},{"t":"Balance","d":"oui"},{"t":"TVA","d":true}]}""")
        val read = Checklist.read(broken)
        assertNull(read.dueMs)
        assertEquals(listOf(ChecklistStep("Balance", false), ChecklistStep("TVA", true)), read.steps)
    }

    @Test
    fun `cocher se garde dans la config du widget`() {
        val config = Checklist.config("", at(18, 0), listOf(ChecklistStep("a"), ChecklistStep("b")))
        Checklist.read(config).toggle(0).writeTo(config)
        assertEquals(listOf(true, false), Checklist.read(config).steps.map { it.done })
    }
}
