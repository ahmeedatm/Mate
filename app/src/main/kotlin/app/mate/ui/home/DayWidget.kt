package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import app.mate.R
import app.mate.focus.FocusTimer
import app.mate.net.BackendClient
import app.mate.net.CalendarEvent
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ma journée (Accueil de bureau, à la place de la grande heure : l'heure est déjà en haut à droite) : salutation et date,
 * la prochaine réunion du jour en grand, ou « Aucune / Plus de réunion aujourd'hui » et la suivante ; puis les actions
 * du jour : Focus (le chrono, [DayBrief.focusPill]), Ne pas déranger de l'ordinateur (lu à chaque [load], caché s'il est
 * illisible), Rejoindre ([DayBrief.canJoin]). Météo et eau à droite ([HeroComplications]). Sur la ligne fine de
 * l'Accueil (92 de poids contre 232 pour la première), la place manque : la réunion et les actions s'effacent, la
 * salutation et la date restent seules, sans pastilles par-dessus. Une mise en page par famille ([DayFace], direction
 * Cadran, #180) : la Ligne garde ce bandeau ; ailleurs, les mêmes pièces autour de l'anneau de compte à rebours avant la
 * réunion, et la journée en ligne de temps dans les grandes formes.
 */
class DayWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = FrameLayout(context).barelyFramed()
    private var face = DayFace.of(placed.size)
    private lateinit var greeting: TextView
    private lateinit var date: TextView
    private lateinit var meeting: LinearLayout
    private lateinit var title: TextView
    private lateinit var whenText: TextView
    private lateinit var join: Button
    private lateinit var focusButton: Button
    private lateinit var dndButton: Button
    private lateinit var note: TextView
    private lateinit var actions: LinearLayout
    private lateinit var complView: View
    private var compl: HeroComplications? = null
    private val ring = CountdownRing(context)
    private val track = DayTrack(context)
    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private var joinable: CalendarEvent? = null
    private var pill: DayBrief.FocusPill? = null
    private var dnd: Boolean? = null
    private var active = false

    /** Aperçu du catalogue : la journée d'exemple à la place de l'agenda de l'ordinateur. */
    private var sample: List<CalendarEvent>? = null
    private val events get() = sample ?: host.events

    /** Bandeau : hauteur du widget, relue à chaque mise en page ; sous [MIN_DP] la réunion et les actions s'effacent. */
    private var room = Int.MAX_VALUE
    private val roomForMore get() = !face.band || room >= dp(MIN_DP)

    // Le chrono prévient chaque seconde : la pastille ne change qu'avec les minutes ou l'état.
    private val focusListener: (FocusTimer.State) -> Unit = { state -> DayBrief.focusPill(state).takeIf { it != pill }?.let(::renderFocusPill) }

    override val hasSettings get() = true

    init {
        view.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val h = bottom - top
            if (h != room) room = h
            if (h != oldBottom - oldTop || right - left != oldRight - oldLeft) v.post { fit() }
        }
        applySize()
    }

    override fun applySize() {
        face = DayFace.of(placed.size)
        // L'ancien contenu part sans mise en page : un repli qui l'attendait n'arrivera jamais.
        foldPosted = false
        foldAgain = false
        view.removeAllViews()
        val root = LayoutInflater.from(context).inflate(R.layout.widget_day, view, false)
        greeting = root.findViewById(R.id.day_greeting)
        date = root.findViewById(R.id.day_date)
        meeting = root.findViewById(R.id.day_meeting)
        title = root.findViewById(R.id.day_title)
        whenText = root.findViewById(R.id.day_when)
        join = root.findViewById(R.id.day_join)
        focusButton = root.findViewById(R.id.day_focus)
        dndButton = root.findViewById(R.id.day_dnd)
        note = root.findViewById(R.id.day_note)
        actions = root.findViewById(R.id.day_actions)
        complView = root.findViewById(R.id.hero_compl)
        join.setOnClickListener { joinable?.let { joinMeeting(host, it, join) } }
        focusButton.setOnClickListener { FocusTimer.toggle() }
        dndButton.setOnClickListener { toggleDnd() }
        view.addView(if (face.band) root else stack(), FrameLayout.LayoutParams(MATCH, MATCH))
        compl?.setActive(false)
        // Météo et eau seulement là où elles se voient : pas de lecture de position ni de réseau pour rien.
        compl = if (face.band || face.compl) HeroComplications(context, host, placed, complView, compl).also { it.setActive(active) } else null
        pill = null
        renderFocusPill(DayBrief.focusPill(FocusTimer.state))
        render(animate = false)
        view.post { fit() }
    }

    /** Les pièces du bandeau rangées pour la forme ; l'anneau à côté de la réunion (ou au-dessus en colonne). */
    private fun stack(): View {
        for (part in listOf(greeting, date, meeting, note, join, actions, complView, ring, track)) (part.parent as? ViewGroup)?.removeView(part)
        actions.removeView(join)
        meeting.orientation = LinearLayout.VERTICAL
        (whenText.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = 0
        val isPill = family == ShapeFamily.PILL
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (face.ringBeside) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
        }
        if (face.greeting) {
            val big = family == ShapeFamily.LARGE || family == ShapeFamily.FULL
            greeting.textSize = if (big) BIG_GREETING_SP else GREETING_SP
            greeting.maxLines = 2
            column.addView(greeting, LinearLayout.LayoutParams(WRAP, WRAP))
            column.addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (face.ringBeside) Gravity.START else Gravity.CENTER_HORIZONTAL
            addView(meeting, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(note, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(join, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(GAP_DP) })
        }
        title.maxLines = if (isPill) 2 else 3
        title.textSize = if (isPill) PILL_TITLE_SP else TITLE_SP
        note.textSize = if (isPill) PILL_NOTE_SP else NOTE_SP
        join.text = if (isPill) "" else context.getString(R.string.join_meeting)
        join.contentDescription = context.getString(R.string.join_meeting)
        join.minWidth = 0
        join.minimumWidth = 0
        val heart = LinearLayout(context).apply {
            orientation = if (face.ringBeside) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (face.ringBeside) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            addView(ring, LinearLayout.LayoutParams(0, 0).apply { if (face.ringBeside) marginEnd = dp(GAP_DP * 2) else bottomMargin = dp(GAP_DP * 2) })
            addView(texts, LinearLayout.LayoutParams(if (face.ringBeside) 0 else MATCH, WRAP, if (face.ringBeside) 1f else 0f))
        }
        column.addView(heart, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (face.greeting) topMargin = dp(GAP_DP * 3) })
        if (face.actions) {
            val down = family == ShapeFamily.COLUMN
            actions.orientation = if (down) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            // Empilés : Ne pas déranger sous Focus, sur le même axe, avec 8 dp entre les deux cibles.
            (dndButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                marginStart = if (down) 0 else marginStart
                topMargin = if (down) dp(STACK_GAP_DP) else 0
            }
            column.addView(actions, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(GAP_DP * 3) })
        }
        if (face.track) {
            track.across = true
            // Sous 3 colonnes, les titres seraient coupés en miettes : la ligne seule.
            track.titles = placed.size.w >= 3
            column.addView(track, LinearLayout.LayoutParams(MATCH, dp(TRACK_DP)).apply { topMargin = dp(GAP_DP * 3) })
        }
        if (face.compl) {
            (complView as? LinearLayout)?.orientation = LinearLayout.HORIZONTAL
            column.addView(complView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(GAP_DP * 3) })
        }
        return column
    }

    private fun fit() {
        if (face.band) {
            actions.visibility = if (roomForMore) View.VISIBLE else View.GONE
            renderFocus()
            bandFold()
            return
        }
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = when (family) {
            ShapeFamily.PILL, ShapeFamily.ROW -> minOf(h, w * PILL_RING_W)
            ShapeFamily.COLUMN -> minOf(w * COLUMN_RING_W, h * COLUMN_RING_H)
            else -> if (face.ringBeside) minOf(h * BESIDE_RING_H, w * BESIDE_RING_W) else minOf(w * COLUMN_RING_W, h * COLUMN_RING_H)
        }.coerceAtLeast(dp(MIN_RING_DP).toFloat()).coerceAtMost(minOf(w, h))
        ring.layoutParams = (ring.layoutParams as LinearLayout.LayoutParams).apply {
            width = d.toInt()
            height = d.toInt()
        }
        ring.requestLayout()
        // Nouvelle taille : tout revient, puis se replie s'il le faut.
        for (part in listOf(complView, track, actions, greeting, date)) if (part.parent != null) part.visibility = View.VISIBLE
        refold()
    }

    /**
     * Trop haute pour la carte (colonne basse) : la météo, la journée, puis les actions, puis la salutation se replient, une à la fois,
     * jugé sur les positions réelles après la mise en page (une mesure à part se trompe sur les polices des thèmes).
     */
    private fun fold() {
        val content = view.getChildAt(0) as? ViewGroup ?: return
        val shown = (0 until content.childCount).map { content.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
        if (shown.isEmpty()) return
        // La colonne écrase le dernier bloc pour qu'il tienne (un bouton coupé sans dépasser) : comparer la place que
        // chaque bloc voudrait, à sa largeur réelle, à la hauteur de la carte.
        val wanted = shown.sumOf {
            it.measure(View.MeasureSpec.makeMeasureSpec(it.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            it.measuredHeight + it.topMargin()
        }
        if (wanted <= content.height) return
        val next = listOf(listOf(complView), listOf(track), listOf(actions), listOf(greeting, date))
            .firstOrNull { part -> part.any { it.parent != null && it.visibility == View.VISIBLE } } ?: return
        next.forEach { it.visibility = View.GONE }
        refold()
    }

    private fun View.topMargin() = (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

    override fun setActive(active: Boolean) {
        this.active = active
        compl?.setActive(active)
        if (active) FocusTimer.observe(focusListener) else FocusTimer.unobserve(focusListener)
        if (active) render(animate = false)
    }

    override fun onMinute() = render(animate = true)

    override fun onCalendar() = renderFocus()

    private fun render(animate: Boolean) {
        val now = System.currentTimeMillis()
        greeting.setText(
            when (DayBrief.greeting(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY))) {
                DayBrief.Greeting.MORNING -> R.string.day_morning
                DayBrief.Greeting.AFTERNOON -> R.string.day_afternoon
                DayBrief.Greeting.EVENING -> R.string.day_evening
            },
        )
        val text = dateFormat.format(Date(now)).replaceFirstChar { it.uppercase() }
        if (date.text.toString() != text) date.text = text
        renderFocus()
        compl?.render(animate)
    }

    private fun renderFocus() {
        val now = System.currentTimeMillis()
        if (face.track) track.show(events, now)
        // Rejoindre, la note ou Ne pas déranger peuvent apparaître : le repli se refait après.
        refold()
        // Ordinateur injoignable : seul message, les rendez-vous peuvent dater.
        renderDnd()
        val down = if (sample == null) host.unreachable else null
        if (down != null) {
            val since = down.since()
            showNote(if (since != null) context.getString(R.string.hero_down, down.name, since) else context.getString(R.string.hero_down_unknown, down.name), R.attr.c_warn)
            return
        }
        when (val focus = DayBrief.focus(events, now, host.chosenId)) {
            is DayBrief.Focus.Meeting -> {
                meeting.visibility = if (roomForMore) View.VISIBLE else View.GONE
                note.visibility = View.GONE
                title.text = focus.event.title
                whenText.text = focus.line.whenText(context)
                whenText.setTextColor(color(if (focus.line is NextLine.At) R.attr.c_text_dim else R.attr.c_accent))
                // Pastille : le temps est dans l'anneau.
                whenText.visibility = if (family == ShapeFamily.PILL) View.GONE else View.VISIBLE
                // Colonne étroite : l'heure de fin passe à la ligne plutôt que d'être coupée.
                if (!face.band) {
                    whenText.isSingleLine = false
                    whenText.maxLines = 2
                }
                if (!face.band) {
                    ring.visibility = View.VISIBLE
                    ring.showNext(focus.event, now)
                }
                joinable = focus.event.takeIf { sample == null && DayBrief.canJoin(it, now, it.id == host.chosenId, host.features.meetings) }
                join.visibility = if (joinable != null) View.VISIBLE else View.GONE
            }
            is DayBrief.Focus.Free -> {
                // Pas d'agenda relié ni de rendez-vous noté : la carte Agenda le dit déjà, rien à affirmer ici.
                if (events.isEmpty() && !host.features.linked) return showNote(null, R.attr.c_text_dim)
                val free = context.getString(if (focus.hadMeetings) R.string.day_free_after else R.string.day_free)
                val later = focus.later?.let { context.getString(R.string.day_later, it.whenText(context), it.title) }
                showNote(if (later != null) "$free · $later" else free, R.attr.c_text_dim)
            }
        }
    }

    /** Ligne du dessous en texte seul ; null : rien. Pas d'anneau sans réunion à attendre. */
    private fun showNote(text: String?, colorAttr: Int) {
        meeting.visibility = View.GONE
        ring.visibility = View.GONE
        joinable = null
        join.visibility = View.GONE
        note.visibility = if (text != null && roomForMore) View.VISIBLE else View.GONE
        note.text = text
        note.setTextColor(color(colorAttr))
    }

    private fun renderFocusPill(p: DayBrief.FocusPill) {
        pill = p
        focusButton.text = context.getString(
            when (p.kind) {
                DayBrief.FocusPill.Kind.IDLE -> R.string.day_focus
                DayBrief.FocusPill.Kind.RUNNING -> R.string.day_focus_running
                DayBrief.FocusPill.Kind.PAUSED -> R.string.day_focus_paused
                DayBrief.FocusPill.Kind.BREAK -> R.string.day_focus_break
            },
            p.minutes,
        )
        val running = FocusTimer.state.running
        focusButton.setCompoundDrawablesRelativeWithIntrinsicBounds(if (running) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0)
        focusButton.isSelected = p.kind == DayBrief.FocusPill.Kind.RUNNING
    }

    /** Ne pas déranger : seulement lisible et ordinateur joignable ; rouge quand il est actif. */
    private fun renderDnd() {
        val on = dnd
        val shown = if (on != null && host.unreachable == null) View.VISIBLE else View.GONE
        if (dndButton.visibility != shown) refold()
        dndButton.visibility = shown
        dndButton.isSelected = on == true
    }

    /**
     * Bandeau : sur une rangée moyenne (plus haute que la fine, trop basse pour tout), les boutons du bas recouvraient la
     * réunion ; après la mise en page, s'ils la touchent, ils s'effacent.
     */
    private fun bandFold() {
        val root = view.getChildAt(0) as? ViewGroup ?: return
        val texts = root.getChildAt(0) ?: return
        root.doOnNextLayout {
            if (actions.visibility == View.VISIBLE && texts.bottom > actions.top) actions.visibility = View.GONE
        }
        root.requestLayout()
    }

    /** Un repli attendu à la fois ; une demande pendant l'attente est rejouée ensuite (la taille a pu changer entre-temps). */
    private var foldPosted = false
    private var foldAgain = false

    private fun refold() {
        if (face.band) return
        if (foldPosted) {
            foldAgain = true
            return
        }
        val content = view.getChildAt(0) ?: return
        foldPosted = true
        // Après la prochaine mise en page : les positions sont celles de l'écran.
        content.doOnNextLayout {
            foldPosted = false
            if (view.width > 0) fold()
            if (foldAgain) {
                foldAgain = false
                refold()
            }
        }
        content.requestLayout()
    }

    private fun toggleDnd() {
        val wanted = dnd != true
        dnd = wanted
        renderDnd()
        host.scope.launch {
            try {
                BackendClient(context).system.setDnd(wanted)
            } catch (e: Exception) {
                dnd = !wanted
                renderDnd()
                host.main?.showBanner(context.errorText(e), 6)
            }
        }
    }

    override suspend fun load() {
        if (sample != null) return
        dnd = runCatchingCancellable { BackendClient(context).system.dnd() }.getOrNull()
        renderDnd()
        compl?.load()
    }

    override fun showSettings() {
        compl?.showSettings()
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is CalendarSample) return
        this.sample = CalendarSample.events(System.currentTimeMillis(), context::getString)
        compl?.showSample(WeatherSample.paris())
        render(animate = false)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Entre la ligne fine (92 sur 740 de hauteur) et la première ligne (232) : salutation et date seules en dessous. */
        const val MIN_DP = 110

        const val GAP_DP = 6
        const val TRACK_DP = 56
        const val MIN_RING_DP = 72
        const val STACK_GAP_DP = 8

        const val GREETING_SP = 26f
        const val BIG_GREETING_SP = 34f
        const val TITLE_SP = 22f
        const val PILL_TITLE_SP = 16f
        const val NOTE_SP = 18f
        const val PILL_NOTE_SP = 15f

        /** Diamètre de l'anneau : Pastille, Colonne (debout), à côté de la réunion ; parts de la largeur et de la hauteur. */
        const val PILL_RING_W = 0.45f
        const val COLUMN_RING_W = 0.7f
        const val COLUMN_RING_H = 0.3f
        const val BESIDE_RING_H = 0.32f
        const val BESIDE_RING_W = 0.3f
    }
}
