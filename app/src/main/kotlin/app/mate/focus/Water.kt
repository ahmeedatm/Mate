package app.mate.focus

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Verres d'eau du jour, objectif et rappel « Pense à boire ». Sur la tablette seulement (marche ordinateur éteint) ;
 * repart à zéro chaque jour. Rappel entre [FROM_HOUR] et [UNTIL_HOUR] h, tant que l'objectif n'est pas atteint, toutes
 * les `intervalMinutes` depuis le dernier verre (ou le dernier rappel).
 */
object Water {

    data class State(val glasses: Int, val goal: Int, val reminder: Boolean, val intervalMinutes: Int)

    private const val FILE = "water"
    private const val FROM_HOUR = 9
    private const val UNTIL_HOUR = 20
    val GOALS = listOf(6, 8, 10, 12)
    val INTERVALS = listOf(30, 60, 120)

    private val listeners = mutableSetOf<(State) -> Unit>()
    private val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun today() = dayKey.format(Date())

    fun state(context: Context): State {
        val p = prefs(context)
        val glasses = if (p.getString("day", null) == today()) p.getInt("glasses", 0) else 0
        return State(glasses, p.getInt("goal", 8), p.getBoolean("reminder", false), p.getInt("interval", 60))
    }

    /** +1 verre (ou -1 pour corriger une erreur). */
    fun add(context: Context, delta: Int) {
        val glasses = (state(context).glasses + delta).coerceIn(0, 30)
        val edit = prefs(context).edit().putString("day", today()).putInt("glasses", glasses)
        if (delta > 0) edit.putLong("last", System.currentTimeMillis())
        edit.apply()
        notifyChanged(context)
    }

    /** Pose le compte du jour (verre touché sur la jauge) : un verre de plus compte comme bu maintenant. */
    fun set(context: Context, glasses: Int) = add(context, glasses - state(context).glasses)

    /** Rappel marche / arrêt (cloche du widget Eau) ; coupé tant qu'on ne l'a pas activé. */
    fun setReminder(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("reminder", on).apply()
        notifyChanged(context)
    }

    fun configure(context: Context, goal: Int, reminder: Boolean, intervalMinutes: Int) {
        prefs(context).edit().putInt("goal", goal).putBoolean("reminder", reminder).putInt("interval", intervalMinutes).apply()
        notifyChanged(context)
    }

    /**
     * À appeler chaque minute : true s'il faut rappeler de boire maintenant (le rappel est alors compté comme fait).
     * Premier passage de la journée : le compte part de [FROM_HOUR] h, pas de la veille. `now` : heure actuelle (tests).
     */
    fun reminderDue(context: Context, now: Calendar = Calendar.getInstance()): Boolean {
        val s = state(context)
        if (!s.reminder || s.glasses >= s.goal) return false
        val hour = now.get(Calendar.HOUR_OF_DAY)
        if (hour < FROM_HOUR || hour >= UNTIL_HOUR) return false
        if (now.timeInMillis - since(context, now) < s.intervalMinutes * 60_000L) return false
        prefs(context).edit().putLong("reminded", now.timeInMillis).apply()
        return true
    }

    /**
     * Heure du prochain rappel (millisecondes), maintenant s'il est en retard ; null si le rappel est coupé, l'objectif
     * atteint, ou s'il tomberait après [UNTIL_HOUR] h. Pour l'affichage seulement : [reminderDue] reste seul juge.
     */
    fun nextReminder(context: Context, now: Calendar = Calendar.getInstance()): Long? {
        val s = state(context)
        if (!s.reminder || s.glasses >= s.goal) return null
        val next = maxOf(since(context, now) + s.intervalMinutes * 60_000L, now.timeInMillis)
        val dayEnd = at(now, UNTIL_HOUR)
        return next.takeIf { it < dayEnd }
    }

    /** Point de départ de l'intervalle : dernier verre, dernier rappel, ou [FROM_HOUR] h aujourd'hui. */
    private fun since(context: Context, now: Calendar): Long {
        val p = prefs(context)
        return maxOf(p.getLong("last", 0), p.getLong("reminded", 0), at(now, FROM_HOUR))
    }

    private fun at(day: Calendar, hour: Int) = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun observe(listener: (State) -> Unit) {
        listeners += listener
    }

    fun unobserve(listener: (State) -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged(context: Context) {
        val s = state(context)
        listeners.toList().forEach { it(s) }
    }
}
