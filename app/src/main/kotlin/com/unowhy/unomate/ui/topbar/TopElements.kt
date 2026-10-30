package com.unowhy.unomate.ui.topbar

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.agents.AgentsFeed
import com.unowhy.unomate.agents.Column
import com.unowhy.unomate.agents.columnCounts
import com.unowhy.unomate.device.Tablet
import com.unowhy.unomate.focus.FocusTimer
import com.unowhy.unomate.net.AudioState
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.CalendarEvent
import com.unowhy.unomate.net.runCatchingCancellable
import com.unowhy.unomate.ui.LatestSender
import com.unowhy.unomate.ui.MusicChip
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.home.DayBrief
import com.unowhy.unomate.ui.home.NextLine
import com.unowhy.unomate.ui.home.Weather
import com.unowhy.unomate.ui.themeColor
import com.unowhy.unomate.ui.themeFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Un élément du bandeau du haut : sa vue, et ce qu'il relit entre [start] (bandeau à l'écran) et [stop]. Ceux qui n'ont
 * rien à montrer (TopItem.self) se cachent seuls ; ceux qui ont besoin de l'ordinateur se cachent sans lui.
 */
abstract class TopElement(val item: TopItem, protected val host: TopBarHost) {
    protected val context: Context get() = host.context
    abstract val view: View
    open fun start() = Unit
    open fun stop() = Unit

    /** Texte du bandeau : petit, atténué, comme l'heure d'avant. */
    protected fun label(colorAttr: Int = R.attr.c_text_dim) = TextView(context).apply {
        textSize = 14f
        isSingleLine = true
        typeface = themeFont(context, R.attr.c_font_medium)
        setTextColor(themeColor(context, colorAttr))
    }

    protected fun icon(res: Int, colorAttr: Int = R.attr.c_text_dim, size: Int = 18) = ImageView(context).apply {
        setImageResource(res)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
        tint(this, colorAttr)
    }

    protected fun tint(view: ImageView, colorAttr: Int) =
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(themeColor(context, colorAttr)))

    /** Icône puis texte, sur une ligne ; touchable si [onTap]. */
    protected fun row(vararg views: View, onTap: (() -> Unit)? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            if (i > 0) (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)).also {
                it.marginStart = context.dp(6)
                v.layoutParams = it
            }
            addView(v)
        }
        if (onTap != null) {
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            minimumHeight = context.dp(36)
            setOnClickListener { onTap() }
        }
    }

    companion object {
        fun create(item: TopItem, host: TopBarHost): TopElement = when (item) {
            TopItem.MUSIC -> MusicElement(host)
            TopItem.MEETING -> MeetingElement(host)
            TopItem.FOCUS -> FocusElement(host)
            TopItem.CALL -> CallElement(host)
            TopItem.VOLUME -> VolumeElement(host)
            TopItem.AGENTS -> AgentsElement(host)
            TopItem.NOTIFICATIONS -> NotificationsElement(host)
            TopItem.WEATHER -> WeatherElement(host)
            TopItem.DATE -> DateElement(host)
            TopItem.CLOCK -> ClockElement(host)
            TopItem.WIFI -> WifiElement(host)
            TopItem.BATTERY -> BatteryElement(host)
        }
    }
}

/** Lecture en cours : la pastille d'avant ([MusicChip]), qui se relit et se cache seule. */
class MusicElement(host: TopBarHost) : TopElement(TopItem.MUSIC, host) {
    val chip = MusicChip(context).apply {
        setBackgroundResource(R.drawable.music_chip_bg)
        setPadding(context.dp(5), 0, context.dp(5), 0)
        layoutParams = LinearLayout.LayoutParams(context.dp(320), context.dp(32))
        suppressed = host::musicSuppressed
    }
    override val view: View = chip
}

class ClockElement(host: TopBarHost) : TopElement(TopItem.CLOCK, host) {
    private val format = SimpleDateFormat("HH:mm", Locale.FRANCE)
    override val view = label()
    private val tick: () -> Unit = { render() }

