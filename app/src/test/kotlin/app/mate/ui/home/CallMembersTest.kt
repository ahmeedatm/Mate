package app.mate.ui.home

import app.mate.net.VoiceMember
import org.junit.Assert.assertEquals
import org.junit.Test

class CallMembersTest {

    private fun member(id: String, name: String = id, speaking: Boolean = false) = VoiceMember(id, name, avatar = false, speaking = speaking)

    private val alice = member("a", "alice")
    private val bob = member("b", "bob")
    private val carole = member("c", "carole")
    private val david = member("d", "david")

    @Test
    fun `colonne, une seule place, un visage, celui qui a parle en dernier, jamais un +N seul`() {
        assertEquals(MemberRowPlan(listOf(carole), more = 0), planMembers(listOf(alice, bob, carole), mapOf("c" to 300L, "a" to 100L), max = 1, faceFirst = true))
        assertEquals(MemberRowPlan(listOf(alice), more = 0), planMembers(listOf(alice, bob), emptyMap(), max = 1, faceFirst = true))
        assertEquals(MemberRowPlan(emptyList(), more = 0), planMembers(listOf(alice, bob), emptyMap(), max = 0, faceFirst = true))
        assertEquals(MemberRowPlan(listOf(alice, bob), more = 0), planMembers(listOf(alice, bob), emptyMap(), max = 2, faceFirst = true))
    }

    @Test
    fun `tous tiennent, ordre d'arrivée sans personne qui a parlé`() {
        assertEquals(MemberRowPlan(listOf(alice, bob, carole), more = 0), planMembers(listOf(alice, bob, carole), emptyMap(), max = 5))
    }

    @Test
    fun `tous tiennent, personne ne bouge quand quelqu'un parle`() {
        val plan = planMembers(listOf(alice, bob, carole), mapOf("c" to 100L, "b" to 300L), max = 3)
        assertEquals(listOf(alice, bob, carole), plan.shown)
    }

    @Test
    fun `salon plein, derniers à avoir parlé d'abord, les autres dans l'ordre d'arrivée`() {
        val plan = planMembers(listOf(alice, bob, carole, david, member("e")), mapOf("c" to 100L, "d" to 300L), max = 4)
        assertEquals(MemberRowPlan(listOf(david, carole, alice), more = 2), plan)
    }

    @Test
    fun `salon plein, la dernière place devient « +N »`() {
        val plan = planMembers(listOf(alice, bob, carole, david), mapOf("d" to 50L), max = 3)
        assertEquals(MemberRowPlan(listOf(david, alice), more = 2), plan)
    }

    @Test
    fun `juste la place, pas de « +N »`() {
        assertEquals(0, planMembers(listOf(alice, bob, carole), emptyMap(), max = 3).more)
    }

    @Test
    fun `une seule place, seulement « +N »`() {
        assertEquals(MemberRowPlan(emptyList(), more = 2), planMembers(listOf(alice, bob), emptyMap(), max = 1))
    }

    @Test
    fun `aucune place ou personne`() {
        assertEquals(MemberRowPlan(emptyList(), more = 0), planMembers(emptyList(), emptyMap(), max = 4))
        assertEquals(MemberRowPlan(emptyList(), more = 0), planMembers(listOf(alice), emptyMap(), max = 0))
    }

    @Test
    fun `initiales, un ou deux mots, séparateurs de pseudo`() {
        assertEquals("A", initials("alice"))
        assertEquals("M", initials("madenheaven."))
        assertEquals("P6", initials("player_678"))
        assertEquals("JD", initials("jean dupont martin"))
        assertEquals("ÉL", initials("élodie-lucas"))
    }

    @Test
    fun `initiales, emoji, vide, signes seuls`() {
        assertEquals("🎃", initials("🎃 citrouille").take(2))
        assertEquals("?", initials(""))
        assertEquals("?", initials("  ..  "))
    }
}
