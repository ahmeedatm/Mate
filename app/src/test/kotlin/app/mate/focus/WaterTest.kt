package app.mate.focus

import app.mate.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WaterTest {

    private val context = FakeContext()

    /** Aujourd'hui à l'heure donnée : le compte des verres est celui du jour. */
    private fun at(hour: Int, minute: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `etat par defaut`() {
        assertEquals(Water.State(glasses = 0, goal = 8, reminder = false, intervalMinutes = 60), Water.state(context))
    }

    @Test
    fun `add compte les verres sans passer sous zero`() {
        Water.add(context, 1)
        Water.add(context, 1)
        assertEquals(2, Water.state(context).glasses)
        Water.add(context, -5)
        assertEquals(0, Water.state(context).glasses)
    }

    @Test
    fun `les verres d'un autre jour ne comptent pas`() {
        context.getSharedPreferences("water", 0).edit().putString("day", "2000-01-01").putInt("glasses", 5).apply()
        assertEquals(0, Water.state(context).glasses)
    }

    @Test
    fun `configure et observateurs`() {
        var seen: Water.State? = null
        val listener: (Water.State) -> Unit = { seen = it }
        Water.observe(listener)
        Water.configure(context, goal = 10, reminder = false, intervalMinutes = 30)
        Water.unobserve(listener)
        assertEquals(Water.State(0, 10, false, 30), seen)
    }

    @Test
    fun `rappel compte depuis 9 h au premier passage du jour`() {
        Water.setReminder(context, true)
        assertFalse(Water.reminderDue(context, at(9, 30)))
        assertTrue(Water.reminderDue(context, at(10, 0)))
    }

    @Test
    fun `rappel une fois par intervalle`() {
        Water.setReminder(context, true)
        assertTrue(Water.reminderDue(context, at(10, 30)))
        assertFalse(Water.reminderDue(context, at(11, 0)))
        assertTrue(Water.reminderDue(context, at(11, 30)))
    }

    @Test
    fun `pas de rappel la nuit`() {
        Water.setReminder(context, true)
        assertFalse(Water.reminderDue(context, at(8, 59)))
        assertFalse(Water.reminderDue(context, at(20, 0)))
    }

    @Test
    fun `pas de rappel desactive ou objectif atteint`() {
        Water.configure(context, goal = 2, reminder = false, intervalMinutes = 60)
        assertFalse(Water.reminderDue(context, at(15)))
        Water.configure(context, goal = 2, reminder = true, intervalMinutes = 60)
        Water.add(context, 2)
        assertFalse(Water.reminderDue(context, at(15)))
    }

    @Test
    fun `prochain rappel un intervalle apres 9 h ou apres le dernier verre`() {
        Water.configure(context, goal = 8, reminder = true, intervalMinutes = 60)
        assertEquals(at(10).timeInMillis, Water.nextReminder(context, at(7)))
        context.getSharedPreferences("water", 0).edit().putLong("last", at(13, 15).timeInMillis).apply()
        assertEquals(at(14, 15).timeInMillis, Water.nextReminder(context, at(13, 30)))
    }

    @Test
    fun `prochain rappel en retard est maintenant`() {
        Water.configure(context, goal = 8, reminder = true, intervalMinutes = 30)
        assertEquals(at(12).timeInMillis, Water.nextReminder(context, at(12)))
    }

    @Test
    fun `pas de prochain rappel coupe, objectif atteint ou apres 20 h`() {
        Water.configure(context, goal = 2, reminder = false, intervalMinutes = 60)
        assertNull(Water.nextReminder(context, at(10)))
        Water.configure(context, goal = 2, reminder = true, intervalMinutes = 120)
        context.getSharedPreferences("water", 0).edit().putLong("last", at(18, 30).timeInMillis).apply()
        assertNull(Water.nextReminder(context, at(19)))
        Water.add(context, 2)
        assertNull(Water.nextReminder(context, at(10)))
    }
}
