package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.TaskItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Carte « Tâches du jour » de l'Accueil, stockées sur le Mac : quotidiennes (config/tasks.json,
 * ex. le worklog Jira, cochée toute seule quand la journée est saisie) et ponctuelles (ajoutées ici ou par Claude).
 */
class TasksCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val list: LinearLayout
    private val count: TextView
    private val status: TextView
    private val input: EditText

    private lateinit var scope: CoroutineScope
    private var onPending: (Int) -> Unit = {}

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_tasks_card, this, true)
        list = findViewById(R.id.tasks_list)
        count = findViewById(R.id.tasks_count)
        status = findViewById(R.id.tasks_status)
        input = findViewById(R.id.tasks_input)
        findViewById<ImageButton>(R.id.tasks_add).setOnClickListener { add() }
        findViewById<ImageButton>(R.id.tasks_refresh).setOnClickListener { launch { load(refresh = true) } }
        input.setOnEditorActionListener { _, actionId, event ->
            val hardwareEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || hardwareEnter) { add(); true } else false
        }
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
            showStatus(context.getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun client() = BackendClient(context)

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun add() {
        val label = input.text.toString().trim()
        if (label.isEmpty()) return
        input.text.clear()
        launch {
            client().addTask(label)
            load()
        }
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
            detail.text = task.detail ?: if (task.daily) context.getString(R.string.task_daily) else ""
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
                visibility = if (task.daily) View.GONE else View.VISIBLE
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
