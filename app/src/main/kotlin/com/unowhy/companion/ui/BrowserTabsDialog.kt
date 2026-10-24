package com.unowhy.companion.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.BrowserTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * « Onglets » de la Page de l'ordi : onglets ouverts dans les navigateurs de l'ordinateur, celui qui y est affiché en
 * tête. Toucher un onglet l'affiche sur la tablette (`onPick`), sans rien changer sur l'ordinateur.
 */
class BrowserTabsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPick: (BrowserTab) -> Unit,
) {

    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        val body = root.findViewById<LinearLayout>(R.id.dialog_body)
        val message = root.findViewById<TextView>(R.id.dialog_message)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.web_tabs_title)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .create()
        message.setText(R.string.loading)
        dialog.showImmersive()
        scope.launch {
            try {
                val result = BackendClient(context).browserTabs()
                val tabs = result.tabs
                // Le premier onglet affiché est celui du navigateur le plus en avant : la page qu'on regarde sur l'ordi.
                val front = tabs.firstOrNull { it.active }
                message.setText(when {
                    !result.extension -> R.string.web_tabs_extension
                    tabs.isEmpty() -> R.string.web_tabs_none
                    else -> R.string.web_tabs_hint
                })
                for (tab in listOfNotNull(front) + tabs.filter { it !== front }) {
                    list.addView(row(list, tab, tab === front) {
                        dialog.dismiss()
                        onPick(tab)
                    })
                }
            } catch (e: Exception) {
                message.text = context.errorText(e)
            }
        }
    }

    private fun row(parent: LinearLayout, tab: BrowserTab, front: Boolean, onClick: () -> Unit): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_window, parent, false)
        val host = Uri.parse(tab.url).host?.removePrefix("www.")
        row.findViewById<TextView>(R.id.window_title).text = tab.title.ifBlank { host ?: tab.browser }
        row.findViewById<TextView>(R.id.window_meta).text = listOfNotNull(
            tab.browser,
            host,
            when {
                front -> context.getString(R.string.web_tab_front)
                tab.active -> context.getString(R.string.web_tab_shown)
                else -> null
            },
        ).joinToString(" · ")
        row.setOnClickListener { onClick() }
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * context.resources.displayMetrics.density).toInt()
        return row
    }
}
