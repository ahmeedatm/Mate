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
import app.mate.net.checkedBoardIds
import app.mate.net.groupBoardsByProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Choix des tableaux Jira de l'onglet Jira : tableaux groupés par projet, cases à cocher. « Enregistrer » envoie les ids
 * dans l'ordre de la liste (le premier coché donne l'ordre des colonnes) puis appelle `onSaved`.
 */
class JiraBoardsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {
    private var dialog: AlertDialog? = null

    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        val body = root.findViewById<LinearLayout>(R.id.dialog_body)
        val message = root.findViewById<TextView>(R.id.dialog_message)
        message.setText(R.string.loading)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        val d = AlertDialog.Builder(context)
            .setTitle(R.string.jira_boards_title)
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
                val result = BackendClient(context).jira.boards()
                val checked = result.selected.toMutableSet()
                val boxes = mutableListOf<Pair<CheckBox, Int>>()
                fun update() {
                    message.text = context.getString(R.string.jira_boards_hint, checked.size, MAX_BOARDS)
                    boxes.forEach { (box, id) -> box.isEnabled = id in checked || checked.size < MAX_BOARDS }
                    save.isEnabled = checked.isNotEmpty()
                }
                if (result.boards.isEmpty()) return@launch message.setText(R.string.jira_boards_none)
                for ((project, boards) in groupBoardsByProject(result.boards)) {
                    list.addView(TitleView(context).apply { text = project; setPadding(0, context.dp(12), 0, context.dp(4)) })
                    for (board in boards) {
                        val box = CheckBox(context).apply {
                            text = board.name
                            textSize = 17f
                            minHeight = context.dp(44)
                            isChecked = board.id in checked
                            setOnCheckedChangeListener { _, on ->
                                if (on) checked += board.id else checked -= board.id
                                update()
                            }
                        }
                        boxes += box to board.id
                        list.addView(box)
                    }
                }
                update()
                save.setOnClickListener {
                    val ids = checkedBoardIds(result.boards, checked)
                    d.dismiss()
                    scope.launch {
                        try {
                            BackendClient(context).jira.saveBoards(ids)
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

    val isShowing: Boolean get() = dialog != null

    private companion object {
        const val MAX_BOARDS = 10
    }
}
