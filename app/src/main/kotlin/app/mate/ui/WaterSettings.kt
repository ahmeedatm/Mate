package app.mate.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import app.mate.R
import app.mate.focus.Water

/** Réglages de l'eau (objectif, rappel) : touché sur le titre du widget Eau, appui long sur la complication du héros. */
object WaterSettings {
    /** Verres d'aujourd'hui (− / +), objectif, rappel marche/arrêt et sa fréquence ; enregistré à chaque toucher. */
    fun show(context: Context) {
        val state = Water.state(context)
        var goal = state.goal
        var interval = state.intervalMinutes
        val pad = context.dp(24)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 3, pad, 0) }
        val inflater = LayoutInflater.from(context)

        fun title(text: Int) = TitleView(context).apply {
            setText(text)
            setPadding(0, pad / 2, 0, pad / 3)
        }
        fun chips(row: LinearLayout, values: List<Int>, label: (Int) -> String) =
            values.map { v -> (inflater.inflate(R.layout.item_chip, row, false) as Button).apply { text = label(v); row.addView(this) } to v }

        val today = inflater.inflate(R.layout.item_water_today, box, false)
        val todayCount = today.findViewById<TextView>(R.id.water_today_count)
        val showToday = { s: Water.State -> todayCount.text = context.getString(R.string.water_count, s.glasses, s.goal) }
        today.findViewById<View>(R.id.water_today_minus).setOnClickListener { Water.add(context, -1); showToday(Water.state(context)) }
        today.findViewById<View>(R.id.water_today_plus).setOnClickListener { Water.add(context, 1); showToday(Water.state(context)) }

        val goalRow = LinearLayout(context)
        val goalChips = chips(goalRow, Water.GOALS) { context.getString(R.string.water_goal_value, it) }
        val reminder = SwitchCompat(context).apply {
            setText(R.string.water_reminder)
            textSize = 16f
            isChecked = state.reminder
            minHeight = context.dp(48)
        }
        val intervalTitle = title(R.string.water_interval)
        val intervalRow = LinearLayout(context)
        val intervalChips = chips(intervalRow, Water.INTERVALS) {
            context.getString(when (it) { 30 -> R.string.water_interval_30; 60 -> R.string.water_interval_60; else -> R.string.water_interval_120 })
        }

        fun update() {
            goalChips.forEach { (chip, v) -> chip.isSelected = v == goal }
            intervalChips.forEach { (chip, v) -> chip.isSelected = v == interval }
            val visibility = if (reminder.isChecked) View.VISIBLE else View.GONE
            intervalTitle.visibility = visibility
            intervalRow.visibility = visibility
            Water.configure(context, goal, reminder.isChecked, interval)
            showToday(Water.state(context))
        }
        goalChips.forEach { (chip, v) -> chip.setOnClickListener { goal = v; update() } }
        intervalChips.forEach { (chip, v) -> chip.setOnClickListener { interval = v; update() } }
        reminder.setOnCheckedChangeListener { _, _ -> update() }

        box.addView(title(R.string.water_today))
        box.addView(today)
        box.addView(title(R.string.water_goal))
        box.addView(goalRow)
        box.addView(reminder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        box.addView(intervalTitle)
        box.addView(intervalRow)
        update()
        AlertDialog.Builder(context)
            .setTitle(R.string.water_settings)
            .setView(box)
            .setPositiveButton(R.string.close, null)
            .showImmersive()
    }
}
