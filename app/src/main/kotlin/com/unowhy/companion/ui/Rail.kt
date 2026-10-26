package com.unowhy.companion.ui

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.unowhy.companion.Mode
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.net.Link

/** Barre latérale : un bouton par mode (pastille, temps du Focus), bouton Parler et pastille de connexion. */
class Rail(private val activity: Activity, onSelect: (Mode) -> Unit) {

    private class Item(val view: View, val label: TextView, val badge: TextView)

    private val items = mutableMapOf<Mode, Item>()
    private val statusDot: View = activity.findViewById(R.id.status_dot)
    private val statusName: TextView = activity.findViewById(R.id.status_name)
    private val status: View = activity.findViewById(R.id.status)

    /** Commandes vocales : sous les modes, au-dessus de l'état de la tablette. */
    val talk: View

    init {
        val bar = activity.findViewById<LinearLayout>(R.id.mode_bar)
        for (mode in Mode.entries) {
            val view = activity.layoutInflater.inflate(R.layout.item_rail, bar, false)
            view.findViewById<ImageView>(R.id.rail_icon).setImageResource(mode.icon)
            val label = view.findViewById<TextView>(R.id.rail_label).apply { setText(mode.label) }
            view.contentDescription = activity.getString(mode.label)
            view.setOnClickListener { onSelect(mode) }
            bar.addView(view)
            items[mode] = Item(view, label, view.findViewById(R.id.rail_badge))
        }
        val rail = activity.findViewById<LinearLayout>(R.id.rail)
        talk = activity.layoutInflater.inflate(R.layout.item_rail, rail, false)
        talk.findViewById<ImageView>(R.id.rail_icon).setImageResource(R.drawable.ic_mic)
        talk.findViewById<TextView>(R.id.rail_label).setText(R.string.voice_button)
        talk.contentDescription = activity.getString(R.string.voice_button)
        rail.addView(talk, rail.indexOfChild(bar) + 1)
    }

    fun select(mode: Mode, changed: Boolean) {
        items.forEach { (m, item) -> item.view.isSelected = m == mode }
        if (changed) items[mode]?.let { Look.pop(it.view.findViewById(R.id.rail_icon)) }
    }

    /** Temps restant du Pomodoro à la place du libellé Focus, visible depuis n'importe quel écran. */
    fun showFocus(state: FocusTimer.State) {
        val label = items.getValue(Mode.FOCUS).label
        val active = state.running || state.remainingMs < state.durationMs
        label.text = if (active) FocusTimer.format(state.remainingMs) else activity.getString(R.string.mode_focus)
    }

    /** Nombre de tâches non faites, en pastille sur l'icône Accueil. */
    fun setPendingTasks(count: Int) {
        val badge = items.getValue(Mode.HOME).badge
        badge.text = count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /** Pastille de connexion : couleur selon l'état, nom de l'ordinateur actif. */
    fun showLink(link: Link) {
        val color = when (link.state) {
            Link.State.ONLINE -> R.attr.c_ok
            Link.State.UNAUTHORIZED -> R.attr.c_warn
            else -> R.attr.c_ko
        }
        statusDot.background.mutate().setTint(themeColor(activity, color))
        statusName.text = link.computer?.name ?: activity.getString(R.string.no_computer_short)
        val state = when (link.state) {
            Link.State.ONLINE -> R.string.online
            Link.State.UNAUTHORIZED -> R.string.unauthorized
            Link.State.OFFLINE -> R.string.offline
            Link.State.NONE -> R.string.no_computer
        }
        status.contentDescription = activity.getString(state)
    }
}
