package app.mate.ui.home

import android.content.Context
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.mate.R
import app.mate.net.BackendClient
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.themeColor
import kotlinx.coroutines.launch

/**
 * Réglage « Afficher quand je joue » d'un écran : la tablette passe sur cet écran quand une des applis cochées est au
 * premier plan de l'ordinateur ([app.mate.ui.GameSession]). Les applis viennent du catalogue de l'ordinateur, comme la
 * carte Applis ; celles déjà cochées restent listées même si l'ordinateur ne répond pas. Un seul écran de jeu : en
 * activer un autre déplace le réglage. `onSaved` : la disposition est modifiée, à enregistrer.
 */
class GameScreenDialog(
    private val context: Context,
    private val layout: HomeLayout,
    private val index: Int,
    private val name: String,
    private val onSaved: () -> Unit,
) {

    private val checked = layout.gameApps.toMutableSet()
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun show() {
        val isGameScreen = layout.gameProfile == index
        val enabled = SwitchCompat(context).apply {
            setText(R.string.game_show)
            textSize = 16f
            isChecked = isGameScreen
        }
        val hint = TextView(context).apply {
            setText(R.string.game_hint)
            textSize = 13f
            setTextColor(themeColor(context, R.attr.c_text_dim))
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
            addView(enabled)
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6); bottomMargin = context.dp(12) })
            addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(280)))
        }
        render(layout.gameApps.sortedBy { it.lowercase() }, loading = true)
        val loader = (context as? LifecycleOwner)?.lifecycleScope?.launch {
            try {
                render((BackendClient(context).deck.catalog().apps + checked).distinct().sortedBy { it.lowercase() }, loading = false)
            } catch (e: Exception) {
                render(checked.sortedBy { it.lowercase() }, loading = false, error = context.errorText(e))
            }
        }
        AlertDialog.Builder(context)
            .setOnDismissListener { loader?.cancel() }
            .setTitle(context.getString(R.string.game_title, name))
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                if (enabled.isChecked) layout.gameProfile = index else if (layout.gameProfile == index) layout.gameProfile = -1
                layout.gameApps.clear()
                layout.gameApps += checked.sortedBy { it.lowercase() }
                onSaved()
            }
            .show()
    }

    private fun render(apps: List<String>, loading: Boolean, error: String? = null) {
        rows.removeAllViews()
        val note = when {
            loading -> context.getString(R.string.loading)
            error != null -> error
            apps.isEmpty() -> context.getString(R.string.game_none)
            else -> null
        }
        note?.let { rows.addView(TextView(context).apply { text = it; textSize = 14f }) }
        apps.forEach { app ->
            rows.addView(
                CheckBox(context).apply {
                    text = app
                    textSize = 16f
                    isChecked = app in checked
                    setOnCheckedChangeListener { _, on -> if (on) checked += app else checked -= app }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)),
            )
        }
    }
}