    override fun start() {
        host.signals.onMinute(tick)
        render()
    }

    override fun stop() {
        host.signals.remove(tick)
    }

    private fun render() {
        view.text = format.format(Date())
    }
}

/** « Ven. 30 oct. » : courte, elle tient à côté de l'heure. */
class DateElement(host: TopBarHost) : TopElement(TopItem.DATE, host) {
    private val format = SimpleDateFormat("EEE d MMM", Locale.FRANCE)
    override val view = label()
    private val tick: () -> Unit = { render() }

    override fun start() {
        host.signals.onMinute(tick)
        render()
    }

    override fun stop() {
        host.signals.remove(tick)
    }

    private fun render() {
        view.text = format.format(Date()).replaceFirstChar { it.uppercase() }
    }
}

class WifiElement(host: TopBarHost) : TopElement(TopItem.WIFI, host) {
    override val view = icon(R.drawable.ic_wifi).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val changed: () -> Unit = { render() }

    override fun start() {
        host.signals.onWifi(changed)
        render()
    }

    override fun stop() {
        host.signals.remove(changed)
    }

    private fun render() {
        val signal = Tablet.wifiLevel(context)
        // Signal faible : arcs estompés en plus de l'ambre (pas la couleur seule).
        view.setImageResource(
            when {
                signal == null -> R.drawable.ic_wifi_off
                signal <= 1 -> R.drawable.ic_wifi_weak
                else -> R.drawable.ic_wifi
            },
        )
        tint(
            view,
            when {
                signal == null -> R.attr.c_ko
                signal <= 1 -> R.attr.c_warn
                else -> R.attr.c_text_dim
            },
        )
        view.contentDescription = context.getString(
            when {
                signal == null -> R.string.wifi_off
                signal <= 1 -> R.string.wifi_weak
                else -> R.string.wifi_current_unnamed
            },
        )
    }
}

class BatteryElement(host: TopBarHost) : TopElement(TopItem.BATTERY, host) {
    private val icon = icon(R.drawable.ic_battery)
    private val level = label()
    override val view = row(icon, level).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val changed: (Tablet.Battery) -> Unit = { render(it) }

    override fun start() {
        host.signals.onBattery(changed)
        host.signals.battery?.let(::render)
    }

    override fun stop() {
        host.signals.remove(changed)
    }

    private fun render(state: Tablet.Battery) {
        icon.setImageResource(
            when {
                state.charging -> R.drawable.ic_battery_charging
                state.low || state.healthIssue -> R.drawable.ic_battery_alert
                else -> R.drawable.ic_battery
            },
        )
        val color = when {
            state.low -> R.attr.c_ko
            state.charging -> R.attr.c_ok
            else -> R.attr.c_text_dim
        }
        tint(icon, color)
        level.setTextColor(themeColor(context, color))
        level.text = context.getString(R.string.percent, state.level)
        // L'icône dit déjà « en charge » et « faible » ; TalkBack aussi.
        view.contentDescription = context.getString(
            when {
                state.charging -> R.string.tablet_battery_state_charging
                state.low -> R.string.tablet_battery_state_low
                else -> R.string.tablet_battery_state
            },
            state.level,
        )
    }
}

/** Cloche : les notifications de l'ordinateur (Depuis l'ordi > Notifications), point quand du nouveau est arrivé. */
class NotificationsElement(host: TopBarHost) : TopElement(TopItem.NOTIFICATIONS, host) {
    private val bell = ImageView(context).apply {
        setImageResource(R.drawable.ic_notifications)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ImageViewCompat.setImageTintList(this, androidx.core.content.ContextCompat.getColorStateList(context, R.color.rail_profile_content))
    }
    private val dot = View(context).apply {
        setBackgroundResource(R.drawable.dot)
        backgroundTintList = ColorStateList.valueOf(themeColor(context, R.attr.c_accent))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    override val view: View = android.widget.FrameLayout(context).apply {
        setBackgroundResource(R.drawable.rail_item_bg)
        contentDescription = context.getString(R.string.notif_open)
        layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        addView(bell, android.widget.FrameLayout.LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER))
        addView(
            dot,
            android.widget.FrameLayout.LayoutParams(context.dp(9), context.dp(9), Gravity.TOP or Gravity.END).apply {
                topMargin = context.dp(8)
                marginEnd = context.dp(8)
            },
        )
        setOnClickListener { host.openNotifications() }
    }

