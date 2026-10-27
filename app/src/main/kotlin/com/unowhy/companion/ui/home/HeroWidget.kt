package com.unowhy.companion.ui.home

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.unowhy.companion.R
import com.unowhy.companion.focus.Water
import com.unowhy.companion.ui.RollingText
import com.unowhy.companion.ui.WaterSettings
import com.unowhy.companion.ui.themeFont
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Héros de l'Accueil (DA Cadran) : taille de l'heure selon la place, sans fond. Pleine hauteur (une ligne de l'Accueil
 * Cadran) : l'heure à 184 px, la date, « Ensuite … » et les complications. Plus bas (autres thèmes, panneau document
 * ouvert) : l'heure et la date, la météo et l'eau seulement s'il y a la largeur.
 */
class HeroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private lateinit var clock: RollingText
    private lateinit var next: View
    private lateinit var compl: View

    /** Il y a un prochain rendez-vous à dire : « Ensuite … » apparaît si la hauteur le permet. */
    var nextWanted = false
        set(value) { if (field != value) { field = value; requestLayout() } }

    override fun onFinishInflate() {
        super.onFinishInflate()
        clock = findViewById(R.id.hero_clock)
        next = findViewById(R.id.hero_next)
        compl = findViewById(R.id.hero_compl)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val full = h >= FULL_HEIGHT * density
        // Ligne de l'heure : 176 pour 184 de corps ; dessous, la date (38) et « Ensuite » (30) ne tiennent que sur une ligne haute.
        val size = minOf(MAX_CLOCK * density, 0.45f * w, if (full) Float.MAX_VALUE else (h - 40 * density) / LINE_RATIO)
        clock.setSize(size, (size * LINE_RATIO).roundToInt(), size.roundToInt())
        show(next, full && nextWanted)
        show(compl, w >= COMPL_WIDTH * density)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun show(view: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) view.visibility = target
    }

    private companion object {
        const val FULL_HEIGHT = 220
        const val MAX_CLOCK = 184f
        const val COMPL_WIDTH = 760
        const val LINE_RATIO = 176f / 184f
    }
}

/**
 * Héros : heure qui bascule chiffre par chiffre à chaque minute, date, « Ensuite … dans N min », météo (réglable par ⚙ en
 * mode Modifier) et eau (toucher = un verre, appui long = réglages). Rien ne bouge entre deux minutes.
 */
class HeroWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LayoutInflater.from(context).inflate(R.layout.widget_hero, FrameLayout(context), false) as HeroView
    private val clock = view.findViewById<RollingText>(R.id.hero_clock)
    private val date = view.findViewById<TextView>(R.id.hero_date)
    private val next = view.findViewById<View>(R.id.hero_next)
    private val nextPrefix = view.findViewById<TextView>(R.id.hero_next_prefix)
    private val nextTitle = view.findViewById<TextView>(R.id.hero_next_title)
    private val nextWhen = view.findViewById<TextView>(R.id.hero_next_when)
    private val nextIn = view.findViewById<View>(R.id.hero_next_in)
    private val nextCount = view.findViewById<RollingText>(R.id.hero_next_count)
    private val wxTemp = view.findViewById<TextView>(R.id.hero_wx_temp)
    private val wxLabel = view.findViewById<TextView>(R.id.hero_wx_label)
    private val glasses = view.findViewById<GlassBars>(R.id.hero_glasses)
    private val glassesCount = view.findViewById<RollingText>(R.id.hero_glasses_count)
    private val glassesLabel = view.findViewById<TextView>(R.id.hero_glasses_label)

    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val waterListener: (Water.State) -> Unit = { renderWater(it, animate = true) }
    private var active = false
    private var forecast: Weather.Forecast? = null
    private var fetchedAt = 0L

    override val hasSettings get() = true

    init {
        val sp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics) }
        clock.typeface = themeFont(context, R.attr.c_font_light)
        clock.color = color(R.attr.c_text)
        nextCount.typeface = ResourcesCompat.getFont(context, R.font.outfit_500)
        nextCount.cellEm = 0.58f
        nextCount.minCells = 2
        nextCount.color = color(R.attr.c_accent)
        nextCount.setSize(sp(17f), dp(24))
        glassesCount.cellEm = 0.58f
        glassesCount.color = color(R.attr.c_text_dim)
        glassesCount.typeface = ResourcesCompat.getFont(context, R.font.outfit_400)
        glassesCount.setSize(sp(14f), dp(20))
        view.findViewById<View>(R.id.hero_weather).setOnClickListener { if (!placed.config.has("lat")) showSettings() }
        val water = view.findViewById<View>(R.id.hero_water)
        water.setOnClickListener {
            val state = Water.state(context)
            if (state.glasses < state.goal) Water.add(context, 1)
        }
        water.setOnLongClickListener { WaterSettings.show(context); true }
        render(animate = false)
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (active) {
            Water.observe(waterListener)
            render(animate = false)
        } else {
            Water.unobserve(waterListener)
        }
    }

    override fun onMinute() = render(animate = active)

    override fun onCalendar() = renderNext(animate = false)

    private fun render(animate: Boolean) {
        val now = System.currentTimeMillis()
        clock.set(SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now)), animate)
        date.text = dateFormat.format(Date(now)).replaceFirstChar { it.uppercase() }
        renderNext(animate)
        renderWeather()
        renderWater(Water.state(context), animate)
    }

    /** « Ensuite <titre> dans N min » : le compteur bascule chaque minute ; plus tard ou demain, le moment en texte. */
    private fun renderNext(animate: Boolean) {
        val line = nextLine(host.events, System.currentTimeMillis(), host.chosenId)
        view.nextWanted = line != null
        if (line == null) return
        nextPrefix.visibility = if (line is NextLine.Now) View.GONE else View.VISIBLE
        nextTitle.text = line.title
        if (line is NextLine.InMinutes) {
            nextWhen.visibility = View.GONE
            nextIn.visibility = View.VISIBLE
            nextCount.set(line.minutes.toString(), animate)
        } else {
            nextIn.visibility = View.GONE
            nextWhen.visibility = View.VISIBLE
            nextWhen.text = line.whenText(context)
            nextWhen.setTextColor(color(if (line is NextLine.Now) R.attr.c_accent else R.attr.c_text_dim))
        }
    }

    private fun renderWeather() {
        val f = forecast
        val named = placed.config.optString("city").isNotBlank()
        if (f == null) {
            wxTemp.setCompoundDrawablesRelative(null, null, null, null)
            wxTemp.text = ""
            wxLabel.setText(if (named) R.string.loading else R.string.widget_weather_choose)
            return
        }
        val sky = Weather.describe(f.code)
        wxTemp.setCompoundDrawablesRelativeWithIntrinsicBounds(sky.icon, 0, 0, 0)
        wxTemp.text = context.getString(R.string.widget_weather_temp, f.temperature.roundToInt())
        wxLabel.text = context.getString(R.string.hero_wx_label, context.getString(sky.text), f.max.roundToInt())
    }

    private fun renderWater(state: Water.State, animate: Boolean) {
        glasses.show(state.glasses, state.goal, animate)
        glassesCount.set(state.glasses.toString(), animate)
        glassesLabel.text = context.getString(R.string.hero_glasses, state.goal)
    }

    override suspend fun load() {
        if (!placed.config.has("lat")) return renderWeather()
        if (forecast != null && SystemClock.elapsedRealtime() - fetchedAt < Weather.REFRESH_MS) return
        forecast = runCatching { Weather.forecast(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: forecast
        if (forecast != null) fetchedAt = SystemClock.elapsedRealtime()
        renderWeather()
    }

    override fun showSettings() = Weather.chooseCity(context, host.scope, placed.config) {
        forecast = null
        host.save()
        renderWeather()
        host.scope.launch { load() }
    }
}
