package app.mate.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.ShoppingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Widget « Courses » : la liste d'articles de l'ordinateur, cochée d'un toucher (les cochés descendent sous les autres).
 * Même gabarit que la carte Tâches. Toucher le compteur relit la liste, la dernière ligne supprime les articles cochés (après confirmation), toucher un
 * article ouvre sa suppression, « + Ajouter un article » ouvre le champ. L'ajout à la voix passe par `VoiceCommand.AddShopping`.
 */
class ShoppingCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val list: LinearLayout
    private val count: TextView
    private val status: TextView
    private var doneCount = 0

    private lateinit var scope: CoroutineScope

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_tasks_cadran, this, true)
        list = findViewById(R.id.tasks_list)
        count = findViewById(R.id.tasks_count)
        status = findViewById(R.id.tasks_status)
        findViewById<TextView>(R.id.tasks_title).setText(R.string.widget_shopping)
        findViewById<TextView>(R.id.tasks_add_row).setText(R.string.shopping_add)
        findViewById<View>(R.id.tasks_add_row).setOnClickListener { askNewItem() }
        count.contentDescription = context.getString(R.string.shopping_reload)
        count.setOnClickListener { launch { load() } }
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Dernière lecture en échec, et liste déjà affichée (elle reste à l'écran pendant une coupure). */
    private var failed = false
    private var hasRead = false

    /** L'ordinateur ne répond plus et l'Accueil le dit ailleurs : pas de message ici. */
    var quiet = false
        set(value) {
            field = value
            if (value && failed) showStatus(if (hasRead) null else context.getString(R.string.shopping_unavailable))
        }

    suspend fun load() {
        try {
            val items = client().shopping.list()
            Computers.active(context)?.let { lastRead[it.id] = items }
            render(items)
            failed = false
        } catch (e: Exception) {
            failed = true
            if (!hasRead) Computers.active(context)?.let { lastRead[it.id] }?.let { render(it) }
            if (!hasRead) showStatus(context.getString(R.string.shopping_unavailable)) else if (!quiet) showStatus(context.errorText(e))
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

    private fun askNewItem() {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setHint(R.string.shopping_hint)
        val box = LinearLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_shopping)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.shopping_add_confirm) { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotEmpty()) launch { client().shopping.add(listOf(label)); load() }
            }
            .create()
            .showForTyping(input)
    }

    private fun confirmClear() {
        AlertDialog.Builder(context)
            .setTitle(context.resources.getQuantityString(R.plurals.shopping_clear_title, doneCount, doneCount))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> launch { client().shopping.clearDone(); load() } }
            .create()
            .showImmersive()
    }

    /** Liste affichée : une relecture identique ne reconstruit pas les lignes (ni l'animation d'apparition). */
    private var shown: List<ShoppingItem>? = null

    private fun render(items: List<ShoppingItem>) {
        hasRead = true
        if (items == shown) return
        shown = items
        doneCount = items.count { it.done }
        count.text = when {
            items.isEmpty() -> ""
            doneCount == items.size -> context.getString(R.string.shopping_all_done)
            else -> context.resources.getQuantityString(R.plurals.shopping_remaining, items.size - doneCount, items.size - doneCount)
        }
        showStatus(if (items.isEmpty()) context.getString(R.string.shopping_empty) else null)
        list.removeAllViews()
        if (items.isNotEmpty()) Look.revealOnce(list)
        items.sortedBy { it.done }.forEach { list.addView(itemRow(it)) }
        if (doneCount > 0) list.addView(clearRow())
    }

    /** Dernière ligne quand des articles sont cochés : les supprime après confirmation. */
    private fun clearRow() = TextView(context).apply {
        setText(R.string.shopping_clear_row)
        textSize = 16f
        setTextColor(themeColor(context, R.attr.c_text_faint))
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setOnClickListener { confirmClear() }
    }

    private fun itemRow(item: ShoppingItem): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_task_cadran, list, false)
        val check = row.findViewById<TaskCheck>(R.id.task_check)
        val label = row.findViewById<StrikeText>(R.id.task_label)
        row.findViewById<ImageButton>(R.id.task_open).visibility = View.GONE
        label.text = item.label
        check.contentDescription = item.label
        fun paint(done: Boolean, animate: Boolean) {
            check.setDone(done, animate)
            label.setStruck(done, animate)
            label.setTextColor(themeColor(context, if (done) R.attr.c_text_faint else R.attr.c_text))
        }
        var done = item.done
        paint(done, animate = false)
        check.setOnClickListener {
            done = !done
            paint(done, animate = true)
            launch {
                try {
                    client().shopping.setDone(item.id, done)
                } catch (e: Exception) {
                    load()
                    throw e
                }
                // Relue après l'animation de la coche : le coché passe sous les autres.
                row.postDelayed({ if (row.parent === list) scope.launch { load() } }, SORT_DELAY_MS)
            }
        }
        row.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(item.label)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> launch { client().shopping.delete(item.id); load() } }
                .create()
                .showImmersive()
        }
        return row
    }

    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private companion object {
        const val SORT_DELAY_MS = 650L

        /** Dernière liste lue par ordinateur, pour un écran recréé pendant une coupure. */
        val lastRead = HashMap<String, List<ShoppingItem>>()
    }
}
