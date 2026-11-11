package app.mate.ui

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.AnimFamily
import app.mate.Mode
import app.mate.Prefs
import app.mate.R
import app.mate.focus.FocusTimer
import app.mate.net.Link
import app.mate.ui.home.HomeLayout
import app.mate.ui.topbar.TopBar

/**
 * Rangée de touches en bas (thèmes Instrument, `c_dock_height` > 0), à la place de la barre de gauche : Parler (touche ronde)
 * à gauche, les touches numérotées Accueil, Focus, Ordi, Caméra, Réglages, Verrou (touche ronde) à droite. Une DEL au-dessus
 * de la touche active ; celle d'Accueil a un segment par profil. L'ordinateur actif est la pastille LCD du bandeau du haut.
 * Ce que la rangée montre vient de [KeyDockModel] ; les outils masqués et les noms suivent les Réglages de la barre.
 */
class KeyDock(
    private val activity: Activity,
    private val onMode: (Mode) -> Unit,
    private val onProfile: (Int) -> Unit,
    private val onProfiles: () -> Unit,
) : Navigation {

    private val dock: LinearLayout = activity.findViewById(R.id.key_dock)
    private val row: LinearLayout = activity.findViewById(R.id.dock_keys)
    private val chip: View = activity.findViewById<TopBar>(R.id.top_bar).computerChip
    private val chipDot: ImageView = chip.findViewById(R.id.chip_dot)
    private val chipName: TextView = chip.findViewById(R.id.chip_name)
    private val chipState: TextView = chip.findViewById(R.id.chip_state)
    override val root: View = dock
    override val status: View = chip
    override val talk: View = round(R.drawable.dock_talk_cap, R.drawable.ic_key_mic, R.attr.c_bg, R.string.voice_button, atEnd = false)
    override val talkIcon: View = talk.findViewById(R.id.dock_round_icon)
    override val lock: View = round(null, R.drawable.ic_key_lock, R.attr.c_text, R.string.rail_lock, atEnd = true)

    private var layout = HomeLayout.load(activity)
    private var page = layout.homeProfile
    private var inCall = false
    private var mode = Mode.HOME
    private var notifications = false
    private var focus: String? = null
    private var pendingTasks = 0
    private var keys: List<KeyDockModel.Key> = emptyList()
    private val views = mutableListOf<View>()
    private var marked = -1
    private var position = -1
    private var resting = false
    private var linkState: Link.State? = null
    override var lastMove = 0
        private set

    init {
        activity.findViewById<View>(R.id.rail).visibility = View.GONE
        dock.visibility = View.VISIBLE
        activity.findViewById<TopBar>(R.id.top_bar).showComputerChip()
        render()
    }

    private fun round(@DrawableRes cap: Int?, @DrawableRes icon: Int, @AttrRes tint: Int, @StringRes description: Int, atEnd: Boolean): View {
        val view = activity.layoutInflater.inflate(R.layout.item_dock_round, dock, false)
        cap?.let { view.findViewById<View>(R.id.dock_round_cap).setBackgroundResource(it) }
        view.findViewById<ImageView>(R.id.dock_round_icon).apply {
            setImageResource(icon)
            imageTintList = themeColorList(activity, tint)
        }
        view.contentDescription = activity.getString(description)
        dock.addView(view, if (atEnd) dock.childCount else 0)
        return view
    }

    override fun showProfiles(layout: HomeLayout, page: Int, inCall: Boolean) {
        this.layout = layout
        this.page = page
        this.inCall = inCall
        decorate(0)
        mark(animate = true)
    }

    override fun select(mode: Mode, changed: Boolean) {
        this.mode = mode
        mark(animate = changed)
    }

    override fun showNotifications(shown: Boolean) {
        if (shown == notifications) return
        notifications = shown
        mark(animate = true)
    }

    override fun showFocus(state: FocusTimer.State) {
        focus = focusRemaining(state)
        keys.indexOfFirst { it.mode == Mode.FOCUS }.takeIf { it >= 0 }?.let { decorate(it) }
    }

    override fun setPendingTasks(count: Int) {
        pendingTasks = count
        decorate(0)
    }

    /** L'état se lit dans la pastille LCD du bandeau : signe, nom et état écrit (couleurs de l'afficheur). */
    override fun showLink(link: Link) {
        renderLink(activity, chipDot, chipName, chipState, chip, link, offline = R.attr.c_text_faint, colors = chip.context)
        if (linkState != null && linkState != link.state) Look.blip(chipDot)
        linkState = link.state
    }

    override fun applySettings() = render()

    override fun tourAnchor(step: TourModel.Step): View? = when (step) {
        TourModel.Step.HOME, TourModel.Step.SCREENS -> views.firstOrNull()
        TourModel.Step.SWIPE -> row
        TourModel.Step.TALK -> talk
        TourModel.Step.NOTIFICATIONS, TourModel.Step.CUSTOMIZE -> null
    }

    private fun render() {
        talk.visibility = if (Prefs.railTalk(activity)) View.VISIBLE else View.GONE
        keys = KeyDockModel.keys(Prefs.railTools(activity))
        row.removeAllViews()
        views.clear()
        keys.forEachIndexed { i, key ->
            val view = activity.layoutInflater.inflate(R.layout.item_dock_key, row, false)
            view.setOnClickListener { open(key) }
            // Appui long sur 01 : la liste des profils (renommer, ranger, ajouter).
            if (key.mode == Mode.HOME) {
                view.setOnLongClickListener {
                    onProfiles()
                    true
                }
            }
            view.findViewById<TextView>(R.id.dock_number).text = key.number
            view.findViewById<ImageView>(R.id.dock_icon).setImageResource(icon(key.mode))
            row.addView(view)
            views += view
            decorate(i)
        }
        marked = -1
        mark(animate = false)
    }

    private fun icon(mode: Mode) = when (mode) {
        Mode.HOME -> R.drawable.ic_key_home
        Mode.FOCUS -> R.drawable.ic_key_focus
        Mode.COMPUTER -> R.drawable.ic_key_computer
        Mode.CAMERA -> R.drawable.ic_key_camera
        Mode.SETTINGS -> R.drawable.ic_key_settings
    }

    private fun open(key: KeyDockModel.Key) {
        if (key.mode != Mode.HOME) return onMode(key.mode)
        when (val tap = KeyDockModel.homeTap(mode, layout.profilesInOrder(inCall), page)) {
            is KeyDockModel.HomeTap.Open -> onProfile(tap.page)
            KeyDockModel.HomeTap.Profiles -> onProfiles()
        }
    }

    /** Libellé (nom du profil sur 01, minuteur sur Focus lancé), couleur du « maintenant » et pastille des tâches. */
    private fun decorate(i: Int) {
        val key = keys.getOrNull(i) ?: return
        val view = views.getOrNull(i) ?: return
        val label = view.findViewById<TextView>(R.id.dock_label)
        val timer = focus.takeIf { key.mode == Mode.FOCUS }
        label.text = when (key.mode) {
            Mode.HOME -> layout.nameOf(page, activity.getString(R.string.profile_first), activity.getString(R.string.profile_default))
            else -> timer ?: activity.getString(key.mode.label)
        }
        label.typeface = themeFont(activity, if (timer != null) R.attr.c_font_display else R.attr.c_font_label)
        label.setTextColor(themeColor(activity, if (timer != null) R.attr.c_accent else R.attr.c_text))
        label.visibility = if (Prefs.railLabels(activity)) View.VISIBLE else View.GONE
        view.contentDescription = label.text
        view.findViewById<TextView>(R.id.dock_badge).apply {
            text = pendingTasks.toString()
            visibility = if (key.mode == Mode.HOME && pendingTasks > 0) View.VISIBLE else View.GONE
        }
    }

    /** Touche active : capuchon enfoncé et DEL allumée (elle clignote une fois en s'allumant). */
    private fun mark(animate: Boolean) {
        val selected = if (notifications && mode == Mode.COMPUTER) -1 else KeyDockModel.marked(keys, mode)
        val order = layout.profilesInOrder(inCall)
        val next = if (selected < 0) -1 else selected * PROFILE_SPAN + if (selected == 0) order.indexOf(page).coerceAtLeast(0) else 0
        if (next != position) lastMove = RailModel.direction(position, next)
        position = next
        marked = selected
        views.forEachIndexed { i, view ->
            view.isSelected = i == selected
            fillLeds(view.findViewById(R.id.dock_led), keys[i].mode, i == selected, order, animate)
        }
    }

    /** DEL d'une touche : un segment ; sur 01, un par profil (allumé sur celui qu'on voit, contour pour Réunion en appel). */
    private fun fillLeds(led: LinearLayout, mode: Mode, selected: Boolean, order: List<Int>, animate: Boolean) {
        val segments = if (mode == Mode.HOME) {
            KeyDockModel.segments(order, if (selected) page else -1, layout.callProfile, inCall)
        } else {
            listOf(KeyDockModel.Segment(0, selected, false))
        }
        while (led.childCount > segments.size) led.removeViewAt(led.childCount - 1)
        while (led.childCount < segments.size) led.addView(segmentView())
        segments.forEachIndexed { i, segment ->
            val view = led.getChildAt(i)
            val lit = segment.on && !view.isSelected
            view.isSelected = segment.on
            view.isActivated = segment.call && !segment.on
            if (lit && animate && Look.on(activity, AnimFamily.NAVIGATION)) {
                view.alpha = FLASH_ALPHA
                view.animate().alpha(1f).setDuration(FLASH_MS).start()
            }
        }
    }

    private fun segmentView() = View(activity).apply {
        setBackgroundResource(R.drawable.dock_led)
        layoutParams = LinearLayout.LayoutParams(dp(SEGMENT_DP), dp(5)).apply { marginStart = dp(2); marginEnd = dp(2) }
    }

    override fun step(delta: Int): Boolean {
        val entry = neighbor(delta) ?: return false
        when (entry) {
            is RailEntry.Profile -> onProfile(entry.page)
            is RailEntry.Tool -> onMode(entry.mode)
            RailEntry.More, RailEntry.Add -> return false
        }
        return true
    }

    override fun canStep(delta: Int): Boolean = neighbor(delta) != null

    // Notifications (cloche) : hors du balayage, comme dans la barre de gauche.
    private fun neighbor(delta: Int): RailEntry? =
        if (notifications && mode == Mode.COMPUTER) null else RailModel.neighbor(RailModel.entries(layout, inCall, Prefs.railTools(activity)), mode, page, delta)

    /** Repos : la rangée passe à 40 %, un toucher la ravive. Rien ne disparaît. */
    override fun setResting(resting: Boolean) {
        if (this.resting == resting) return
        this.resting = resting
        Look.navSpring(dock, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, if (resting) RESTING_ALPHA else 1f)
    }

    private companion object {
        const val RESTING_ALPHA = 0.4f
        const val FLASH_ALPHA = 0.25f
        const val FLASH_MS = 140L
        const val SEGMENT_DP = 24

        /** Place d'un profil derrière sa touche, pour le sens du mouvement ([lastMove]). */
        const val PROFILE_SPAN = 1000
    }
}
