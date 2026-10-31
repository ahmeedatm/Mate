package app.mate.ui

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.TaskItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Carte « Tâches du jour » de l'Accueil, stockées sur l'ordinateur : récurrentes (créées ici avec ⏰, ou dans
 * config/tasks.json comme le worklog Jira, cochée toute seule quand la journée est saisie) et ponctuelles (ici ou
 * par Claude), avec rappel parlé. 🔁 : toutes les récurrentes, même celles qui ne tombent pas aujourd'hui.
 * Console : en-tête « Tâches / N restantes », cases rondes, pas de croix par ligne (toucher la ligne ouvre la
 * suppression ; l'appui long est pris par le mode Modifier de l'Accueil), toucher « Tâches » ouvre les récurrentes,
 * toucher le compteur relit les tâches, « + Nouvelle tâche » en dernière ligne.
 */
class TasksCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val list: LinearLayout
    private val count: TextView
    private val status: TextView
    private var pending = 0

    private lateinit var scope: CoroutineScope
    private var onPending: (Int) -> Unit = {}

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_tasks_cadran, this, true)
        list = findViewById(R.id.tasks_list)
        count = findViewById(R.id.tasks_count)
        status = findViewById(R.id.tasks_status)
        // Champ et + : fenêtre de tâche en haut de l'écran, au-dessus du clavier (texte, puis répétition et rappel).
        val newTask = View.OnClickListener { TaskDialog(context) { label, remindAt, days -> add(label, remindAt, days) }.show("") }
        findViewById<View>(R.id.tasks_add_row).setOnClickListener(newTask)
        findViewById<View>(R.id.tasks_title).setOnClickListener { showRecurring() }
        count.setOnClickListener { launch { load(refresh = true) } }
    }

    /** [onPending] reçoit le nombre de tâches non faites (badge de la barre latérale). */
    fun attach(scope: CoroutineScope, onPending: (Int) -> Unit) {
        this.scope = scope
        this.onPending = onPending
    }

    /** Dernière lecture en échec. */
    private var failed = false

    /** Une liste est affichée (lue ici, ou la dernière lecture de l'ordinateur). */
    private var hasRead = false

    /** L'ordinateur ne répond plus et l'Accueil le dit ailleurs : pas de message ici. */
    var quiet = false
        set(value) {
            field = value
            if (value && failed) showStatus(if (hasRead) null else context.getString(R.string.tasks_unavailable))
        }

    suspend fun load(refresh: Boolean = false) {
        try {
            val tasks = client().tasks.list(refresh)
            Computers.active(context)?.let { lastRead[it.id] = tasks }
            render(tasks)
            failed = false
        } catch (e: Exception) {
            failed = true
            // Les tâches déjà lues restent affichées (écran recréé : la dernière lecture de cet ordinateur).
            if (!hasRead) Computers.active(context)?.let { lastRead[it.id] }?.let { render(it) }
            if (!hasRead) showStatus(context.getString(R.string.tasks_unavailable)) else if (!quiet) showStatus(context.errorText(e))
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
            val schedule = client().tasks.add(label, remindAt, days)
            // Une tâche « lun–ven » créée un samedi n'apparaît pas aujourd'hui : on dit qu'elle est bien enregistrée.
            if (schedule != null) Toast.makeText(context, context.getString(R.string.task_added, schedule), Toast.LENGTH_LONG).show()
            load()
        }
    }

    /** Liste de toutes les tâches récurrentes, avec ✕ pour supprimer celles créées depuis la tablette. */
    private fun showRecurring() = launch {
        val tasks = client().tasks.recurring()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = dp(24)
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
                        client().tasks.delete(task.id)
                        box.removeView(row)
                        load()
                    }
                }
            }
            box.addView(row)
        }
        dialog.showImmersive()
    }

    private fun render(tasks: List<TaskItem>) {
        hasRead = true
        pending = tasks.count { !it.done }
        onPending(pending)
        showCount(tasks.isNotEmpty())
        showStatus(if (tasks.isEmpty()) context.getString(R.string.tasks_empty) else null)

        list.removeAllViews()
        if (tasks.isNotEmpty()) Look.revealOnce(list)
        tasks.sortedBy { it.done }.forEach { list.addView(taskRow(it)) }
    }

    private fun showCount(hasTasks: Boolean) {
        count.text = when {
            !hasTasks -> ""
            pending == 0 -> context.getString(R.string.tasks_all_done)
            else -> context.resources.getQuantityString(R.plurals.tasks_remaining, pending, pending)
        }
    }

    /** Une ligne : cocher dessine la coche et la barre, puis la tâche descend sous les autres. */
    private fun taskRow(task: TaskItem): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_task_cadran, list, false)
        val check = row.findViewById<TaskCheck>(R.id.task_check)
        val label = row.findViewById<StrikeText>(R.id.task_label)
        label.text = task.label
        fun paint(done: Boolean, animate: Boolean) {
            check.setDone(done, animate)
            label.setStruck(done, animate)
            label.setTextColor(themeColor(context, if (done) R.attr.c_text_faint else R.attr.c_text))
        }
        var done = task.done
        paint(done, animate = false)
        if (!task.auto) check.setOnClickListener {
            done = !done
            paint(done, animate = true)
            toggle(task, row, done)
        }
        val detail = row.findViewById<TextView>(R.id.task_detail).apply {
            text = task.detail ?: task.schedule
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        // Carte étroite (Vertical, une colonne) : le libellé d'abord ; le détail (« Lun-ven · 17:30 ») ne le réduit pas à « R… ».
        if (!detail.text.isNullOrEmpty()) {
            row.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val shown = right - left >= context.dp(NARROW_ROW_DP)
                if (detail.isVisible != shown) row.post { detail.isVisible = shown }
            }
        }
        row.findViewById<ImageButton>(R.id.task_open).apply {
            visibility = if (task.hasUrl) View.VISIBLE else View.GONE
            setOnClickListener {
                launch {
                    client().tasks.openOnComputer(task.id)
                    Toast.makeText(context, R.string.opened_on_computer, Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (task.deletable) row.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(task.label)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    launch {
                        client().tasks.delete(task.id)
                        load()
                    }
                }
                .create()
                .showImmersive()
        }
        return row
    }

    /** Enregistre la coche ; la liste n'est pas relue (elle casserait l'animation), sauf en cas d'échec ou pour décocher. */
    private fun toggle(task: TaskItem, row: View, done: Boolean) {
        pending += if (done) -1 else 1
        onPending(pending)
        showCount(hasTasks = true)
        launch {
            try {
                client().tasks.setDone(task.id, done)
            } catch (e: Exception) {
                load()
                throw e
            }
            if (!done) load() else row.postDelayed({ if (row.parent === list) sinkToBottom(row) }, SINK_DELAY_MS)
        }
    }

    /** La tâche faite passe en bas ; les autres montent d'un cran en ressort (0,9 / 700), décalées de 20 ms. */
    private fun sinkToBottom(row: View) {
        val rows = (0 until list.childCount).map { list.getChildAt(it) }
        val before = rows.associateWith { it.top }
        list.removeView(row)
        list.addView(row)
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                rows.forEachIndexed { i, view ->
                    val shift = (before.getValue(view) - view.top).toFloat()
                    if (shift == 0f) return@forEachIndexed
                    view.translationY = shift
                    view.postDelayed(
                        { Look.springTo(view, DynamicAnimation.TRANSLATION_Y, Look.SPATIAL_DEFAULT, 0f) },
                        if (view === row) 0L else 20L * i,
                    )
                }
                return true
            }
        })
    }

    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private companion object {
        const val SINK_DELAY_MS = 650L

        /** Largeur de ligne sous laquelle le détail d'une tâche se cache (carte Vertical : ~270 dp). */
        const val NARROW_ROW_DP = 340

        /** Console : dernière liste lue par ordinateur, pour un écran recréé pendant une coupure. */
        val lastRead = HashMap<String, List<TaskItem>>()
    }
}
