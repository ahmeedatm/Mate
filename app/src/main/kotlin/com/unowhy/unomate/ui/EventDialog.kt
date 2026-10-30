package com.unowhy.unomate.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.net.CalendarEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Ce que l'utilisateur a saisi ; `remindBefore` en minutes (0 = à l'heure), null = pas de rappel. */
data class EventDraft(val title: String, val startMs: Long, val allDay: Boolean, val remindBefore: Int?)

/**
 * Événement de l'agenda personnel (bouton + de l'agenda, ou toucher un de ses événements) : quoi, quel jour
 * (aujourd'hui, demain, les jours de la semaine, autre date), quelle heure ou toute la journée, rappel parlé.
 * `onDelete` : seulement pour un événement existant.
 */
class EventDialog(
    private val context: Context,
    private val onSave: (EventDraft) -> Unit,
    private val onDelete: (() -> Unit)? = null,
) {

    private val dayLabel = SimpleDateFormat("EEE d", Locale.FRANCE)
    private val dateLabel = SimpleDateFormat("EEE d MMM", Locale.FRANCE)

    fun show(existing: CalendarEvent? = null) {
        val root = LayoutInflater.from(context).inflate(R.layout.view_event_dialog, null)
        val title = root.findViewById<EditText>(R.id.event_dialog_title).apply {
            setHint(R.string.event_title_hint)
            // Une ligne, touche « OK » du clavier : le ferme, pour voir les options en dessous.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { v, _, _ ->
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            }
            if (existing != null) setText(existing.title)
        }
        val daysRow = root.findViewById<LinearLayout>(R.id.event_dialog_days)
        val allDay = root.findViewById<SwitchCompat>(R.id.event_dialog_all_day)
        val timeButton = root.findViewById<Button>(R.id.event_dialog_time)
        val reminderTitle = root.findViewById<TextView>(R.id.event_dialog_reminder_title)
        val remindersRow = root.findViewById<LinearLayout>(R.id.event_dialog_reminders)

        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        // Jour choisi (minuit), heure et minute ; nouvel événement : aujourd'hui, à l'heure pleine suivante.
        val day = today.clone() as Calendar
        val now = Calendar.getInstance()
        var hour = (now.get(Calendar.HOUR_OF_DAY) + 1).coerceAtMost(23)
        var minute = 0
        var remindBefore: Int? = null
        if (existing != null) {
            val start = Calendar.getInstance().apply { timeInMillis = existing.startMs }
            day.set(start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH))
            hour = start.get(Calendar.HOUR_OF_DAY)
            minute = start.get(Calendar.MINUTE)
            allDay.isChecked = existing.allDay
            remindBefore = existing.remindBefore
        }

        // Jours : aujourd'hui, demain, les 5 suivants, puis « Autre date… » (calendrier).
        val dayChips = (0..6).map { offset ->
            val date = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            val chip = chip(daysRow)
            chip.text = when (offset) {
                0 -> context.getString(R.string.event_today)
                1 -> context.getString(R.string.event_tomorrow)
                else -> dayLabel.format(date.time).replaceFirstChar { it.uppercase() }
            }
            chip to date
        }
        val otherChip = chip(daysRow)
        val reminders = listOf(
            null to R.string.event_reminder_none, 0 to R.string.event_reminder_at,
            15 to R.string.event_reminder_15, 60 to R.string.event_reminder_60,
        )
        val reminderChips = reminders.map { (minutes, label) -> chip(remindersRow).apply { setText(label) } to minutes }

        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        fun render() {
            val inWeek = dayChips.any { (_, date) -> sameDay(date, day) }
            dayChips.forEach { (chip, date) -> chip.isSelected = sameDay(date, day) }
            otherChip.isSelected = !inWeek
            otherChip.text = if (inWeek) context.getString(R.string.event_other_day) else dateLabel.format(day.time).replaceFirstChar { it.uppercase() }
            timeButton.visibility = if (allDay.isChecked) View.GONE else View.VISIBLE
            timeButton.text = String.format(Locale.FRANCE, "%02d:%02d", hour, minute)
            // Rappel parlé seulement pour un événement à heure fixe.
            val showReminders = if (allDay.isChecked) View.GONE else View.VISIBLE
            reminderTitle.visibility = showReminders
            remindersRow.visibility = showReminders
            reminderChips.forEach { (chip, minutes) -> chip.isSelected = minutes == remindBefore }
        }

        dayChips.forEach { (chip, date) ->
            chip.setOnClickListener { day.timeInMillis = date.timeInMillis; render() }
        }
        otherChip.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d -> day.set(y, m, d); render() },
                day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = today.timeInMillis
            }.showImmersive()
        }
        timeButton.setOnClickListener {
            TimePickerDialog(context, { _, h, m -> hour = h; minute = m; render() }, hour, minute, true).showImmersive()
        }
        allDay.setOnCheckedChangeListener { _, _ -> render() }
        reminderChips.forEach { (chip, minutes) -> chip.setOnClickListener { remindBefore = minutes; render() } }
        render()

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (existing == null) R.string.event_new else R.string.event_edit)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .apply { if (onDelete != null) setNeutralButton(R.string.event_delete) { _, _ -> onDelete.invoke() } }
            .create()
        // Nouvel événement : on écrit d'abord ; modification : clavier fermé pour voir les options.
        dialog.showForTyping(title, keyboard = existing == null)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = title.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener title.setError(context.getString(R.string.event_title_missing))
            val start = (day.clone() as Calendar).apply {
                if (!allDay.isChecked) { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
            }
            dialog.dismiss()
            onSave(EventDraft(text, start.timeInMillis, allDay.isChecked, if (allDay.isChecked) null else remindBefore))
        }
    }

    private fun chip(parent: LinearLayout): Button =
        (LayoutInflater.from(context).inflate(R.layout.item_chip, parent, false) as Button).also { parent.addView(it) }
}
