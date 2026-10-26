package com.unowhy.companion.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.LinkedCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * « Mes agendas » (Accueil) : agendas reliés à l'ordinateur actif, à délier depuis la tablette ; en relier un autre
 * ouvre la page de l'ordinateur (connexion au compte ou lien ICS à coller, impossible à taper sur la tablette).
 * `onChanged` : un agenda a été délié, l'Accueil relit l'agenda ; `onAdd` : ouvrir la page de l'ordinateur.
 */
class CalendarsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onAdd: () -> Unit,
) {

    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        val body = root.findViewById<LinearLayout>(R.id.dialog_body)
        val message = root.findViewById<TextView>(R.id.dialog_message)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.calendars_title)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.calendars_add) { _, _ -> onAdd() }
            .create()

        fun load() {
            message.setText(R.string.loading)
            scope.launch {
                try {
                    val calendars = BackendClient(context).linkedCalendars()
                    list.removeAllViews()
                    message.setText(if (calendars.isEmpty()) R.string.calendars_none else R.string.calendars_hint)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(if (calendars.isEmpty()) R.string.calendars_add_first else R.string.calendars_add)
                    for (calendar in calendars) list.addView(row(list, calendar) { confirmUnlink(calendar) { dialog.dismiss() } })
                } catch (e: Exception) {
                    message.text = context.errorText(e)
                }
            }
        }
        dialog.showImmersive()
        load()
    }

    private fun row(parent: LinearLayout, calendar: LinkedCalendar, onClick: () -> Unit): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_window, parent, false)
        row.findViewById<TextView>(R.id.window_title).text = calendar.label
        row.findViewById<TextView>(R.id.window_meta).text = listOfNotNull(
            calendar.detail,
            context.getString(R.string.calendars_expired).takeIf { calendar.expired },
            context.getString(R.string.calendars_tap_unlink).takeIf { calendar.removable },
        ).joinToString(" · ")
        if (calendar.removable) row.setOnClickListener { onClick() } else row.isClickable = false
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(8)
        return row
    }

    private fun confirmUnlink(calendar: LinkedCalendar, closeList: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.calendars_unlink_title, calendar.label))
            .setMessage(R.string.calendars_unlink_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.calendars_unlink) { _, _ ->
                scope.launch {
                    try {
                        BackendClient(context).unlinkCalendar(calendar.id)
                        closeList()
                        onChanged()
                        show()
                    } catch (e: Exception) {
                        AlertDialog.Builder(context).setMessage(context.errorText(e)).setPositiveButton(R.string.close, null).showImmersive()
                    }
                }
            }
            .showImmersive()
    }
}
