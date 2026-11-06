package app.mate.ui.home

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.ui.ListCardViews
import app.mate.ui.ListShape
import app.mate.ui.Look
import app.mate.ui.StrikeText
import app.mate.ui.TaskCheck
import app.mate.ui.showForTyping
import app.mate.ui.showImmersive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Exemple de l'Échéance pour le catalogue : un titre, des étapes dont les [done] premières sont faites, l'échéance dans [days] jours. */
class ChecklistSample(@StringRes val title: Int, @ArrayRes val steps: Int, val done: Int, val days: Int) : WidgetSample

/**
 * Échéance : une checklist (titre, date limite, étapes à cocher), gardée dans la config du widget ([Checklist]). Même
 * carte que Tâches et Courses ([ListCardViews], [ListShape]) : l'anneau des étapes faites, le temps qui reste en chiffre
 * (rouge à moins de 24 h), le titre, puis les étapes. Un toucher coche ou décoche une étape et l'enregistre tout de suite ;
 * le compte à rebours suit à la minute. Titre, échéance et étapes se règlent par le ⚙ du mode Modifier (aucun champ dans
 * la carte : une saisie passe par une boîte) ; en Pastille, sans liste, un toucher ouvre ces réglages.
 */
class ChecklistWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_tasks_cadran, this, true)
    }
    private val views = ListCardViews(card).apply {
        (addRow as TextView).setText(R.string.checklist_add)
        addRow.setOnClickListener { askText(R.string.checklist_add, R.string.checklist_step_hint, "") { text -> commit(model().add(text)); render() } }
        ringBox.isClickable = false
        ringBox.isFocusable = false
    }

    override val view: View = card.asCard()

    override val hasSettings get() = true

    /** Aperçu du catalogue : ses étapes remplacent la config, et rien ne s'enregistre. */
    private var preview: Checklist? = null

    private fun model() = preview ?: Checklist.read(placed.config)

    /** Enregistre `next` dans la config du widget, sur la tablette, tout de suite. */
    private fun commit(next: Checklist) {
        if (preview != null) return
        next.writeTo(placed.config)
        host.save()
    }

    override fun applySize() {
        val shape = ListShape.of(placed.size)
        views.apply(shape)
        card.setOnClickListener(if (shape.list) null else View.OnClickListener { showSettings() })
        card.isClickable = !shape.list
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? ChecklistSample ?: return
        val steps = context.resources.getStringArray(example.steps).mapIndexed { i, text -> ChecklistStep(text, done = i < example.done) }
        preview = Checklist(context.getString(example.title), Checklist.inDays(System.currentTimeMillis(), example.days), steps)
        render()
    }

    /** Chaque minute : le compte à rebours bouge, les étapes non. */
    override fun onMinute() = renderHead(model())

    private fun render() {
        val list = model()
        renderHead(list)
        views.status.setText(R.string.checklist_empty)
        views.status.isVisible = list.steps.isEmpty()
        // Les étapes à faire d'abord (une étape cochée descend au prochain affichage), chacune garde son rang dans la config.
        views.fill(list.todoFirst().map { (i, step) -> stepRow(i, step) })
        views.addRow.isVisible = views.shape.addRow && list.total < Checklist.MAX_STEPS
    }

    /** Le temps qui reste en chiffre (rien sans échéance), le titre en légende, l'anneau des étapes faites. */
    private fun renderHead(list: Checklist) {
        val now = System.currentTimeMillis()
        val title = list.title.ifBlank { context.getString(R.string.checklist_default_title) }
        views.showHead(countdownText(list.countdown(now)).orEmpty(), title, list.doneCount, list.total, urgent = list.urgent(now))
        views.ringBox.contentDescription = context.getString(R.string.checklist_progress_cd, list.doneCount, list.total)
    }

    private fun countdownText(c: Countdown): String? = when (c) {
        Countdown.None -> null
        is Countdown.Days -> context.getString(R.string.checklist_days, c.days)
        is Countdown.Hours ->
            if (c.hours == 0) context.getString(R.string.checklist_minutes, c.minutes) else context.getString(R.string.checklist_hours, c.hours, c.minutes)
        Countdown.Passed -> context.getString(R.string.checklist_passed)
    }

    /** Une étape : case ronde et libellé (barré quand elle est faite) ; toucher la case ou la ligne la coche ou la décoche. */
    private fun stepRow(index: Int, step: ChecklistStep): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_task_cadran, views.list, false)
        val check = row.findViewById<TaskCheck>(R.id.task_check)
        val label = row.findViewById<StrikeText>(R.id.task_label)
        row.findViewById<View>(R.id.task_open).visibility = View.GONE
        label.text = step.text
        fun style(done: Boolean, animate: Boolean) {
            check.setDone(done, animate)
            label.setStruck(done, animate)
            label.setTextColor(color(if (done) R.attr.c_text_faint else R.attr.c_text))
            val description = context.getString(if (done) R.string.checklist_step_done else R.string.checklist_step_todo, step.text)
            row.contentDescription = description
            check.contentDescription = description
        }
        style(step.done, animate = false)
        val toggle = View.OnClickListener {
            row.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val next = model().toggle(index)
            val done = next.steps.getOrNull(index)?.done ?: return@OnClickListener
            if (preview != null) preview = next else commit(next)
            style(done, animate = true)
            renderHead(next)
        }
        check.setOnClickListener(toggle)
        row.setOnClickListener(toggle)
        Look.pressSpring(row)
        return row
    }

    // --- Réglages (⚙ du mode Modifier)

    override fun showSettings() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_checklist)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .showImmersive()
        fillSettings(box)
    }

    /** Remplit la boîte d'après la config du moment ; refait après chaque changement. */
    private fun fillSettings(box: LinearLayout) {
        box.removeAllViews()
        val list = model()
        val refresh = { render(); fillSettings(box) }

        box.addView(sectionLabel(R.string.checklist_title_label))
        box.addView(actionButton(list.title.ifBlank { context.getString(R.string.checklist_default_title) }) {
            askText(R.string.checklist_title_label, R.string.checklist_title_hint, list.title) { text -> commit(model().withTitle(text)); refresh() }
        })

        box.addView(sectionLabel(R.string.checklist_due_label))
        val dueRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val dueText = list.dueMs?.let { SimpleDateFormat(context.getString(R.string.checklist_due_format), Locale.FRANCE).format(it) }
            ?: context.getString(R.string.checklist_due_none)
        dueRow.addView(actionButton(dueText) { pickDue(list.dueMs, refresh) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        if (list.dueMs != null) dueRow.addView(
            iconButton(R.drawable.ic_close, context.getString(R.string.checklist_due_remove)) { commit(model().withDue(null)); refresh() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
        )
        box.addView(dueRow, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(sectionLabel(R.string.checklist_steps_label))
        list.steps.forEachIndexed { i, step ->
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(17f).apply {
                text = step.text
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.chip_bg)
                setPadding(dp(16), 0, dp(16), 0)
                contentDescription = context.getString(R.string.checklist_step_rename, step.text)
                setOnClickListener {
                    askText(R.string.checklist_step_rename, R.string.checklist_step_hint, step.text) { text -> commit(model().rename(i, text)); refresh() }
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(
                iconButton(R.drawable.ic_delete, context.getString(R.string.checklist_step_remove, step.text)) { commit(model().remove(i)); refresh() },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
            )
            box.addView(row, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val add = actionButton(context.getString(R.string.checklist_add)) {
            askText(R.string.checklist_add, R.string.checklist_step_hint, "") { text -> commit(model().add(text)); refresh() }
        }.apply { isEnabled = list.total < Checklist.MAX_STEPS }
        box.addView(add, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })
        val uncheck = actionButton(context.getString(R.string.checklist_uncheck_all)) { commit(model().uncheckAll()); refresh() }
            .apply { isEnabled = list.doneCount > 0 }
        box.addView(uncheck, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })
    }

    private fun sectionLabel(text: Int) = label(14f, R.attr.c_text_dim).apply {
        setText(text)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(context, null, 0, R.style.ActionButton).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
        setImageResource(icon)
        // `tint` du style n'est lu que par les vues AppCompat gonflées depuis le XML.
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.c_text_dim)))
        contentDescription = description
        setOnClickListener { onClick() }
    }

    /** Date puis heure, avec les sélecteurs natifs ; la date de départ est l'échéance actuelle, sinon demain 18:00. */
    private fun pickDue(current: Long?, done: () -> Unit) {
        val start = Calendar.getInstance().apply {
            if (current != null) timeInMillis = current else { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0) }
        }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                val due = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                commit(model().withDue(due))
                done()
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).showImmersive()
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).showImmersive()
    }

    /** Boîte à un champ, en haut de l'écran au-dessus du clavier ([showForTyping]) ; `onOk` reçoit le texte saisi. */
    private fun askText(titleRes: Int, hintRes: Int, initial: String, onOk: (String) -> Unit) {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setText(initial)
        input.setSelection(input.text.length)
        input.setHint(hintRes)
        val frame = LinearLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> onOk(input.text.toString()) }
            .create()
            .showForTyping(input)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
