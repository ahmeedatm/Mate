package com.unowhy.companion.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.unowhy.companion.R
import com.unowhy.companion.focus.Water

/**
 * Carte Eau de l'Accueil : un segment par verre de l'objectif, rempli au fil de la journée ; + = un verre bu,
 * − = corriger ; toucher le titre = objectif et rappel. Le rappel lui-même est lancé par MainActivity (chaque minute).
 */
class WaterCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val count: TextView
    private val gauge: LinearLayout
    private val listener: (Water.State) -> Unit = { render(it) }

    /** Petit widget de l'Accueil : « 3 / 8 » sans « verres », pas de bouton − (corriger : réglages). */
    var compact = false
        set(value) {
            field = value
            findViewById<View>(R.id.water_minus).visibility = if (value) View.GONE else View.VISIBLE
            refresh()
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_water_card, this, true)
        count = findViewById(R.id.water_count)
        gauge = findViewById(R.id.water_gauge)
        findViewById<View>(R.id.water_plus).setOnClickListener {
            Water.add(context, 1)
            Look.pop(count)
        }
        findViewById<View>(R.id.water_minus).setOnClickListener { Water.add(context, -1) }
        findViewById<View>(R.id.water_header).setOnClickListener { showSettings() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Water.observe(listener)
        render(Water.state(context))
    }

    override fun onDetachedFromWindow() {
        Water.unobserve(listener)
        super.onDetachedFromWindow()
    }

    /** Relu chaque minute par l'Accueil : un nouveau jour remet la jauge à zéro. */
    fun refresh() = render(Water.state(context))

    private fun render(state: Water.State) {
        val done = state.glasses >= state.goal
        count.text = context.getString(
            when {
                compact -> if (done) R.string.water_done_short else R.string.water_count_short
                else -> if (done) R.string.water_done else R.string.water_count
            },
            state.glasses, state.goal,
        )
        count.setTextColor(themeColor(context, if (done) R.attr.c_ok else R.attr.c_text))
        gauge.removeAllViews()
        val gap = (3 * resources.displayMetrics.density).toInt()
        for (i in 0 until state.goal) {
            val color = when {
                i >= state.glasses -> R.attr.c_surface_raised
                done -> R.attr.c_ok
                else -> R.attr.c_hue_cyan
            }
            gauge.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(themeColor(context, color))
                    cornerRadius = 6 * resources.displayMetrics.density
                }
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = gap })
        }
    }

    /** Objectif (verres par jour), rappel marche/arrêt et sa fréquence ; enregistré à chaque toucher. */
    private fun showSettings() {
        val state = Water.state(context)
        var goal = state.goal
        var interval = state.intervalMinutes
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(pad, pad / 3, pad, 0) }
        val inflater = LayoutInflater.from(context)

        fun title(text: Int) = TitleView(context).apply {
            setText(text)
            setPadding(0, pad / 2, 0, pad / 3)
        }
        fun chips(row: LinearLayout, values: List<Int>, label: (Int) -> String) =
            values.map { v -> (inflater.inflate(R.layout.item_chip, row, false) as Button).apply { text = label(v); row.addView(this) } to v }

        val goalRow = LinearLayout(context)
        val goalChips = chips(goalRow, Water.GOALS) { context.getString(R.string.water_goal_value, it) }
        val reminder = SwitchCompat(context).apply {
            setText(R.string.water_reminder)
            textSize = 16f
            isChecked = state.reminder
            minHeight = (48 * resources.displayMetrics.density).toInt()
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
        }
        goalChips.forEach { (chip, v) -> chip.setOnClickListener { goal = v; update() } }
        intervalChips.forEach { (chip, v) -> chip.setOnClickListener { interval = v; update() } }
        reminder.setOnCheckedChangeListener { _, _ -> update() }

        box.addView(title(R.string.water_goal))
        box.addView(goalRow)
        box.addView(reminder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
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
