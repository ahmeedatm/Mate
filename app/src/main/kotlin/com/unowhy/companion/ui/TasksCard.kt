package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.TaskItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Carte « Tâches du jour » de l'Accueil, stockées sur l'ordinateur : récurrentes (créées ici avec ⏰, ou dans
 * config/tasks.json comme le worklog Jira, cochée toute seule quand la journée est saisie) et ponctuelles (ici ou
 * par Claude), avec rappel parlé. 🔁 : toutes les récurrentes, même celles qui ne tombent pas aujourd'hui.
 */
class TasksCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val list: LinearLayout
    private val count: TextView
    private val status: TextView
    private val input: TextView

    private lateinit var scope: CoroutineScope
    private var onPending: (Int) -> Unit = {}

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_tasks_card, this, true)
        list = findViewById(R.id.tasks_list)
        count = findViewById(R.id.tasks_count)
        status = findViewById(R.id.tasks_status)
        input = findViewById(R.id.tasks_input)
        // Champ et + : fenêtre de tâche en haut de l'écran, au-dessus du clavier (texte, puis répétition et rappel).
        val newTask = View.OnClickListener { TaskDialog(context) { label, remindAt, days -> add(label, remindAt, days) }.show("") }
        input.setOnClickListener(newTask)
        findViewById<ImageButton>(R.id.tasks_add).setOnClickListener(newTask)
        findViewById<ImageButton>(R.id.tasks_recurring).setOnClickListener { showRecurring() }
        findViewById<ImageButton>(R.id.tasks_refresh).setOnClickListener { launch { load(refresh = true) } }
    }

    /** [onPending] reçoit le nombre de tâches non faites (badge de la barre latérale). */
    fun attach(scope: CoroutineScope, onPending: (Int) -> Unit) {
        this.scope = scope
        this.onPending = onPending
    }

    suspend fun load(refresh: Boolean = false) {
        try {
            render(client().tasks(refresh))
        } catch (e: Exception) {
            list.removeAllViews()
            count.text = ""
            showStatus(context.errorText(e))
        }
    }

    private fun client() = BackendClient(context)

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Toast.makeText(context, context.errorText(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun add(label: String, remindAt: String?, days: List<Int>?) {
        launch {
            val schedule = client().addTask(label, remindAt, days)
            // Une tâche « lun–ven » créée un samedi n'apparaît pas aujourd'hui : on dit qu'elle est bien enregistrée.
            if (schedule != null) Toast.makeText(context, context.getString(R.string.task_added, schedule), Toast.LENGTH_LONG).show()
            load()
        }
    }

    /** Liste de toutes les tâches récurrentes, avec ✕ pour supprimer celles créées depuis la tablette. */
    private fun showRecurring() = launch {
        val tasks = client().recurringTasks()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.tasks_recurring)
            .setView(box)
            .setPositiveButton(R.string.close, null)
            .create()
        if (tasks.isEmpty()) box.addView(TextView(context).apply { setText(R.string.tasks_recurring_empty); textSize = 16f })
        for (task in tasks) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_task, box, false)
            row.findViewById<TextView>(R.id.task_label).text = task.label
            row.findViewById<TextView>(R.id.task_detail).text = task.schedule
            row.findViewById<CheckBox>(R.id.task_check).visibility = View.GONE
            row.findViewById<ImageButton>(R.id.task_open).visibility = View.GONE
            row.findViewById<ImageButton>(R.id.task_delete).apply {
                visibility = if (task.deletable) View.VISIBLE else View.GONE
                setOnClickListener {
                    launch {
                        client().deleteTask(task.id)
                        box.removeView(row)
                        load()
                    }
                }
            }
            box.addView(row)
        }
        dialog.show()
    }

    private fun render(tasks: List<TaskItem>) {
        val pending = tasks.count { !it.done }
        onPending(pending)
        count.text = when {
            tasks.isEmpty() -> ""
            pending == 0 -> context.getString(R.string.tasks_all_done)
            else -> context.getString(R.string.tasks_left, pending)
        }
        count.setTextColor(ContextCompat.getColor(context, if (pending > 0) R.color.warn else R.color.ok))
        showStatus(if (tasks.isEmpty()) context.getString(R.string.tasks_empty) else null)

        list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        // Les tâches à faire d'abord, l'ordre du Mac est gardé à l'intérieur de chaque groupe.
        for (task in tasks.sortedBy { it.done }) {
            val row = inflater.inflate(R.layout.item_task, list, false)
            val label = row.findViewById<TextView>(R.id.task_label)
            label.text = task.label
            label.setTextColor(ContextCompat.getColor(context, if (task.done) R.color.text_faint else R.color.text))
            label.paintFlags = if (task.done) label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            val detail = row.findViewById<TextView>(R.id.task_detail)
            detail.text = task.detail ?: task.schedule ?: if (task.daily) context.getString(R.string.task_daily) else ""
            detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE

            row.findViewById<CheckBox>(R.id.task_check).apply {
                isChecked = task.done
                isEnabled = !task.auto
                setOnCheckedChangeListener { _, checked ->
                    launch {
                        client().setTaskDone(task.id, checked)
                        load()
                    }
                }
            }
            row.findViewById<ImageButton>(R.id.task_open).apply {
                visibility = if (task.hasUrl) View.VISIBLE else View.GONE
                setOnClickListener {
                    launch {
                        client().openTaskOnMac(task.id)
                        Toast.makeText(context, R.string.opened_on_mac, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            row.findViewById<ImageButton>(R.id.task_delete).apply {
                visibility = if (task.deletable) View.VISIBLE else View.GONE
                setOnClickListener {
                    launch {
                        client().deleteTask(task.id)
                        load()
                    }
                }
            }
            list.addView(row)
        }
    }

    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}
