package app.mate.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.AnimFamily
import app.mate.Mode
import app.mate.Prefs
import app.mate.R
import app.mate.focus.FocusTimer
import app.mate.net.Link
import app.mate.ui.home.HomeLayout
import app.mate.ui.home.ProfileIcons
import app.mate.ui.topbar.TopBar

/**
 * Rangée de touches en bas (thèmes Instrument, `c_dock_height` > 0), à la place de la barre de gauche : Parler (touche ronde),
 * une grande touche numérotée par écran de l'Accueil (Réunion, accentuée, pendant un appel) suivie de « + » pour en créer
 * un (la zone défile quand il y en a beaucoup), puis des boutons ronds pour Focus, Ordi, Caméra, Réglages et Verrou (touche ronde).
 * Une DEL au-dessus de l'élément actif, écran ou mode. L'ordinateur actif est la pastille LCD du bandeau du haut.
 * Ce que la rangée montre vient de [KeyDockModel] ; les outils masqués et les noms suivent les Réglages de la barre.
 */
class KeyDock(
    private val activity: Activity,
    private val onMode: (Mode) -> Unit,
    private val onProfile: (Int) -> Unit,
    private val onProfiles: () -> Unit,
) : Navigation {

    private val dock: LinearLayout = activity.findViewById(R.id.key_dock)
    private val scroll: HorizontalScrollView = activity.findViewById(R.id.dock_scroll)
    private val row: LinearLayout = activity.findViewById(R.id.dock_keys)
    private val end: LinearLayout = activity.findViewById(R.id.dock_end)
    private val chip: View = activity.findViewById<TopBar>(R.id.top_bar).computerChip
    private val chipDot: ImageView = chip.findViewById(R.id.chip_dot)
    private val chipName: TextView = chip.findViewById(R.id.chip_name)
    private val chipState: TextView = chip.findViewById(R.id.chip_state)
    override val root: View = dock
    override val status: View = chip
    override val talk: View = round(R.drawable.dock_talk_cap, R.drawable.ic_key_mic, R.color.dock_talk_icon, R.string.voice_button, atEnd = false)
    override val talkIcon: View = talk.findViewById(R.id.dock_round_icon)
    override val lock: View = round(null, R.drawable.ic_key_lock, R.color.dock_lock_icon, R.string.rail_lock, atEnd = true)

    private var layout = HomeLayout.load(activity)
    private var page = layout.homeProfile
    private var inCall = false
    private var mode = Mode.HOME
    private var notifications = false
    private var focus: String? = null
    private var pendingTasks = 0
    private var screens: List<KeyDockModel.Screen> = emptyList()
    private var tools: List<Mode> = emptyList()
    private val screenViews = mutableListOf<View>()
    private val toolViews = mutableListOf<View>()
    private var drawn: RailModel.Snapshot? = null
    private var fitted = 0
    private var position = -1
    private var resting = false
    private var linkState: Link.State? = null
    override var lastMove = 0
        private set

    init {
        activity.findViewById<View>(R.id.rail).visibility = View.GONE
        dock.visibility = View.VISIBLE
        activity.findViewById<TopBar>(R.id.top_bar).showComputerChip()
        ViewCompat.replaceAccessibilityAction(chip, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, activity.getString(R.string.dock_switch_computer), null)
        // La largeur des touches d'écran dépend de la place de la zone : elle se refait quand cette place change.
        scroll.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ -> if (right - left != oldRight - oldLeft) fitScreens() }
        render()
    }

    private fun round(@DrawableRes cap: Int?, @DrawableRes icon: Int, @ColorRes tint: Int, @StringRes description: Int, atEnd: Boolean): View {
        val view = activity.layoutInflater.inflate(R.layout.item_dock_round, dock, false)
        val face = view.findViewById<View>(R.id.dock_round_cap)
        cap?.let { face.setBackgroundResource(it) }
        view.findViewById<ImageView>(R.id.dock_round_icon).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(activity, tint)
        }
        view.contentDescription = activity.getString(description)
        press(view, face)
        dock.addView(view, if (atEnd) dock.childCount else 0)
        return view
    }

    /** Appui sur une touche : son capuchon s'enfonce tant qu'elle est pressée ([Look.keyCap]). Le clic n'est pas consommé. */
    private fun press(view: View, cap: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Look.keyCap(cap, pressed = true, rest = restOf(view))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Look.keyCap(cap, pressed = false, rest = restOf(view))
            }
            false
        }
    }

    /** Place du capuchon au repos : enfoncé de 4 dp quand la touche est active (celle qu'on voit), sinon posé. */
    private fun restOf(view: View): Float = if (view.isSelected) view.dp(SELECTED_DP).toFloat() else 0f

    override fun showProfiles(layout: HomeLayout, page: Int, inCall: Boolean) {
        this.layout = layout
        this.page = page
        this.inCall = inCall
        // Écrans, noms, icônes ou appel changés : la rangée se redessine ; sinon seule la touche active bouge.
        if (drawn != snapshot()) render() else mark(animate = true)
    }

    private fun snapshot() = RailModel.snapshot(layout, inCall, Prefs.railTools(activity), Prefs.railLabels(activity))

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
        val remaining = focusRemaining(state)
        // Le minuteur appelle à chaque tic : sans changement du texte, ni redécor ni nouvelle annonce TalkBack.
        if (remaining == focus) return
        focus = remaining
        tools.indexOf(Mode.FOCUS).takeIf { it >= 0 }?.let { decorateTool(it) }
    }

    override fun setPendingTasks(count: Int) {
        pendingTasks = count
        screens.indices.forEach { decorateScreen(it) }
    }

    /** L'état se lit dans la pastille LCD du bandeau : signe, nom et état écrit (couleurs de l'afficheur). */
    override fun showLink(link: Link) {
        renderLink(activity, chipDot, chipName, chipState, chip, link, offline = R.attr.c_text_faint, colors = chip.context)
        if (linkState != null && linkState != link.state) Look.blip(chipDot)
        linkState = link.state
    }

    override fun applySettings() = render()

    override fun tourAnchor(step: TourModel.Step): View? = when (step) {
        TourModel.Step.HOME, TourModel.Step.SCREENS -> screenViews.firstOrNull()
        TourModel.Step.SWIPE -> scroll
        TourModel.Step.TALK -> talk
        TourModel.Step.NOTIFICATIONS, TourModel.Step.CUSTOMIZE -> null
    }

    private fun render() {
        talk.visibility = if (Prefs.railTalk(activity)) View.VISIBLE else View.GONE
        val hadCall = screens.any { it.call }
        drawn = snapshot()
        screens = KeyDockModel.screens(layout.profilesInOrder(inCall), layout.callProfile, inCall)
        tools = KeyDockModel.tools(Prefs.railTools(activity))
        row.removeAllViews()
        end.removeAllViews()
        screenViews.clear()
        toolViews.clear()
        screens.forEach { screen ->
            val view = activity.layoutInflater.inflate(R.layout.item_dock_key, row, false)
            view.setOnClickListener { onProfile(screen.page) }
            // Appui long : la liste des écrans (renommer, ranger, ajouter). Le geste n'a pas d'indice visible : TalkBack l'annonce.
            view.setOnLongClickListener {
                onProfiles()
                true
            }
            ViewCompat.addAccessibilityAction(view, activity.getString(R.string.dock_manage_screens)) { _, _ ->
                onProfiles()
                true
            }
            view.findViewById<TextView>(R.id.dock_number).text = screen.number
            press(view, view.findViewById(R.id.dock_cap))
            row.addView(view)
            screenViews += view
        }
        if (KeyDockModel.showAdd(screens.size)) row.addView(addKey())
        end.addView(separator())
        tools.forEach { tool ->
            val view = activity.layoutInflater.inflate(R.layout.item_dock_tool, end, false)
            view.setOnClickListener { onMode(tool) }
            view.findViewById<ImageView>(R.id.dock_icon).setImageResource(icon(tool))
            press(view, view.findViewById(R.id.dock_cap))
            end.addView(view)
            toolViews += view
        }
        screens.indices.forEach { decorateScreen(it) }
        tools.indices.forEach { decorateTool(it) }
        fitted = 0
        fitScreens()
        position = -1
        mark(animate = false)
        val call = screenViews.lastOrNull()?.takeIf { !hadCall && screens.lastOrNull()?.call == true }
        call?.let { appear(it) }
    }

    private fun addKey(): View {
        val view = activity.layoutInflater.inflate(R.layout.item_dock_add, row, false)
        view.contentDescription = activity.getString(R.string.rail_add_desc)
        view.setOnClickListener { onProfiles() }
        press(view, view.findViewById(R.id.dock_cap))
        return view
    }

    private fun separator() = View(activity).apply {
        setBackgroundColor(themeColor(activity, R.attr.c_stroke))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(SEPARATOR_DP)).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            marginStart = dp(6)
            marginEnd = dp(6)
        }
    }

    /** Réunion qui arrive pendant un appel : sa touche monte en fondu, et la zone défile jusqu'à elle. */
    private fun appear(view: View) {
        scroll.post { scroll.smoothScrollTo(row.width, 0) }
        if (!Look.on(activity, AnimFamily.DETAILS) || Look.reduced(activity)) return
        view.alpha = 0f
        view.translationY = view.dp(APPEAR_DP).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(FLASH_MS * 2).start()
    }

    /** Largeur des touches d'écran : la zone partagée à parts égales, entre [MIN_KEY_DP] et [MAX_KEY_DP] ; plus étroit, elle défile. */
    private fun fitScreens() {
        val room = scroll.width - if (KeyDockModel.showAdd(screens.size)) scroll.dp(ADD_DP) else 0
        if (room <= 0 || screenViews.isEmpty()) return
        val each = (room / screenViews.size).coerceIn(scroll.dp(MIN_KEY_DP), scroll.dp(MAX_KEY_DP))
        if (each == fitted) return
        fitted = each
        val compact = KeyDockModel.compact(screens.size)
        screenViews.forEach { view ->
            val params = view.layoutParams
            params.width = each - params.margins()
            view.layoutParams = params
            // Touches étroites : sans icône, lettres resserrées (le nom reste lu par TalkBack).
            view.findViewById<View>(R.id.dock_icon).visibility = if (compact) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.dock_label).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) COMPACT_LABEL_SP else LABEL_SP)
                letterSpacing = if (compact) COMPACT_SPACING else LABEL_SPACING
            }
        }
    }

    private fun ViewGroup.LayoutParams.margins(): Int = (this as? ViewGroup.MarginLayoutParams)?.let { it.marginStart + it.marginEnd } ?: 0

    private fun icon(mode: Mode) = when (mode) {
        Mode.HOME -> R.drawable.ic_key_home
        Mode.FOCUS -> R.drawable.ic_key_focus
        Mode.COMPUTER -> R.drawable.ic_key_computer
        Mode.CAMERA -> R.drawable.ic_key_camera
        Mode.SETTINGS -> R.drawable.ic_key_settings
    }

    /** Nom du profil, couleur d'appel pour Réunion, pastille des tâches sur l'Accueil ; nom et tâches lus par TalkBack. */
    private fun decorateScreen(i: Int) {
        val screen = screens.getOrNull(i) ?: return
        val view = screenViews.getOrNull(i) ?: return
        val name = layout.nameOf(screen.page, activity.getString(R.string.profile_first), activity.getString(R.string.profile_default))
        val label = view.findViewById<TextView>(R.id.dock_label)
        label.text = name
        label.typeface = themeFont(activity, R.attr.c_font_label)
        label.visibility = if (Prefs.railLabels(activity)) View.VISIBLE else View.GONE
        // Réunion en appel : contour et texte orange (le contour dit « en appel » sans la couleur seule).
        val tone = themeColor(activity, if (screen.call) R.attr.c_accent else R.attr.c_text)
        label.setTextColor(tone)
        view.findViewById<ImageView>(R.id.dock_icon).apply {
            setImageResource(ProfileIcons.of(layout, screen.page))
            imageTintList = ColorStateList.valueOf(tone)
        }
        view.isActivated = screen.call
        val tasks = if (screen.page == layout.homeProfile) pendingTasks else 0
        view.contentDescription = name + if (tasks > 0) activity.resources.getQuantityString(R.plurals.dock_desc_tasks, tasks, tasks) else ""
        view.findViewById<TextView>(R.id.dock_badge).apply {
            text = tasks.toString()
            visibility = if (tasks > 0) View.VISIBLE else View.GONE
        }
    }

    /** Libellé (minuteur sur Focus lancé, avec l'accent comme signe qui reste noms coupés) et description de l'outil. */
    private fun decorateTool(i: Int) {
        val tool = tools.getOrNull(i) ?: return
        val view = toolViews.getOrNull(i) ?: return
        val label = view.findViewById<TextView>(R.id.dock_label)
        val timer = focus.takeIf { tool == Mode.FOCUS }
        val name = activity.getString(tool.label)
        label.text = timer ?: name
        label.typeface = themeFont(activity, if (timer != null) R.attr.c_font_display else R.attr.c_font_label)
        val tone = themeColor(activity, if (timer != null) R.attr.c_accent else R.attr.c_text)
        label.setTextColor(tone)
        label.visibility = if (Prefs.railLabels(activity)) View.VISIBLE else View.GONE
        view.findViewById<ImageView>(R.id.dock_icon).imageTintList = ColorStateList.valueOf(tone)
        view.contentDescription = if (timer != null) activity.getString(R.string.dock_desc_timer, name, timer) else name
    }

    /** Élément actif (écran ou outil) : capuchon enfoncé et DEL allumée (elle clignote une fois en s'allumant). */
    private fun mark(animate: Boolean) {
        val screen = KeyDockModel.markedScreen(screens, mode, page)
        val tool = if (notifications && mode == Mode.COMPUTER) -1 else KeyDockModel.markedTool(tools, mode)
        val next = if (screen >= 0) screen else if (tool >= 0) screens.size + tool else -1
        if (next != position) lastMove = RailModel.direction(position, next)
        position = next
        screenViews.forEachIndexed { i, view -> light(view, i == screen, screens[i].call, animate) }
        toolViews.forEachIndexed { i, view -> light(view, i == tool, false, animate) }
        screenViews.getOrNull(screen)?.let { reveal(it) }
    }

    private fun light(view: View, selected: Boolean, call: Boolean, animate: Boolean) {
        val lit = selected && !view.isSelected
        view.isSelected = selected
        val cap = view.findViewById<View>(R.id.dock_cap)
        if (animate) Look.keyCap(cap, pressed = false, rest = restOf(view)) else cap.translationY = restOf(view)
        view.findViewById<View>(R.id.dock_led).apply {
            isSelected = selected
            isActivated = call
            if (lit && animate && Look.on(activity, AnimFamily.DETAILS) && !Look.reduced(activity)) {
                alpha = FLASH_ALPHA
                animate().alpha(1f).setDuration(FLASH_MS).start()
            }
        }
    }

    /** La touche de l'écran affiché reste visible quand la zone défile : elle vient au centre si elle est en partie cachée. */
    private fun reveal(view: View) {
        scroll.post {
            if (view.left >= scroll.scrollX && view.right <= scroll.scrollX + scroll.width) return@post
            scroll.smoothScrollTo(view.left - (scroll.width - view.width) / 2, 0)
        }
    }

    override fun step(delta: Int): Boolean {
        when (val entry = neighbor(delta) ?: return false) {
            is RailEntry.Profile -> onProfile(entry.page)
            is RailEntry.Tool -> onMode(entry.mode)
            RailEntry.More, RailEntry.Add -> return false
        }
        return true
    }

    override fun canStep(delta: Int): Boolean = neighbor(delta) != null

    // Notifications (cloche) : hors du balayage, comme dans la barre de gauche.
    private fun neighbor(delta: Int): RailEntry? =
        if (notifications && mode == Mode.COMPUTER) null else RailModel.neighbor(KeyDockModel.stepEntries(screens, Prefs.railTools(activity)), mode, page, delta)

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

        /** Enfoncement du capuchon de la touche active, au repos. */
        const val SELECTED_DP = 4

        /** Largeurs d'une touche d'écran, marges comprises : jamais sous 112 dp (cible), au plus 230 dp. */
        const val MIN_KEY_DP = 112
        const val MAX_KEY_DP = 230
        const val SEPARATOR_DP = 56

        /** Place de « + » après les touches d'écran, marges comprises (64 dp + 2 × 6 dp du layout). */
        const val ADD_DP = 76
        const val APPEAR_DP = 12
        const val LABEL_SP = 17f
        const val COMPACT_LABEL_SP = 15f
        const val LABEL_SPACING = 0.08f
        const val COMPACT_SPACING = 0.04f
    }
}
