package com.unowhy.unomate.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.HttpException
import com.unowhy.unomate.net.PcNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Écran « Notifications » de Depuis l'ordi (view_notifications.xml) : l'historique des notifications de l'ordinateur
 * gardé par le compagnon (24 h, 100 au plus), la plus récente en haut. Une croix retire une notification, « Tout
 * effacer » vide l'historique (après confirmation) ; ni l'une ni l'autre ne touche au centre de notifications de
 * l'ordinateur. Lu à l'affichage de l'écran, sur Actualiser et à l'arrivée d'une notification, jamais en boucle. En
 * mode discret (Réglages), le texte des notifications n'est pas affiché.
 */
class NotificationsView(private val fragment: Fragment, root: View) {

    private val context = root.context
    private val list: ListView = root.findViewById(R.id.notif_list)
    private val status: TextView = root.findViewById(R.id.notif_status)
    private val clear: Button = root.findViewById(R.id.notif_clear)
    private val adapter = Adapter()

    private var readJob: Job? = null

    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope

    init {
        list.adapter = adapter
        root.findViewById<View>(R.id.notif_refresh).setOnClickListener { read() }
        clear.setOnClickListener { confirmClear() }
    }

    /** Écran affiché, Actualiser ou notification reçue : lit l'historique une fois (la liste reste affichée pendant ce temps). */
    fun read() {
        readJob?.cancel()
        if (adapter.isEmpty) showStatus(context.getString(R.string.notif_reading))
        readJob = scope.launch {
            try {
                show(BackendClient(context).notifications.list())
            } catch (e: Exception) {
                val message = notifError(e)
                show(emptyList())
                showStatus(message)
            }
        }
    }

    /** L'écran n'est plus affiché : la lecture en cours est oubliée. */
    fun stop() {
        readJob?.cancel()
    }

    private fun show(items: List<PcNotification>) {
        adapter.clear()
        adapter.addAll(items)
        clear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        showStatus(if (items.isEmpty()) context.getString(R.string.notif_empty) else null)
    }

    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Retire une notification de l'historique ; déjà partie (404) : retirée ici aussi. */
    private fun remove(n: PcNotification) {
        scope.launch {
            try {
                BackendClient(context).notifications.delete(n.id)
            } catch (e: Exception) {
                if (!(e is HttpException && e.code == 404)) {
                    Toast.makeText(context, notifError(e), Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            adapter.remove(n)
            if (adapter.isEmpty) show(emptyList())
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(context)
            .setTitle(R.string.notif_clear_title)
            .setMessage(R.string.notif_clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.notif_clear) { _, _ -> clearAll() }
            .showImmersive()
    }

    private fun clearAll() {
        // Écran recréé entre-temps (ordinateur changé) : plus rien à effacer ici.
        if (fragment.view == null) return
        readJob?.cancel()
        scope.launch {
            try {
                BackendClient(context).notifications.clear()
                show(emptyList())
            } catch (e: Exception) {
                Toast.makeText(context, notifError(e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Erreur d'une lecture ou d'un effacement : 501 = système non pris en charge ; 503 = notifications illisibles sur
     * l'ordinateur, avec la raison donnée par le compagnon (Mac sans accès complet au disque) ; le reste passe par
     * [errorText] (qui relance l'annulation).
     */
    private fun notifError(e: Exception): String = when {
        e is HttpException && e.code == 501 -> context.getString(R.string.notif_not_supported)
        e is HttpException && e.code == 503 -> e.reason.ifBlank { context.getString(R.string.notif_unavailable) }
        else -> fragment.errorText(e)
    }

    private inner class Adapter : ArrayAdapter<PcNotification>(context, 0) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: fragment.layoutInflater.inflate(R.layout.item_notification, parent, false)
            val n = getItem(position)!!
            view.findViewById<TextView>(R.id.notif_app).text = n.app
            view.findViewById<TextView>(R.id.notif_time).text = notificationTime(n.at, System.currentTimeMillis())
            view.findViewById<TextView>(R.id.notif_row_title).setTextOrGone(n.title.trim().ifEmpty { null })
            view.findViewById<TextView>(R.id.notif_body).setTextOrGone(notificationBody(n, Prefs.notificationsDiscreet(context)))
            view.findViewById<View>(R.id.notif_remove).setOnClickListener { remove(n) }
            return view
        }
    }

    private fun TextView.setTextOrGone(value: String?) {
        text = value
        visibility = if (value == null) View.GONE else View.VISIBLE
    }
}