    override fun start() = render(ring = false)

    fun render(ring: Boolean) {
        view.isSelected = host.notificationsShown()
        bell.isSelected = view.isSelected
        dot.visibility = if (host.notificationsUnread()) View.VISIBLE else View.GONE
        if (ring && dot.visibility == View.VISIBLE) com.unowhy.unomate.ui.Look.ring(bell, dot)
    }
}

/** Chrono Focus : les minutes restantes tant qu'il tourne ou qu'il est en pause ; toucher = pause ou reprise. */
class FocusElement(host: TopBarHost) : TopElement(TopItem.FOCUS, host) {
    private val icon = icon(R.drawable.ic_timer, R.attr.c_accent)
    private val text = label(R.attr.c_text)
    override val view = row(icon, text) { FocusTimer.toggle() }
    private var pill: DayBrief.FocusPill? = null

    // Le chrono prévient chaque seconde : le texte ne change qu'avec les minutes ou l'état.
    private val listener: (FocusTimer.State) -> Unit = { state -> DayBrief.focusPill(state).takeIf { it != pill }?.let(::render) }

    override fun start() {
        FocusTimer.observe(listener)
        render(DayBrief.focusPill(FocusTimer.state))
    }

    override fun stop() {
        FocusTimer.unobserve(listener)
    }

    private fun render(p: DayBrief.FocusPill) {
        pill = p
        view.visibility = if (p.kind == DayBrief.FocusPill.Kind.IDLE) View.GONE else View.VISIBLE
        text.text = context.getString(
            when (p.kind) {
                DayBrief.FocusPill.Kind.PAUSED -> R.string.top_focus_paused
                DayBrief.FocusPill.Kind.BREAK -> R.string.top_focus_break
                else -> R.string.top_focus_running
            },
            p.minutes,
        )
        icon.setImageResource(if (p.kind == DayBrief.FocusPill.Kind.PAUSED) R.drawable.ic_play else R.drawable.ic_timer)
    }
}

/**
 * Prochain rendez-vous, seulement dans l'heure qui vient ou en cours (« Point équipe · 12 min ») ; agenda relu toutes
 * les [RELOAD_MS]. Toucher : Rejoindre une réunion en ligne quand c'est possible.
 */
class MeetingElement(host: TopBarHost) : TopElement(TopItem.MEETING, host) {
    private val icon = icon(R.drawable.ic_event, R.attr.c_accent)
    private val text = label(R.attr.c_text).apply {
        maxWidth = context.dp(260)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private var shown: CalendarEvent? = null
    override val view = row(icon, text) { shown?.let { if (it.joinable) host.joinMeeting(it) } }
    private var events: List<CalendarEvent> = emptyList()
    private var job: Job? = null
    private val tick: () -> Unit = { render() }

    init {
        view.visibility = View.GONE
    }

    override fun start() {
        host.signals.onMinute(tick)
        job = host.scope.launch {
            while (isActive) {
                // Relecture silencieuse : en erreur, l'agenda d'avant reste.
                if (host.hasComputer()) runCatchingCancellable { BackendClient(context).calendar.days(1).events }.onSuccess { events = it }
                render()
                delay(RELOAD_MS)
            }
        }
    }

    override fun stop() {
        host.signals.remove(tick)
        job?.cancel()
    }

    private fun render() {
        val focus = DayBrief.focus(events, System.currentTimeMillis())
        val line = (focus as? DayBrief.Focus.Meeting)?.line
        shown = (focus as? DayBrief.Focus.Meeting)?.event?.takeIf { line is NextLine.InMinutes || line is NextLine.Now }
        view.visibility = if (shown != null && host.hasComputer()) View.VISIBLE else View.GONE
        text.text = when (line) {
            is NextLine.InMinutes -> context.getString(R.string.top_meeting_in, line.title, line.minutes)
            is NextLine.Now -> context.getString(R.string.top_meeting_now, line.title)
            else -> ""
        }
        view.isClickable = shown?.joinable == true
    }

    private companion object {
        const val RELOAD_MS = 5 * 60_000L
    }
}

/**
 * Micro de l'ordinateur : visible pendant un appel (relu toutes les [CALL_POLL_MS]) ou quand il est coupé (relu toutes les
 * [IDLE_POLL_MS]) ; rouge coupé. Toucher : couper ou rouvrir.
 */
class CallElement(host: TopBarHost) : TopElement(TopItem.CALL, host) {
    private val icon = icon(R.drawable.ic_mic, R.attr.c_text, 20)
    override val view = row(icon) { toggle() }
    private var muted: Boolean? = null
    private var job: Job? = null

