package com.unowhy.companion.ui

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.unowhy.companion.Mode
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.net.Link
import com.unowhy.companion.ui.home.HomeLayout

/**
 * Barre fixe à gauche (Deck V2) : Parler en tête, les profils (Accueil, Deck, ceux de l'utilisateur, Réunion pendant
 * un appel), « Plus » au-delà de [RailModel.MAX_PROFILES], les outils (Focus, Ordi, Caméra), puis en pied la cloche,
 * l'ordinateur et l'état de la tablette (toucher : Réglages). Jamais cachée. Ce qu'elle montre vient de [RailModel] ;
 * la pastille de l'élément choisi glisse d'une entrée à l'autre sur un ressort qui joue aussi en kiosque.
 */
class ProfileRail(
    private val activity: Activity,
    private val onMode: (Mode) -> Unit,
    private val onProfile: (Int) -> Unit,
    private val onProfiles: () -> Unit,
) : Navigation {

    private val rail: LinearLayout = activity.findViewById(R.id.rail)
    private val bar: LinearLayout = activity.findViewById(R.id.mode_bar)
    private val indicator: View = activity.findViewById(R.id.rail_indicator)
    private val tabletStatus: View = activity.findViewById(R.id.tablet_status)
    private val statusDot: View = activity.findViewById(R.id.status_dot)
    private val statusName: TextView = activity.findViewById(R.id.status_name)
    override val status: View = activity.findViewById(R.id.status)
    override val talk: View = activity.layoutInflater.inflate(R.layout.item_rail_talk, rail, false).also { rail.addView(it, 0) }

    private var layout = HomeLayout.load(activity, cadran = true)
    private var page = layout.homeProfile
    private var inCall = false
    private var mode = Mode.HOME
    private var entries: List<RailEntry> = emptyList()
    private val views = mutableListOf<View>()
    private var focus: String? = null
    private var pendingTasks = 0
    private var placed = false

    init {
        render()
    }

    override fun showProfiles(layout: HomeLayout, page: Int, inCall: Boolean) {
        val structure = this.layout !== layout || this.inCall != inCall || names() != names(layout)
        this.layout = layout
        this.page = page
        this.inCall = inCall
        if (structure) render() else mark(animate = true)
    }

    override fun select(mode: Mode, changed: Boolean) {
        this.mode = mode
        mark(animate = changed)
    }

    override fun showFocus(state: FocusTimer.State) {
        focus = focusRemaining(state)
        entries.indexOf(RailEntry.Tool(Mode.FOCUS)).takeIf { it >= 0 }?.let { decorate(views[it], entries[it]) }
    }

    override fun setPendingTasks(count: Int) {
        pendingTasks = count
        entries.indexOfFirst { it is RailEntry.Profile && it.page == layout.homeProfile }.takeIf { it >= 0 }?.let { decorate(views[it], entries[it]) }
    }

    override fun showLink(link: Link) = renderLink(activity, statusDot, statusName, status, link, offline = R.attr.c_text_faint)

    private fun names(of: HomeLayout = layout) = of.names.toList() to listOf(of.homeProfile, of.deckProfile, of.callProfile, of.gameProfile)

    /** Recrée les entrées (profils ajoutés, renommés, retirés, appel commencé ou fini). */
    private fun render() {
        entries = RailModel.entries(layout, inCall)
        bar.removeAllViews()
        views.clear()
        for (entry in entries) {
            val view = activity.layoutInflater.inflate(R.layout.item_profile_rail, bar, false)
            view.setOnClickListener { open(entry) }
            if (entry is RailEntry.Profile) {
                view.setOnLongClickListener {
                    onProfiles()
                    true
                }
            }
            decorate(view, entry)
            bar.addView(view)
            views += view
            // Séparateur entre les profils et les outils.
            if (entry !is RailEntry.Tool && entries.getOrNull(entries.indexOf(entry) + 1) is RailEntry.Tool) bar.addView(separator())
        }
        placed = false
        mark(animate = false)
    }

    private fun open(entry: RailEntry) = when (entry) {
        is RailEntry.Profile -> onProfile(entry.page)
        RailEntry.More -> onProfiles()
        is RailEntry.Tool -> onMode(entry.mode)
    }

    /** Icône, libellé, couleur du « maintenant » (Focus lancé, appel en cours) et pastille des tâches. */
    private fun decorate(view: View, entry: RailEntry) {
        val icon = view.findViewById<ImageView>(R.id.rail_icon)
        val label = view.findViewById<TextView>(R.id.rail_label)
        val badge = view.findViewById<TextView>(R.id.rail_badge)
        var now = false
        when (entry) {
            is RailEntry.Profile -> {
                icon.setImageResource(
                    when (entry.page) {
                        layout.homeProfile -> R.drawable.ic_dashboard
                        layout.deckProfile -> R.drawable.ic_deck
                        layout.callProfile -> R.drawable.ic_videocam
                        layout.gameProfile -> R.drawable.ic_gamepad
                        else -> R.drawable.ic_widgets
                    },
                )
                label.text = layout.nameOf(entry.page, activity.getString(R.string.profile_first), activity.getString(R.string.profile_default))
                now = entry.isCall
                val tasks = entry.page == layout.homeProfile && pendingTasks > 0
                badge.text = pendingTasks.toString()
                badge.visibility = if (tasks) View.VISIBLE else View.GONE
            }
            RailEntry.More -> {
                icon.setImageResource(R.drawable.ic_more)
                label.setText(R.string.rail_more)
            }
            is RailEntry.Tool -> {
                icon.setImageResource(entry.mode.icon)
                val remaining = focus.takeIf { entry.mode == Mode.FOCUS }
                label.text = remaining ?: activity.getString(entry.mode.label)
                now = remaining != null
            }
        }
        view.contentDescription = label.text
        if (now) {
            val red = themeColor(activity, R.attr.c_accent)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(red)
            label.setTextColor(red)
        } else {
            icon.imageTintList = androidx.core.content.ContextCompat.getColorStateList(activity, R.color.rail_profile_content)
            label.setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.rail_profile_content))
        }
    }

    private fun separator() = View(activity).apply {
        setBackgroundColor(themeColor(activity, R.attr.c_stroke))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(2)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    /** Élément choisi : texte plein, et la pastille glisse derrière son icône (ressort, joue aussi en kiosque). */
    private fun mark(animate: Boolean) {
        val selected = RailModel.selected(entries, mode, page)
        views.forEachIndexed { i, v -> v.isSelected = i == selected }
        tabletStatus.isSelected = mode == Mode.SETTINGS
        val target = views.getOrNull(selected)
        if (target == null) {
            Look.springTo(indicator, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 0f, always = true)
            return
        }
        // Avant la première mise en page, les positions ne sont pas connues : on place sans animer, après la mesure.
        if (target.height == 0) {
            target.post { mark(animate = false) }
            return
        }
        // Zone de l'icône (56 × 32) : la pastille se pose exactement derrière.
        val zone = target.findViewById<View>(R.id.rail_icon).parent as View
        val y = (bar.top + target.top + (zone.parent as View).top + zone.top).toFloat()
        if (!animate || !placed) {
            indicator.translationY = y
            indicator.alpha = 1f
            placed = true
            return
        }
        Look.springTo(indicator, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f, always = true)
        Look.springTo(indicator, DynamicAnimation.TRANSLATION_Y, Look.SPATIAL_DEFAULT, y, always = true)
    }
}
