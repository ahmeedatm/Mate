package app.mate.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.runCatchingCancellable
import app.mate.ui.home.TabletTemplate
import app.mate.ui.home.TabletTemplates
import kotlinx.coroutines.launch

/**
 * Templates (Réglages > Ordinateurs, et après le premier appairage) : la liste des templates ; en choisir un remplace les
 * profils, le bandeau, la barre et le thème par les siens (ui/home/TabletTemplates.kt), puis l'activité est recréée et
 * arrive sur l'Accueil. Pas de retour arrière : c'est un point de départ.
 */
class TemplatesDialog(private val activity: FragmentActivity) {

    /** Depuis les Réglages : confirmation avant de remplacer. */
    fun show() {
        list(R.string.template_title, cancelable = true) { confirm(it) }
    }

    /**
     * Après le premier appairage : un choix sans confirmation, Bureau en premier. `onResolved(applied)` dès que l'on a
     * choisi (`true`, l'activité va être recréée) ou gardé la config telle quelle (`false`, « Plus tard »).
     */
    fun showFirstUse(onResolved: (applied: Boolean) -> Unit) {
        list(R.string.template_first_title, cancelable = false, onSkip = { onResolved(false) }) {
            onResolved(true)
            apply(it)
        }
    }

    private fun list(title: Int, cancelable: Boolean, onSkip: (() -> Unit)? = null, onChoose: (TabletTemplate) -> Unit) {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8))
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(list) })
            .setCancelable(cancelable)
        if (onSkip != null) builder.setNegativeButton(R.string.template_later) { _, _ -> onSkip() } else builder.setNegativeButton(R.string.cancel, null)
        val dialog = builder.create()
        for (template in TabletTemplate.entries) {
            list.addCard(template.title, template.description) { dialog.dismiss(); onChoose(template) }
        }
        dialog.showImmersive()
    }

    private fun LinearLayout.addCard(title: Int, description: Int, onClick: () -> Unit) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
            addView(TextView(activity).apply {
                setText(title)
                textSize = 18f
                setTextColor(themeColor(activity, R.attr.c_text))
            })
            addView(TextView(activity).apply {
                setText(description)
                textSize = 14f
                setTextColor(themeColor(activity, R.attr.c_text_dim))
            })
            setOnClickListener { onClick() }
        }
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (childCount > 0) topMargin = activity.dp(8)
        })
    }

    private fun confirm(template: TabletTemplate) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.template_confirm_title, activity.getString(template.title)))
            .setMessage(R.string.template_confirm)
            .setPositiveButton(R.string.template_apply) { _, _ -> apply(template) }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .showImmersive()
    }

    /** Écrans optionnels (GitLab, Jira, Agents) selon ce que l'ordinateur propose ; injoignable : tous posés. */
    private fun apply(template: TabletTemplate) {
        // Une pose à la fois (ordre distant compris) : la lecture de l'ordinateur peut durer.
        if (TabletTemplates.busy) return
        TabletTemplates.busy = true
        activity.lifecycleScope.launch {
            try {
                val features = if (Computers.active(activity) == null) null else runCatchingCancellable { BackendClient(activity).deck.homeFeatures() }.getOrNull()
                val layout = template.layout({ activity.getString(it) }, activity.getString(R.string.profile_call), features)
                TabletTemplates.apply(activity, template, layout)
                activity.recreate()
            } finally {
                TabletTemplates.busy = false
            }
        }
    }
}