    init {
        view.visibility = View.GONE
    }

    override fun start() {
        job = host.scope.launch {
            while (isActive) {
                if (host.hasComputer()) muted = runCatchingCancellable { BackendClient(context).system.audio().micMuted }.getOrNull()
                render()
                delay(if (host.callActive()) CALL_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    private fun render() {
        val off = muted == true
        view.visibility = if (host.hasComputer() && muted != null && (off || host.callActive())) View.VISIBLE else View.GONE
        icon.setImageResource(if (off) R.drawable.ic_mic_off else R.drawable.ic_mic)
        tint(icon, if (off) R.attr.c_ko else R.attr.c_text)
        view.contentDescription = context.getString(if (off) R.string.top_mic_off else R.string.top_mic_on)
    }

    private fun toggle() {
        val wanted = muted != true
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).system.setAudio(micMuted = wanted) }
                .onSuccess { muted = it.micMuted }
                .onFailure { host.showError(it) }
            render()
        }
    }

    private companion object {
        const val CALL_POLL_MS = 4_000L
        const val IDLE_POLL_MS = 30_000L
    }
}

/**
 * Son de l'ordinateur : haut-parleur (toucher = couper ou remettre) et barre de la sortie. Glisser envoie le plus récent
 * sans empiler ([LatestSender]) ; relu toutes les 10 s hors glisser, pour suivre un réglage fait sur l'ordinateur.
 */
class VolumeElement(host: TopBarHost) : TopElement(TopItem.VOLUME, host) {
    private val speaker = icon(R.drawable.ic_volume, R.attr.c_text, 20)
    // Construite sans style (le style Slider seul, sans parent, perdait la piste et le curseur d'Android) : ses teintes à la main.
    private val bar = SeekBar(context).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(themeColor(context, R.attr.c_accent))
        thumbTintList = ColorStateList.valueOf(themeColor(context, R.attr.c_accent))
        progressBackgroundTintList = ColorStateList.valueOf(themeColor(context, R.attr.c_text_faint))
        contentDescription = context.getString(R.string.top_volume)
        layoutParams = LinearLayout.LayoutParams(context.dp(BAR_DP), context.dp(36))
    }
    private val mute = row(speaker) { toggle() }
    override val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(mute)
        addView(bar)
    }
    private var state: AudioState? = null
    private var dragging = false
    private var job: Job? = null
    private val sender = LatestSender<Int>(host.scope) { value ->
        state = BackendClient(context).system.setAudio(output = value)
    }

