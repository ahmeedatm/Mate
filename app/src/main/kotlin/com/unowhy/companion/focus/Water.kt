package com.unowhy.companion.focus

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

    private val listeners = mutableListOf<(State) -> Unit>()
    private val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun today() = dayKey.format(Date())

    fun state(context: Context): State {
        val p = prefs(context)
        val glasses = if (p.getString("day", null) == today()) p.getInt("glasses", 0) else 0
        return State(glasses, p.getInt("goal", 8), p.getBoolean("reminder", true), p.getInt("interval", 60))
    }

    /** +1 verre (ou -1 pour corriger une erreur). */
    fun add(context: Context, delta: Int) {
        val glasses = (state(context).glasses + delta).coerceIn(0, 30)
        val edit = prefs(context).edit().putString("day", today()).putInt("glasses", glasses)
        if (delta > 0) edit.putLong("last", System.currentTimeMillis())
        edit.apply()
        notifyChanged(context)
    }

    fun configure(context: Context, goal: Int, reminder: Boolean, intervalMinutes: Int) {
        prefs(context).edit().putInt("goal", goal).putBoolean("reminder", reminder).putInt("interval", intervalMinutes).apply()
        notifyChanged(context)
    }

    /**
     * À appeler chaque minute : true s'il faut rappeler de boire maintenant (le rappel est alors compté comme fait).
     * Premier passage de la journée : le compte part de [FROM_HOUR] h, pas de la veille.
     */
    fun reminderDue(context: Context): Boolean {
        val s = state(context)
        if (!s.reminder || s.glasses >= s.goal) return false
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        if (hour < FROM_HOUR || hour >= UNTIL_HOUR) return false
        val dayStart = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, FROM_HOUR); set(Calendar.MINUTE, 0) }.timeInMillis
        val p = prefs(context)
        val since = maxOf(p.getLong("last", 0), p.getLong("reminded", 0), dayStart)
        if (now.timeInMillis - since < s.intervalMinutes * 60_000L) return false
        p.edit().putLong("reminded", now.timeInMillis).apply()
        return true
    }

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
