package app.mate.ui

import android.app.TimePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.DeckTile
import app.mate.net.ScheduleApi
import app.mate.net.ScheduleItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * « Programmations » (Réglages) : une touche du Deck lancée par l'ordinateur à heure fixe ou à intervalle, même
 * tablette éteinte. Liste (toucher une ligne : activer, désactiver, supprimer) et ajout en trois choix : la touche,
 * heure fixe ou intervalle, puis l'heure et les jours (ou l'intervalle).
 */
class ScheduleDialog(private val context: Context, private val scope: CoroutineScope) {

    private val api get() = BackendClient(context).schedule

    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        val message = root.findViewById<TextView>(R.id.dialog_message)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.findViewById<LinearLayout>(R.id.dialog_body).addView(list)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.schedule_title)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.schedule_add) { _, _ -> chooseTile() }
            .create()
        message.setText(R.string.loading)
        scope.launch {
            try {
                val items = api.list()
                message.setText(if (items.isEmpty()) R.string.schedule_none else R.string.schedule_hint)
                for (item in items) list.addView(row(list, item) { choose(item) { dialog.dismiss() } })
            } catch (e: Exception) {
                message.text = context.errorText(e)
            }
        }
        dialog.showImmersive()
    }

    private fun row(parent: LinearLayout, item: ScheduleItem, onClick: () -> Unit): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_window, parent, false)
        row.findViewById<TextView>(R.id.window_title).text = item.label ?: context.getString(R.string.schedule_tile_gone)
        row.findViewById<TextView>(R.id.window_meta).text =
            listOfNotNull(item.schedule, context.getString(R.string.schedule_off).takeIf { !item.enabled }).joinToString(" · ")
        row.setOnClickListener { onClick() }
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(8)
        return row
    }

    /** Activer ou désactiver, supprimer : puis la liste se rouvre à jour. */
    private fun choose(item: ScheduleItem, closeList: () -> Unit) {
        val toggle = context.getString(if (item.enabled) R.string.schedule_disable else R.string.schedule_enable)
        AlertDialog.Builder(context)
            .setTitle(item.label ?: context.getString(R.string.schedule_tile_gone))
            .setItems(arrayOf(toggle, context.getString(R.string.delete))) { _, which ->
                if (which == 0) change(closeList) { api.setEnabled(item.id, !item.enabled) } else confirmDelete(item, closeList)
            }
            .setNegativeButton(R.string.cancel, null)
            .showImmersive()
    }

    private fun confirmDelete(item: ScheduleItem, closeList: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.schedule_delete_title)
            .setMessage(item.schedule)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> change(closeList) { api.delete(item.id) } }
            .showImmersive()
    }

    private fun change(closeList: () -> Unit, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                closeList()
                show()
            } catch (e: Exception) {
                AlertDialog.Builder(context).setMessage(context.errorText(e)).setPositiveButton(R.string.close, null).showImmersive()
            }
        }
    }

    private fun chooseTile() {
        scope.launch {
            try {
                // Les touches à maintenir ne se programment pas (le compagnon les refuse).
                val tiles = BackendClient(context).deck.pages().flatMap { page -> page.tiles.filter { it.label.isNotBlank() && !it.push }.map { page.title to it } }
                    .distinctBy { it.second.id }
                if (tiles.isEmpty()) {
                    AlertDialog.Builder(context).setMessage(R.string.schedule_no_tile).setPositiveButton(R.string.close, null).showImmersive()
                    return@launch
                }
                AlertDialog.Builder(context)
                    .setTitle(R.string.schedule_pick_tile)
                    .setItems(tiles.map { (page, tile) -> "$page · ${tile.label}" }.toTypedArray()) { _, i -> chooseKind(tiles[i].second) }
                    .setNegativeButton(R.string.cancel, null)
                    .showImmersive()
            } catch (e: Exception) {
                AlertDialog.Builder(context).setMessage(context.errorText(e)).setPositiveButton(R.string.close, null).showImmersive()
            }
        }
    }

    private fun chooseKind(tile: DeckTile) {
        AlertDialog.Builder(context)
            .setTitle(tile.label)
            .setItems(arrayOf(context.getString(R.string.schedule_kind_time), context.getString(R.string.schedule_kind_interval))) { _, which ->
                if (which == 0) chooseTime(tile) else chooseInterval(tile)
            }
            .setNegativeButton(R.string.cancel, null)
            .showImmersive()
    }

    private fun chooseTime(tile: DeckTile) {
        TimePickerDialog(context, { _, hour, minute -> chooseDays(tile, ScheduleApi.hhmm(hour, minute)) }, 18, 0, true).showImmersive()
    }

    private fun chooseDays(tile: DeckTile, at: String) {
        val presets = listOf(
            context.getString(R.string.schedule_days_all) to emptyList(),
            context.getString(R.string.schedule_days_weekdays) to listOf(1, 2, 3, 4, 5),
            context.getString(R.string.schedule_days_weekend) to listOf(6, 7),
        )
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.schedule_days_title, at))
            .setItems(presets.map { it.first }.toTypedArray()) { _, i -> save { api.add(tile.id, at = at, days = presets[i].second) } }
            .setNegativeButton(R.string.cancel, null)
            .showImmersive()
    }

    private fun chooseInterval(tile: DeckTile) {
        AlertDialog.Builder(context)
            .setTitle(R.string.schedule_interval_title)
            .setItems(ScheduleApi.INTERVALS.map { intervalLabel(it) }.toTypedArray()) { _, i ->
                save { api.add(tile.id, everyMinutes = ScheduleApi.INTERVALS[i]) }
            }
            .setNegativeButton(R.string.cancel, null)
            .showImmersive()
    }

    private fun intervalLabel(minutes: Int) = when {
        minutes % 60 == 0 -> context.getString(R.string.schedule_every_hours, minutes / 60)
        minutes > 60 -> context.getString(R.string.schedule_every_hours_minutes, minutes / 60, minutes % 60)
        else -> context.getString(R.string.schedule_every, minutes)
    }

    private fun save(action: suspend () -> Unit) = change({}, action)
}
