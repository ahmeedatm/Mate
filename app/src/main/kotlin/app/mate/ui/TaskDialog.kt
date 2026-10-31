package app.mate.ui

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
import app.mate.R
import java.util.Calendar
import java.util.Locale

/**
 * Nouvelle tâche avec options (bouton ⏰ de la carte Tâches) : une fois, tous les jours, du lundi au vendredi ou
 * des jours choisis, et rappel parlé à une heure. `onSave(label, remindAt "HH:MM" ou null, jours 1 = lundi ou null)`.
 */
class TaskDialog(private val context: Context, private val onSave: (String, String?, List<Int>?) -> Unit) {

    private enum class Repeat { ONCE, DAILY, WEEKDAYS, CUSTOM }

    /** `from` : champ de la carte, dont le clavier est fermé si le texte est déjà tapé. */
    fun show(initialLabel: String, from: View? = null) {
        val root = LayoutInflater.from(context).inflate(R.layout.view_task_dialog, null)
        val label = root.findViewById<EditText>(R.id.task_dialog_label).apply {
            setHint(R.string.task_hint)
            // Une ligne, touche « OK » du clavier : le ferme, pour voir les options en dessous.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { v, _, _ ->
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            }
            setText(initialLabel)
            setSelection(text.length)
        }
        val repeatChips = root.findViewById<LinearLayout>(R.id.task_dialog_repeat).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) as Button }
        }
        val daysRow = root.findViewById<LinearLayout>(R.id.task_dialog_days)
        val remind = root.findViewById<SwitchCompat>(R.id.task_dialog_remind)
        val timeButton = root.findViewById<Button>(R.id.task_dialog_time)
        val hint = root.findViewById<TextView>(R.id.task_dialog_hint)

        var repeat = Repeat.ONCE
        val chosenDays = sortedSetOf<Int>()
        // Heure proposée : l'heure pleine suivante.
        var hour = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24
        var minute = 0

        fun render() {
            repeatChips.forEachIndexed { i, chip -> chip.isSelected = i == repeat.ordinal }
            daysRow.visibility = if (repeat == Repeat.CUSTOM) View.VISIBLE else View.GONE
            for (i in 0 until daysRow.childCount) daysRow.getChildAt(i).isSelected = (i + 1) in chosenDays
            timeButton.isEnabled = remind.isChecked
            timeButton.text = String.format(Locale.FRANCE, "%02d:%02d", hour, minute)
            hint.setText(
                when {
                    !remind.isChecked -> R.string.task_reminder_none
                    repeat == Repeat.ONCE -> R.string.task_reminder_once
                    else -> R.string.task_reminder_repeat
                },
            )
        }

        repeatChips.forEachIndexed { i, chip -> chip.setOnClickListener { repeat = Repeat.entries[i]; render() } }
        context.resources.getStringArray(R.array.task_day_letters).forEachIndexed { i, letter ->
            val chip = LayoutInflater.from(context).inflate(R.layout.item_day_chip, daysRow, false) as Button
            chip.text = letter
            chip.setOnClickListener {
                if (!chosenDays.add(i + 1)) chosenDays.remove(i + 1)
                render()
            }
            daysRow.addView(chip)
        }
        remind.setOnCheckedChangeListener { _, _ -> render() }
        timeButton.setOnClickListener {
            TimePickerDialog(context, { _, h, m -> hour = h; minute = m; render() }, hour, minute, true).showImmersive()
        }
        render()

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.task_new)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        // Texte déjà tapé : clavier fermé, pour voir les options (il cache la moitié de l'écran).
        if (initialLabel.isNotEmpty()) {
            from?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        }
        dialog.showForTyping(label, keyboard = initialLabel.isEmpty())
        // Bouton qui ne ferme la boîte que si la saisie est complète.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = label.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener label.setError(context.getString(R.string.task_label_missing))
            val days = when (repeat) {
                Repeat.ONCE -> null
                Repeat.DAILY -> (1..7).toList()
                Repeat.WEEKDAYS -> (1..5).toList()
                Repeat.CUSTOM -> chosenDays.toList().ifEmpty {
                    hint.setText(R.string.task_days_missing)
                    return@setOnClickListener
                }
            }
            val remindAt = if (remind.isChecked) String.format(Locale.ROOT, "%02d:%02d", hour, minute) else null
            dialog.dismiss()
            onSave(text, remindAt, days)
        }
    }
}
