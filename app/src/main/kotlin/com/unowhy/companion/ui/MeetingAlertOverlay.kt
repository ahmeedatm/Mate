package com.unowhy.companion.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.device.Screen
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.CalendarEvent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Alerte réunion (Réglages > Tablette) : 5 min avant le début d'un événement horaire de l'agenda, un plein écran par-dessus
 * tout avec le titre, l'heure et un compte à rebours qui passe au rouge sous 1 min. Un toucher le ferme pour cette
 * réunion ; il part seul au début. Le compte à rebours ne tourne que pendant l'affichage (une mise à jour par seconde).
 * L'agenda est relu au plus toutes les 5 min, la vérification se fait chaque minute (MainActivity.onMinute).
 */
class MeetingAlertOverlay(private val activity: MainActivity) : FrameLayout(activity) {

    private var events: List<CalendarEvent> = emptyList()
    private var loadedAt = 0L
    private var loading = false
    private var shown: CalendarEvent? = null
    private var lastMinutes = -1

    private fun color(attr: Int) = themeColor(context, attr)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    private val title = text(40f, R.attr.c_text).apply { typeface = themeFont(context, R.attr.c_font_medium); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val start = text(24f, R.attr.c_text_dim)
    private val countdown = text(120f, R.attr.c_text).apply { typeface = themeFont(context, R.attr.c_font_display); fontFeatureSettings = "tnum" }
    private val hint = text(16f, R.attr.c_text_faint).apply { setText(R.string.meeting_alert_close) }

    init {
        setBackgroundColor(ColorUtils.setAlphaComponent(color(R.attr.c_bg), 0xF2))
        isClickable = true // les touchers ne passent pas à l'écran dessous ; toute la surface ferme
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { hide() }
        visibility = GONE
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Lu d'un bloc par la racine, qui porte la description.
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(48), dp(24), dp(48), dp(24))
            addView(title)
            addView(start, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            addView(countdown, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
            addView(hint, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
        }
        addView(column, LayoutParams(MATCH, WRAP, Gravity.CENTER))
    }

    private fun text(size: Float, colorAttr: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorAttr))
        gravity = Gravity.CENTER
    }

    private val tick = object : Runnable {
        override fun run() {
            val event = shown ?: return
            val left = MeetingAlertModel.remainingMs(event, System.currentTimeMillis())
            if (left <= 0) {
                hide()
                return
            }
            render(event, left)
            postDelayed(this, 1_000 - System.currentTimeMillis() % 1_000)
        }
    }

    /** Chaque minute (et au démarrage) : relit l'agenda s'il est périmé, puis annonce la réunion qui approche. */
    fun onMinute() {
        if (!Prefs.meetingAlert(context) || Computers.all(context).isEmpty()) {
            hide()
            return
        }
        val now = System.currentTimeMillis()
        if (!loading && now - loadedAt > RELOAD_MS) {
            loading = true
            activity.lifecycleScope.launch {
                // Relecture silencieuse : en cas d'erreur on garde l'agenda d'avant et on réessaie à la minute suivante.
                runCatching { BackendClient(activity).calendar.days(2).events }.onSuccess {
                    events = it
                    loadedAt = System.currentTimeMillis()
                }
                loading = false
                check()
            }
        } else {
            check()
        }
    }

    private fun check() {
        if (shown != null || !Prefs.meetingAlert(context)) return
        val now = System.currentTimeMillis()
        val seen = Prefs.meetingAlertSeen(context)
        val event = MeetingAlertModel.due(events, now, seen) ?: return
        if (activity.meetingAlertBlocked()) return
        Prefs.setMeetingAlertSeen(context, MeetingAlertModel.remember(seen, MeetingAlertModel.keyOf(event), now))
        show(event)
    }

    private fun show(event: CalendarEvent) {
        shown = event
        lastMinutes = -1
        title.text = event.title
        start.text = context.getString(R.string.meeting_alert_start, timeFormat.format(event.startMs))
        visibility = VISIBLE
        bringToFront()
        Screen.touched(activity)
        Look.enter(this, 0)
        tick.run()
    }

    private fun render(event: CalendarEvent, left: Long) {
        countdown.text = MeetingAlertModel.countdown(left)
        countdown.setTextColor(color(if (MeetingAlertModel.urgent(left)) R.attr.c_ko else R.attr.c_text))
        // Description lue par l'accessibilité : mise à jour à chaque minute entamée seulement, pas à chaque seconde.
        val minutes = MeetingAlertModel.minutesLeft(left)
        if (minutes == lastMinutes) return
        val first = lastMinutes < 0
        lastMinutes = minutes
        contentDescription = if (left <= MeetingAlertModel.URGENT_MS) context.getString(R.string.meeting_alert_desc_soon, event.title)
        else context.getString(R.string.meeting_alert_desc, event.title, minutes)
        if (first) announceForAccessibility(contentDescription)
    }

    fun hide() {
        removeCallbacks(tick)
        shown = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        const val RELOAD_MS = 5 * 60_000L
    }
}
