package app.mate.ui

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.Mode
import app.mate.Prefs
import app.mate.R
import app.mate.focus.FocusTimer
import app.mate.net.Link
import app.mate.ui.home.HomeLayout

/**
 * Barre fixe à gauche (Deck V2) : Parler en tête, les profils (Accueil, Deck, ceux de l'utilisateur, Réunion pendant
 * un appel), « Plus » au-delà de [RailModel.MAX_PROFILES], les outils (Focus, Ordi, Caméra), puis en pied la cloche,
 * l'ordinateur et la roue des Réglages (l'heure, le WiFi et la batterie sont en haut à droite des écrans). Jamais cachée. Ce qu'elle montre vient de [RailModel] ;
 * le repère rouge de l'élément choisi glisse d'une entrée à l'autre sur un ressort qui joue aussi en kiosque.
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
    private val settings: View = activity.findViewById(R.id.rail_settings)
    private val items: View = activity.findViewById(R.id.rail_items)
    private val statusDot: View = activity.findViewById(R.id.status_dot)
    private val statusName: TextView = activity.findViewById(R.id.status_name)
    private val statusState: TextView = activity.findViewById(R.id.status_state)
    override val status: View = activity.findViewById(R.id.status)
    override val talk: View = activity.layoutInflater.inflate(R.layout.item_rail_talk, rail, false).also { rail.addView(it, 0) }

    private var layout = HomeLayout.load(activity)
    private var page = layout.homeProfile
    private var inCall = false
    private var mode = Mode.HOME
    private var notifications = false
    private var entries: List<RailEntry> = emptyList()
    private val views = mutableListOf<View>()
    private var focus: String? = null
    private var pendingTasks = 0
    private var placed = false

    /** Ce que la barre montre : elle ne se redessine que si la disposition, l'appel ou les réglages changent. */
    private var drawn: RailModel.Snapshot? = null
    private var waitingLayout = false
    private val fontRegular = themeFont(activity, R.attr.c_font_regular)
    private val fontMedium = themeFont(activity, R.attr.c_font_medium)
    private var marked = -1
    private var resting = false
    override var lastMove = 0
        private set

    init {
        render()
        // La fenêtre passe en plein écran après la première mise en page (barres d'Android retirées) : la barre grandit
        // et ses pieds descendent, le repère est recalé sur place.
        rail.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (placed && bottom - top != oldBottom - oldTop) mark(animate = false)
        }
        // Place des entrées changée (plein écran, barre d'Android, clavier) : elles se resserrent ou se détendent.
        items.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) items.post { fit() }
        }
    }

    override fun showProfiles(layout: HomeLayout, page: Int, inCall: Boolean) {
        val structure = drawn != RailModel.snapshot(layout, inCall, Prefs.railTools(activity), Prefs.railLabels(activity))
        val callArrives = inCall && !this.inCall
        val before = entries.filterIsInstance<RailEntry.Profile>().map { it.page }.toSet()
        this.layout = layout
        this.page = page
        this.inCall = inCall
        if (structure) render() else mark(animate = true)
        if (callArrives) arrive(entries.indexOfFirst { it is RailEntry.Profile && it.isCall })
        // Profil créé : il grandit à sa place dans la barre, comme Réunion à l'appel.
        if (structure && before.isNotEmpty()) {
            entries.forEachIndexed { i, entry -> if (entry is RailEntry.Profile && !entry.isCall && entry.page !in before) arrive(i) }
        }
    }

    /** Réunion qui entre dans la barre : elle grandit de 0,6 à 1 en fondu (ressorts, jouent aussi en kiosque). */
    private fun arrive(index: Int) {
        val view = views.getOrNull(index) ?: return
        view.scaleX = ARRIVE_SCALE
        view.scaleY = ARRIVE_SCALE
        view.alpha = 0f
        Look.navSpring(view, DynamicAnimation.SCALE_X, Look.SPATIAL_DEFAULT, 1f)
        Look.navSpring(view, DynamicAnimation.SCALE_Y, Look.SPATIAL_DEFAULT, 1f)
        Look.navSpring(view, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
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
        entries.indexOf(RailEntry.Tool(Mode.FOCUS)).takeIf { it >= 0 }?.let { decorate(views[it], entries[it]) }
    }

    override fun setPendingTasks(count: Int) {
        pendingTasks = count
        entries.indexOfFirst { it is RailEntry.Profile && it.page == layout.homeProfile }.takeIf { it >= 0 }?.let { decorate(views[it], entries[it]) }
    }

    /** Le rouge est réservé au « maintenant » : point vert si l'ordinateur répond, neutre sinon ; sa forme et l'état écrit disent le reste. */
    override fun showLink(link: Link) {
        renderLink(activity, statusDot, statusName, statusState, status, link, offline = R.attr.c_text_faint)
        // L'état change (en ligne ↔ injoignable…) : la pastille gonfle une fois, pour qu'on le remarque.
        if (linkState != null && linkState != link.state) Look.blip(statusDot)
        linkState = link.state
    }

    private var linkState: Link.State? = null

    /** Recrée les entrées (profils ajoutés, renommés, retirés, appel commencé ou fini). */
    override fun applySettings() = render()

    override fun tourAnchor(step: TourModel.Step): View? = when (step) {
        TourModel.Step.HOME -> views.getOrNull(entries.indexOfFirst { it is RailEntry.Profile && it.page == layout.homeProfile })
        TourModel.Step.SCREENS -> views.getOrNull(entries.indexOf(RailEntry.Add))
        TourModel.Step.SWIPE -> bar
        TourModel.Step.TALK -> talk
        // Cloche dans le bandeau du haut : MainActivity la donne.
        TourModel.Step.NOTIFICATIONS -> null
        TourModel.Step.CUSTOMIZE -> null
    }

    private fun render() {
        val labels = Prefs.railLabels(activity)
        drawn = RailModel.snapshot(layout, inCall, Prefs.railTools(activity), labels)
        talk.findViewById<View>(R.id.rail_talk_label).visibility = if (labels) View.VISIBLE else View.GONE
        statusName.visibility = if (labels) View.VISIBLE else View.GONE
        entries = RailModel.entries(layout, inCall, Prefs.railTools(activity))
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
        drawnLabels = null
        fit()
        mark(animate = false)
    }

    /**
     * Hauteur des entrées : 60 dp avec les noms, 52 sans. Trop d'entrées pour la place (profils, outils, crayon et roue
     * empilés) : elles se resserrent, sans les noms, jusqu'à [MIN_ENTRY_DP] (dessous, la fin est rognée).
     */
    private fun fit() {
        if (views.isEmpty()) return
        val labels = Prefs.railLabels(activity)
        val normal = activity.dp(if (labels) ENTRY_DP else ENTRY_BARE_DP)
        val separators = (bar.childCount - views.size) * activity.dp(SEPARATOR_DP)
        val room = items.height
        val height = if (room <= 0) normal else ((room - separators) / views.size).coerceIn(activity.dp(MIN_ENTRY_DP), normal)
        val shown = labels && height == normal
        if (views.all { it.layoutParams.height == height } && drawnLabels == shown) return
        drawnLabels = shown
        views.forEach { view ->
            view.findViewById<View>(R.id.rail_label).visibility = if (shown) View.VISIBLE else View.GONE
            view.layoutParams = view.layoutParams.apply { this.height = height }
        }
        if (room > 0) bar.post { mark(animate = false) }
    }

    /** Noms affichés sous les icônes des entrées (pas resserrées). */
    private var drawnLabels: Boolean? = null

    override fun step(delta: Int): Boolean {
        val entry = neighbor(delta) ?: return false
        open(entry)
        return true
    }

    override fun canStep(delta: Int): Boolean = neighbor(delta) != null

    // Notifications (cloche) : en pied de barre comme Réglages, hors du balayage.
    private fun neighbor(delta: Int): RailEntry? = if (notifications && mode == Mode.COMPUTER) null else RailModel.neighbor(entries, mode, page, delta)

    private fun open(entry: RailEntry) = when (entry) {
        is RailEntry.Profile -> onProfile(entry.page)
        RailEntry.More, RailEntry.Add -> onProfiles()
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
                icon.setImageResource(app.mate.ui.home.ProfileIcons.of(layout, entry.page))
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
            RailEntry.Add -> {
                icon.setImageResource(R.drawable.ic_add)
                label.setText(R.string.rail_add)
            }
            is RailEntry.Tool -> {
                icon.setImageResource(entry.mode.icon)
                val remaining = focus.takeIf { entry.mode == Mode.FOCUS }
                label.text = remaining ?: activity.getString(entry.mode.label)
                now = remaining != null
            }
        }
        view.contentDescription = if (entry == RailEntry.Add) activity.getString(R.string.rail_add_desc) else label.text
        if (now) {
            val accent = themeColor(activity, R.attr.c_accent)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
            label.setTextColor(accent)
        } else {
            icon.imageTintList = androidx.core.content.ContextCompat.getColorStateList(activity, R.color.rail_profile_content)
            label.setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.rail_profile_content))
        }
    }

    private fun separator() = View(activity).apply {
        setBackgroundColor(themeColor(activity, R.attr.c_stroke))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(SEPARATOR_DP - 16)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    /** Élément choisi : icône pleine et texte plein, le repère rouge glisse à sa hauteur (ressort, joue aussi en kiosque). */
    private fun mark(animate: Boolean) {
        val bellShown = notifications && mode == Mode.COMPUTER
        val selected = if (bellShown) -1 else RailModel.selected(entries, mode, page)
        if (selected != marked) lastMove = RailModel.direction(marked, selected)
        marked = selected
        views.forEachIndexed { i, v ->
            v.isSelected = i == selected
            v.findViewById<TextView>(R.id.rail_label).typeface = if (i == selected) fontMedium else fontRegular
        }
        settings.isSelected = mode == Mode.SETTINGS
        // La roue est en pied de barre, hors de la liste des entrées : le repère descend jusqu'à elle. Notifications : la
        // cloche est dans le bandeau du haut, aucun repère dans la barre.
        val target = views.getOrNull(selected) ?: settings.takeIf { mode == Mode.SETTINGS }
        if (target == null) {
            Look.navSpring(indicator, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 0f)
            return
        }
        // Avant la première mise en page, les positions ne sont pas connues : on place sans animer, après la mesure.
        if (target.height == 0) {
            // Barre pas encore mesurée (ou masquée par le clavier) : on attend sa prochaine mise en page, une fois.
            if (!waitingLayout) {
                waitingLayout = true
                bar.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                        bar.removeOnLayoutChangeListener(this)
                        waitingLayout = false
                        mark(animate = false)
                    }
                })
            }
            return
        }
        // Le repère se centre sur l'entrée (icône et libellé), au bord gauche.
        val y = (topInRail(target) - topInRail(items) + (target.height - indicator.height) / 2).toFloat()
        if (!animate || !placed) {
            indicator.translationY = y
            indicator.alpha = 1f
            placed = true
            return
        }
        Look.navSpring(indicator, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        Look.navSpring(indicator, DynamicAnimation.TRANSLATION_Y, Look.SPATIAL_DEFAULT, y)
    }

    /** Haut de `view` dans la barre entière (sans les translations). */
    private fun topInRail(view: View): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== rail) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    /** Repos : la barre passe à 40 %, les libellés s'effacent ; un toucher la ravive. Rien ne disparaît. */
    override fun setResting(resting: Boolean) {
        if (this.resting == resting) return
        this.resting = resting
        Look.navSpring(rail, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, if (resting) RESTING_ALPHA else 1f)
        views.forEach { Look.navSpring(it.findViewById(R.id.rail_label), DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, if (resting) 0f else 1f) }
    }

    private companion object {
        const val RESTING_ALPHA = 0.4f
        const val ARRIVE_SCALE = 0.6f
        const val ENTRY_DP = 60
        const val ENTRY_BARE_DP = 52
        const val MIN_ENTRY_DP = 40

        /** Séparateur entre profils et outils : 2 dp et 8 dp de marge de chaque côté. */
        const val SEPARATOR_DP = 18
    }
}
