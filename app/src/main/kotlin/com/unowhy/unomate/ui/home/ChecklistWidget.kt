package com.unowhy.unomate.ui.home

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
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.ImageViewCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.ui.Look
import com.unowhy.unomate.ui.StrikeText
import com.unowhy.unomate.ui.TaskCheck
import com.unowhy.unomate.ui.TitleView
import com.unowhy.unomate.ui.showForTyping
import com.unowhy.unomate.ui.showImmersive
import com.unowhy.unomate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Clôture : une checklist d'échéance (titre, date limite, étapes à cocher), gardée dans la config du widget
 * ([Checklist]). Un toucher coche ou décoche une étape et l'enregistre tout de suite ; le compte à rebours et la
 * progression suivent à la minute. Titre, échéance et étapes se règlent par le ⚙ du mode Modifier (aucun champ dans la
 * carte : une saisie passe par une boîte).
 */
class ChecklistWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = TitleView(context).apply {
        setIcon(R.drawable.ic_tile_check_circle)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val countdown = label(26f, light = true).apply { isSingleLine = true }
    private val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(countdown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
    }

    private val fill = View(context).apply { setBackgroundResource(R.drawable.bar) }
    private val rest = View(context).apply { setBackgroundResource(R.drawable.bar) }
    private val bar = LinearLayout(context).apply {
        addView(fill, LinearLayout.LayoutParams(0, MATCH, 0f))
        addView(rest, LinearLayout.LayoutParams(0, MATCH, 1f))
    }
    private val count = label(14f, R.attr.c_text_dim).apply { isSingleLine = true }
    private val progress = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f))
        addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
    }

    private val steps = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(steps, ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override val view = LinearLayout(context).asCard(20).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(progress, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
    }

    override val hasSettings get() = true

    private fun model() = Checklist.read(placed.config)

    /** Enregistre `next` dans la config du widget, sur la tablette, tout de suite. */
    private fun commit(next: Checklist) {
        next.writeTo(placed.config)
        host.save()
    }

    override fun applySize() = render()

    /** Chaque minute : le compte à rebours bouge, les étapes non. */
    override fun onMinute() = renderHeader(model())

    private fun render() {
        val list = model()
        renderHeader(list)
        steps.removeAllViews()
        steps.gravity = if (list.steps.isEmpty()) Gravity.CENTER else Gravity.TOP
        if (list.steps.isEmpty()) return renderEmpty()
        list.steps.forEachIndexed { i, step -> steps.addView(stepRow(i, step), LinearLayout.LayoutParams(MATCH, dp(ROW_DP)).apply { if (i > 0) topMargin = dp(4) }) }
    }

    private fun renderHeader(list: Checklist) {
        val now = System.currentTimeMillis()
        title.text = list.title.ifBlank { context.getString(R.string.checklist_default_title) }
        val text = countdownText(list.countdown(now))
        countdown.visibility = if (text == null) View.GONE else View.VISIBLE
        countdown.text = text
        countdown.contentDescription = text?.let { context.getString(R.string.checklist_countdown_cd, it) }
        countdown.setTextColor(color(if (list.urgent(now)) R.attr.c_ko else R.attr.c_text))

        progress.visibility = if (list.total == 0) View.GONE else View.VISIBLE
        (fill.layoutParams as LinearLayout.LayoutParams).weight = list.doneCount.toFloat()
        (rest.layoutParams as LinearLayout.LayoutParams).weight = (list.total - list.doneCount).toFloat()
        bar.requestLayout()
        fill.background.mutate().setTint(color(R.attr.c_ok))
        rest.background.mutate().setTint(color(R.attr.c_stroke))
        count.text = context.getString(R.string.checklist_progress, list.doneCount, list.total)
        progress.contentDescription = context.getString(R.string.checklist_progress_cd, list.doneCount, list.total)
        bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        count.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun countdownText(c: Countdown): String? = when (c) {
        Countdown.None -> null
        is Countdown.Days -> context.getString(R.string.checklist_days, c.days)
        is Countdown.Hours ->
            if (c.hours == 0) context.getString(R.string.checklist_minutes, c.minutes) else context.getString(R.string.checklist_hours, c.hours, c.minutes)
        Countdown.Passed -> context.getString(R.string.checklist_passed)
    }

    /** Aucune étape : l'invitation au milieu de la carte, le bouton ouvre la boîte de saisie. */
    private fun renderEmpty() {
        steps.addView(label(16f, R.attr.c_text_dim).apply {
            setText(R.string.checklist_empty)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        steps.addView(Button(context, null, 0, R.style.PrimaryButton).apply {
            setText(R.string.checklist_add)
            setOnClickListener { askText(R.string.checklist_add, R.string.checklist_step_hint, "") { text -> commit(model().add(text)); render() } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    /** Une étape : case ronde et libellé (barré quand elle est faite) ; toucher la ligne la coche ou la décoche. */
    private fun stepRow(index: Int, step: ChecklistStep): View {
        val check = TaskCheck(context).apply { setDone(step.done, animate = false) }
        val label = StrikeText(context).apply {
            text = step.text
            textSize = 17f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = themeFont(context, R.attr.c_font_regular)
            setStruck(step.done, animate = false)
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(check, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = -dp(12) })
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun style(done: Boolean) {
            label.setTextColor(color(if (done) R.attr.c_text_faint else R.attr.c_text))
            row.contentDescription = context.getString(if (done) R.string.checklist_step_done else R.string.checklist_step_todo, step.text)
        }
        style(step.done)
        Look.pressSpring(row)
        row.setOnClickListener {
            row.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val next = model().toggle(index)
            val done = next.steps.getOrNull(index)?.done ?: return@setOnClickListener
            commit(next)
            check.setDone(done, animate = true)
            label.setStruck(done, animate = true)
            style(done)
            renderHeader(next)
        }
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

        /** Ligne d'étape : cible tactile de 48 dp. */
        const val ROW_DP = 48
    }
}
