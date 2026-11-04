package app.mate.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.MainActivity
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.JiraColumnChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Réglage des colonnes du widget Jira (⚙) : pour chaque colonne des tableaux choisis, « afficher » et « tous les tickets ».
 * « Enregistrer » envoie le choix à l'ordinateur, qui le garde, puis appelle `onSaved`.
 */
class JiraColumnsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {
    private var dialog: AlertDialog? = null

    val isShowing: Boolean get() = dialog != null

    fun show() {
        if (dialog != null) return
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        val body = root.findViewById<LinearLayout>(R.id.dialog_body)
        val message = root.findViewById<TextView>(R.id.dialog_message)
        message.setText(R.string.loading)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        val d = AlertDialog.Builder(context)
            .setTitle(R.string.jira_columns_title)
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
                val result = BackendClient(context).jira.columnChoices()
                if (!result.boardsChosen) return@launch message.setText(R.string.jira_columns_no_boards)
                message.setText(R.string.jira_columns_hint)
                val choices = result.columns.toMutableList()
                for ((i, column) in choices.withIndex()) {
                    val everyone = CheckBox(context).apply {
                        setText(R.string.jira_columns_everyone)
                        textSize = 15f
                        minHeight = context.dp(40)
                        isChecked = column.everyone
                        isEnabled = column.shown
                        setPadding(paddingLeft + context.dp(32), paddingTop, paddingRight, paddingBottom)
                        setOnCheckedChangeListener { _, on -> choices[i] = choices[i].copy(everyone = on) }
                    }
                    val shown = CheckBox(context).apply {
                        text = column.title
                        textSize = 17f
                        minHeight = context.dp(44)
                        isChecked = column.shown
                        setOnCheckedChangeListener { _, on ->
                            choices[i] = choices[i].copy(shown = on)
                            everyone.isEnabled = on
                        }
                    }
                    list.addView(shown)
                    list.addView(everyone)
                }
                save.isEnabled = true
                save.setOnClickListener {
                    val saved: List<JiraColumnChoice> = choices.toList()
                    d.dismiss()
                    scope.launch {
                        try {
                            BackendClient(context).jira.saveColumnChoices(saved)
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
