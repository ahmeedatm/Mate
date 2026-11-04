package app.mate.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.MainActivity
import app.mate.R
import app.mate.gitlab.MAX_SCHEDULES
import app.mate.gitlab.checkedStillListed
import app.mate.gitlab.scheduleRefsInOrder
import app.mate.net.BackendClient
import app.mate.net.ScheduleRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Choix des planifications de pipelines du widget Pipelines (⚙) : celles des projets GitLab dont l'utilisateur est membre,
 * groupées par projet, cases à cocher. « Enregistrer » envoie le choix à l'ordinateur, qui le garde, puis appelle `onSaved`.
 */
class ScheduleChoicesDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {
    private var dialog: AlertDialog? = null

    fun show() {
        if (dialog != null) return
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        val body = root.findViewById<LinearLayout>(R.id.dialog_body)
        val message = root.findViewById<TextView>(R.id.dialog_message)
        message.setText(R.string.loading)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        val d = AlertDialog.Builder(context)
            .setTitle(R.string.schedules_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog = d
        d.setOnDismissListener { dialog = null }
        d.showImmersive()
        val save = d.getButton(AlertDialog.BUTTON_POSITIVE)
        save.isEnabled = false
        scope.launch {
            try {
                val result = BackendClient(context).gitlab.scheduleChoices()
                if (!result.connected) return@launch message.setText(R.string.gitlab_connect_text)
                if (result.projects.isEmpty()) return@launch message.setText(R.string.schedules_none)
                val checked = checkedStillListed(result.projects, result.selected).toMutableSet()
                val boxes = mutableListOf<Pair<CheckBox, ScheduleRef>>()
                fun update() {
                    message.text = context.getString(R.string.schedules_hint, checked.size, MAX_SCHEDULES)
                    boxes.forEach { (box, ref) -> box.isEnabled = ref in checked || checked.size < MAX_SCHEDULES }
                }
                for (project in result.projects) {
                    list.addView(TitleView(context).apply { text = project.project; setPadding(0, context.dp(12), 0, context.dp(4)) })
                    for (schedule in project.schedules) {
                        val ref = ScheduleRef(project.project, schedule.id)
                        val label = "${schedule.description.ifEmpty { schedule.ref }} · ${schedule.ref}"
                        val box = CheckBox(context).apply {
                            text = if (schedule.active) label else context.getString(R.string.schedules_inactive, label)
                            textSize = 17f
                            minHeight = context.dp(44)
                            isChecked = ref in checked
                            setOnCheckedChangeListener { _, on ->
                                if (on) checked += ref else checked -= ref
                                update()
                            }
                        }
                        boxes += box to ref
                        list.addView(box)
                    }
                }
                update()
                save.isEnabled = true
                save.setOnClickListener {
                    val refs = scheduleRefsInOrder(result.projects, checked)
                    d.dismiss()
                    scope.launch {
                        try {
                            BackendClient(context).gitlab.saveSchedules(refs)
                            onSaved()
                        } catch (e: Exception) {
                            (context as? MainActivity)?.showBanner(context.errorText(e), 6)
                        }
                    }
                }
            } catch (e: Exception) {
                message.text = context.errorText(e)
            }
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}
