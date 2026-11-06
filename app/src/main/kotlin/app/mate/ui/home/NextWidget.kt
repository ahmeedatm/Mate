package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val nextTime get() = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val nextShortDay get() = SimpleDateFormat("EEE", Locale.FRANCE)

/**
 * Prochain rendez-vous (ou la réunion touchée dans l'agenda), direction Cadran (#180) : un anneau qui se vide jusqu'au
 * départ (moins d'une heure) ou se remplit pendant la réunion, l'heure au centre sinon ; « Rejoindre » pour une réunion
 * en ligne à partir de 15 min avant. Une mise en page par famille ([NextFace]) : plus grand, les détails (lieu,
 * début–fin, durée) et les rendez-vous suivants, autant que la place en laisse.
 */
class NextWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val ring = CountdownRing(context)
    private val labelView = TitleView(context).apply {
        setTextColor(color(R.attr.c_hue_orange))
        oneLine()
    }
    private val todayCount = label(15f, R.attr.c_text_faint).apply { isSingleLine = true }
    private val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(labelView, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(todayCount)
    }
    private val title = label(22f).apply {
        ellipsize = TextUtils.TruncateAt.END
        typeface = themeFont(context, R.attr.c_font_medium)
    }
    private val whenText = label(17f, R.attr.c_accent).apply { oneLine() }
    private val details = label(15f, R.attr.c_text_dim).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val join = Button(context, null, 0, R.style.NowButton).apply {
        setText(R.string.join_meeting)
        contentDescription = context.getString(R.string.join_meeting)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_videocam, 0, 0, 0)
        visibility = View.GONE
        setOnClickListener { joinNext() }
    }

    /** Rien de prévu : l'action visible pour noter un rendez-vous (prend la place de « Rejoindre »). */
    private val add = Button(context, null, 0, R.style.ActionButton).apply {
        setText(R.string.event_add)
        visibility = View.GONE
        // Sans agenda relié, le plus utile au bureau : relier Outlook ou Google (page ouverte sur l'ordinateur).
        setOnClickListener { if (host.features.linked) host.editEvent(null) else host.showCalendars() }
    }
    private val texts = column()
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val main = LinearLayout(context)
    private val after = column()
    private val afterLabel = label(15f, R.attr.c_text_faint).apply { setText(R.string.next_prefix) }
    private val afterBlock = column()
    private val divider = View(context).apply { setBackgroundColor(color(R.attr.c_stroke)) }
    override val view = FrameLayout(context).asCard()

    private var face = NextFace.of(placed.size)
    private var joinable: CalendarEvent? = null

    /** Suivants affichés : reconstruits seulement quand ils changent. */
    private var followingKey: String? = null

    /** Aperçu du catalogue : la journée d'exemple à la place de l'agenda de l'ordinateur. */
    private var sample: List<CalendarEvent>? = null
    private val events get() = sample ?: host.events

    init {
        // La hauteur d'une rangée dépend du profil de l'Accueil (RowProfile) : les tailles se recalculent à chaque changement.
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) view.post(::fit)
        }
    }

    override fun applySize() {
        face = NextFace.of(placed.size)
        for (part in listOf(ring, header, title, whenText, details, join, add, texts, actions, main, after, afterLabel, afterBlock, divider)) {
            (part.parent as? ViewGroup)?.removeView(part)
        }
        listOf(texts, actions, main, afterBlock).forEach { it.removeAllViews() }
        view.removeAllViews()
        val pill = family == ShapeFamily.PILL
        val pad = dp(if (pill) PILL_PAD_DP else PAD_DP)
        view.setPadding(pad, pad, pad, pad)
        // Pastille et Ligne : l'anneau dit déjà ce que c'est, pas de titre de carte.
        val withHeader = family != ShapeFamily.PILL && family != ShapeFamily.ROW
        if (withHeader) texts.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        texts.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        texts.addView(whenText, LinearLayout.LayoutParams(MATCH, WRAP))
        if (face.details) texts.addView(details, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        actions.addView(join, LinearLayout.LayoutParams(WRAP, WRAP))
        actions.addView(add, LinearLayout.LayoutParams(WRAP, WRAP))
        // Pastille : l'icône seule, la place manque pour le mot.
        join.text = if (pill) "" else context.getString(R.string.join_meeting)
        join.minWidth = 0
        join.minimumWidth = 0
        main.orientation = if (face.across) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        main.gravity = if (face.across) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        val gap = dp(if (pill) PILL_GAP_DP else GAP_DP)
        // La marge suit l'anneau : sans rendez-vous, il disparaît et le texte reste calé au bord.
        main.addView(ring, LinearLayout.LayoutParams(0, 0).apply { if (face.across) marginEnd = gap else bottomMargin = gap })
        if (face.across) {
            main.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            // Ligne : Rejoindre à droite du titre ; ailleurs, sous les textes.
            if (family == ShapeFamily.ROW) main.addView(actions, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = gap })
            else texts.addView(actions, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = gap })
        } else {
            main.addView(texts, LinearLayout.LayoutParams(MATCH, WRAP))
            texts.addView(actions, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = gap })
        }
        followingKey = null
        view.addView(content(), FrameLayout.LayoutParams(MATCH, MATCH))
        onCalendar()
        view.post(::fit)
    }

    /** L'anneau et ses textes, puis les suivants : à droite (large et basse) ou dessous. */
    private fun content(): View {
        if (face.following == 0) return main
        afterBlock.addView(afterLabel)
        afterBlock.addView(after, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        return if (face.followBeside) {
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(main, LinearLayout.LayoutParams(0, WRAP, MAIN_WEIGHT))
                addView(divider, LinearLayout.LayoutParams(dp(1), MATCH).apply { marginStart = dp(GAP_DP); marginEnd = dp(GAP_DP) })
                addView(afterBlock, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        } else {
            column().apply {
                addView(main, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(divider, LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(GAP_DP); bottomMargin = dp(GAP_DP) })
                addView(afterBlock, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
    }

    /** Diamètre de l'anneau et tailles des textes sur la place réelle, puis autant de suivants qu'il en tient. */
    private fun fit() {
        val w = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        val h = (view.height - view.paddingTop - view.paddingBottom).toFloat()
        if (w <= 0 || h <= 0) return
        val mainW = if (face.following > 0 && face.followBeside) w * MAIN_WEIGHT / (MAIN_WEIGHT + 1) else w
        val mainH = if (face.following > 0 && !face.followBeside) h * BELOW_MAIN_PART else h
        // Debout (Colonne basse) : jamais sous 72 dp, la valeur et son unité doivent tenir dans le cercle.
        val d = if (face.across) minOf(mainH, mainW * ACROSS_RING_PART) else minOf(maxOf(minOf(mainW * STACK_RING_W, mainH * STACK_RING_H), dp(MIN_RING_DP).toFloat()), mainW)
        ring.layoutParams = (ring.layoutParams as LinearLayout.LayoutParams).apply {
            width = d.toInt()
            height = d.toInt()
        }
        val pill = family == ShapeFamily.PILL
        val titlePx = (d * TITLE_PART).coerceIn(sp(MIN_SP), sp(if (pill) PILL_TITLE_MAX_SP else TITLE_MAX_SP))
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx)
        whenText.setTextSize(TypedValue.COMPLEX_UNIT_PX, (titlePx * SUB_PART).coerceAtLeast(sp(MIN_SP)))
        details.setTextSize(TypedValue.COMPLEX_UNIT_PX, (titlePx * SUB_PART).coerceAtLeast(sp(MIN_SP)))
        ring.requestLayout()
        view.post(::fitFollowing)
    }

    override fun onMinute() = onCalendar()

    override fun onCalendar() {
        val now = System.currentTimeMillis()
        val events = events
        val upcoming = nextEvent(events, now, host.chosenId)
        val chosen = upcoming?.takeIf { it.id == host.chosenId }
        labelView.put(context.getString(if (chosen != null) R.string.chosen_label else R.string.next_label))
        val today = events.count { !it.allDay && daysFrom(now, it.startMs) == 0 }
        todayCount.put(if (today > 0) context.getString(R.string.next_today, today) else "")
        joinable = upcoming?.takeIf { sample == null && DayBrief.canJoin(it, now, chosen != null, host.features.meetings) }
        join.visibility = if (joinable == null) View.GONE else View.VISIBLE
        // Pastille : le titre sur une ligne quand Rejoindre est là, sinon le bouton sortirait de la rangée fine.
        title.maxLines = when {
            family == ShapeFamily.PILL -> if (joinable != null) 1 else 2
            family == ShapeFamily.ROW -> 2
            else -> 3
        }
        // Rien de prévu : l'action pour noter un rendez-vous. Ordinateur injoignable et rien de lu : on ne dit pas « rien de prévu ».
        val empty = upcoming == null && host.unreachable == null
        add.visibility = if (empty && sample == null) View.VISIBLE else View.GONE
        ring.visibility = if (upcoming == null) View.GONE else View.VISIBLE
        whenText.visibility = if (upcoming == null || family == ShapeFamily.PILL) View.GONE else View.VISIBLE
        details.visibility = if (upcoming == null || !face.details) View.GONE else View.VISIBLE
        renderFollowing(events, upcoming, now)
        if (upcoming == null) {
            add.setText(if (host.features.linked) R.string.event_add else R.string.agenda_link_now)
            title.put(if (!empty) "" else context.getString(if (host.features.linked) R.string.next_none_today else R.string.next_not_linked))
            return
        }
        title.put(upcoming.title)
        renderRing(upcoming, now)
        val text = nextLine(upcoming, now).whenText(context)
        whenText.put(if (face.details || upcoming.location.isBlank()) text else "$text · ${upcoming.location}")
        details.put(detailsOf(upcoming))
    }

    /** Au centre : les minutes qui restent avant (ou pendant) la réunion, sinon son heure et, plus loin, le jour. */
    private fun renderRing(event: CalendarEvent, now: Long) {
        val center = NextRing.center(event, now)
        val unit = when (center.unit) {
            NextRing.Unit.NONE -> ""
            NextRing.Unit.MIN -> context.getString(R.string.next_ring_min)
            NextRing.Unit.NOW -> context.getString(R.string.next_ring_now)
            NextRing.Unit.TOMORROW -> context.getString(R.string.next_ring_tomorrow)
            NextRing.Unit.DAY -> nextShortDay.format(Date(event.startMs))
        }
        ring.show(NextRing.fraction(event, now), center.value, unit)
    }

    /** Même texte : pas de nouvelle mise en page (une fois par minute sur la tablette). */
    private fun TextView.put(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    /** « Salle Lune · 14:45 – 15:45 · 1 h ». */
    private fun detailsOf(event: CalendarEvent): String {
        val span = context.getString(R.string.next_span, nextTime.format(Date(event.startMs)), nextTime.format(Date(event.endMs)))
        val split = NextRing.length(((event.endMs - event.startMs) / MINUTE).toInt())
        val length = when {
            split.hours == 0 -> context.getString(R.string.duration_minutes, split.minutes)
            split.minutes == 0 -> context.getString(R.string.next_duration_h, split.hours)
            else -> context.getString(R.string.next_duration_hm, split.hours, split.minutes)
        }
        return listOf(event.location, span, length).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Les rendez-vous qui suivent le prochain : l'heure (ou le jour) et le titre. */
    private fun renderFollowing(events: List<CalendarEvent>, upcoming: CalendarEvent?, now: Long) {
        if (face.following == 0) return
        val following = if (upcoming == null) emptyList() else events.filter { !it.allDay && it.endMs > now && it !== upcoming }.take(face.following)
        // Mêmes suivants, même jour : rien à reconstruire (la carte repasse chaque minute).
        val key = following.joinToString("|") { "${it.id}/${daysFrom(now, it.startMs) == 0}/${it.title}" }
        if (key == followingKey) return
        followingKey = key
        after.removeAllViews()
        val show = if (following.isEmpty()) View.GONE else View.VISIBLE
        divider.visibility = show
        afterBlock.visibility = show
        if (show == View.GONE) return
        Look.revealOnce(after)
        for (event in following) {
            after.addView(
                LinearLayout(context).apply {
                    val sameDay = daysFrom(now, event.startMs) == 0
                    addView(
                        label(15f, R.attr.c_text_dim).apply {
                            text = if (sameDay) nextTime.format(Date(event.startMs)) else nextShortDay.format(Date(event.startMs)).replaceFirstChar { it.uppercase() }
                            minWidth = dp(TIME_COL_DP)
                        },
                    )
                    addView(label(15f).apply { text = event.title; oneLine() }, LinearLayout.LayoutParams(0, WRAP, 1f))
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { if (after.childCount > 0) topMargin = dp(ROW_GAP_DP) },
            )
        }
        view.post(::fitFollowing)
    }

    /**
     * Cache les suivants qui ne tiennent pas ; sans aucun, ni filet ni « Ensuite » (gardés en place, invisibles). La place
     * se lit sur la carte (à droite) ou sur le bloc du dessous, qui ne dépendent pas des lignes cachées.
     */
    private fun fitFollowing() {
        if (face.following == 0 || after.childCount == 0 || after.width == 0 || afterBlock.visibility == View.GONE) return
        val room = if (face.followBeside) view.height - view.paddingTop - view.paddingBottom else afterBlock.height
        var free = room - after.top
        var fitting = 0
        for (i in 0 until after.childCount) {
            val row = after.getChildAt(i)
            row.measure(View.MeasureSpec.makeMeasureSpec(after.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            val need = row.measuredHeight + if (i > 0) dp(ROW_GAP_DP) else 0
            if (need > free) break
            free -= need
            fitting++
        }
        for (i in 0 until after.childCount) after.getChildAt(i).visibility = if (i < fitting) View.VISIBLE else View.GONE
        val show = if (fitting > 0) View.VISIBLE else View.INVISIBLE
        divider.visibility = show
        afterBlock.visibility = show
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is CalendarSample) return
        this.sample = CalendarSample.events(System.currentTimeMillis(), context::getString)
        onCalendar()
    }

    private fun joinNext() {
        joinable?.let { joinMeeting(host, it, join) }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun TextView.oneLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MINUTE = 60_000L

        const val PAD_DP = 20
        const val PILL_PAD_DP = 12
        const val GAP_DP = 16
        const val PILL_GAP_DP = 10
        const val ROW_GAP_DP = 10
        const val TIME_COL_DP = 56
        const val MIN_RING_DP = 72

        /** Large et basse : l'anneau et ses textes prennent ce poids, les suivants 1. */
        const val MAIN_WEIGHT = 1.4f

        /** Suivants dessous : part de la hauteur laissée à l'anneau et ses textes. */
        const val BELOW_MAIN_PART = 0.55f

        /** Diamètre de l'anneau : à côté du titre, part de la largeur au plus ; au-dessus, part de la largeur et de la hauteur. */
        const val ACROSS_RING_PART = 0.42f
        const val STACK_RING_W = 0.8f
        const val STACK_RING_H = 0.42f

        /** Titre en part du diamètre, borné ; le moment et les détails en part du titre. */
        const val TITLE_PART = 0.16f
        const val SUB_PART = 0.78f
        const val MIN_SP = 14f
        const val PILL_TITLE_MAX_SP = 17f
        const val TITLE_MAX_SP = 30f
    }
}