    init {
        view.visibility = View.GONE
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) sender.offer(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                sender.offer(seekBar.progress)
            }
        })
    }

    override fun start() {
        job = host.scope.launch {
            while (isActive) {
                if (host.hasComputer() && !dragging) {
                    runCatchingCancellable { BackendClient(context).system.audio() }.onSuccess { state = it }
                }
                render()
                delay(POLL_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    private fun render() {
        val s = state
        // Sortie illisible (output null) : rien à régler, l'élément se cache.
        view.visibility = if (host.hasComputer() && s?.output != null) View.VISIBLE else View.GONE
        if (s?.output == null) return
        if (!dragging) bar.progress = s.output
        speaker.setImageResource(if (s.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        tint(speaker, if (s.muted) R.attr.c_ko else R.attr.c_text)
        mute.contentDescription = context.getString(if (s.muted) R.string.top_volume_unmute else R.string.top_volume_mute)
    }

    private fun toggle() {
        val wanted = !(state?.muted ?: false)
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).system.setAudio(muted = wanted) }
                .onSuccess { state = it }
                .onFailure { host.showError(it) }
            render()
        }
    }

    private companion object {
        const val POLL_MS = 10_000L
        const val BAR_DP = 140
    }
}

/** Agents Claude qui attendent une réponse (Mac) : le nombre, caché à zéro ; toucher = le tableau. */
class AgentsElement(host: TopBarHost) : TopElement(TopItem.AGENTS, host) {
    private val icon = icon(R.drawable.ic_agents, R.attr.c_agent_wait)
    private val text = label(R.attr.c_text)
    override val view = row(icon, text) { host.openAgents() }
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null

    init {
        view.visibility = View.GONE
    }

    override fun start() {
        if (!host.agentsOffered()) return
        val listener: (AgentsFeed.Snapshot) -> Unit = { snapshot ->
            val waiting = snapshot.state?.let { columnCounts(it.counts)[Column.YOU] } ?: 0
            view.visibility = if (waiting > 0) View.VISIBLE else View.GONE
            text.text = context.getString(R.string.top_agents_waiting, waiting)
        }
        observer = listener
        AgentsFeed.observe(context, listener)
    }

    override fun stop() {
        observer?.let { AgentsFeed.unobserve(it) }
        observer = null
    }
}

/**
 * Météo : icône du temps et température. Ville propre au bandeau (Prefs.topWeather), prise au départ d'un widget de
 * l'Accueil qui en a une, sinon la position si l'option des Réglages le permet ; sans ville, toucher pour la choisir.
 */
class WeatherElement(host: TopBarHost) : TopElement(TopItem.WEATHER, host) {
    private val icon = icon(R.drawable.ic_cloud, R.attr.c_text_dim, 20)
    private val text = label()
    override val view = row(icon, text).apply { setOnClickListener { if (!config.has("lat")) choose() } }
    private val config = runCatching { JSONObject(Prefs.topWeather(context).orEmpty()) }.getOrElse { JSONObject() }
    private var job: Job? = null

    override fun start() {
        job = host.scope.launch {
            while (isActive) {
                load()
                delay(Weather.REFRESH_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    private suspend fun load() {
        if (!config.has("lat")) host.homeCity()?.let { city -> for (k in listOf("city", "lat", "lon")) config.put(k, city.opt(k)) }
        if (!config.has("lat")) Weather.autoCity(context, config)
        if (config.has("lat")) Prefs.setTopWeather(context, config.toString())
        if (!config.has("lat")) return render(null)
        val forecast = runCatchingCancellable { Weather.forecast(config.getDouble("lat"), config.getDouble("lon")) }.getOrNull()
        render(forecast)
    }

    private fun render(forecast: Weather.Forecast?) {
        if (forecast == null) {
            icon.setImageResource(R.drawable.ic_cloud)
            text.text = if (config.has("lat")) "" else context.getString(R.string.top_weather_city)
            view.contentDescription = context.getString(R.string.top_weather_choose)
            return
        }
        val sky = Weather.describe(forecast.code)
        icon.setImageResource(sky.icon)
        text.text = context.getString(R.string.top_weather_temp, forecast.temperature.roundToInt())
        view.contentDescription = "${config.optString("city")} : ${context.getString(sky.text)}, ${text.text}"
    }

    private fun choose() = Weather.chooseCity(context, host.scope, config) {
        Prefs.setTopWeather(context, config.toString())
        stop()
        start()
    }
}
