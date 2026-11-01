package app.mate.ui.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Météo de la ville choisie, lue par la tablette chez MET Norway (gratuit, sans clé ni compte, voir Weather), toutes les 30 min.
 * Petit : emoji et température ; moyen : en plus le temps qu'il fait, min / max du jour et la ville ; grand : en plus
 * les 4 jours suivants.
 */
class WeatherWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val emoji = label(44f)
    private val temp = label(40f, light = true)
    private val sky = label(17f).apply { maxLines = 2 }
    private val range = label(15f, R.attr.c_text_dim).apply { isSingleLine = true }
    private val city = label(14f, R.attr.c_text_faint).apply { isSingleLine = true }

    /** Petit : la colonne de détails n'a pas la place, la ville passe sous la température (trouvée seule, elle doit se voir). */
    private val smallCity = label(14f, R.attr.c_text_faint).apply { isSingleLine = true }
    private val details = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sky)
        addView(range)
        addView(city)
    }
    private val now = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(emoji)
        addView(temp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
    }

    /** Indice de qualité de l'air de la ville, coloré par tranche ([AirQuality]) ; caché sans mesure. */
    private val air = label(15f).apply { isSingleLine = true; visibility = android.view.View.GONE }

    /** Grand : une colonne par jour suivant (jour, emoji, min / max). */
    private val days = LinearLayout(context)
    override val view = LinearLayout(context).asCard(16).apply {
        setPadding(dp(18), dp(14), dp(18), dp(14))
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(now, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(smallCity)
        addView(air)
        addView(days, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        setOnClickListener { if (!placed.config.has("lat")) showSettings() }
    }

    private var fetchedAt = 0L
    private var floating: android.animation.ValueAnimator? = null
    private var current: Weather.Forecast? = null
    private var airIndex: Int? = null

    override val hasSettings get() = true

    /** L'emoji du temps flotte doucement tant que la page est à l'écran. */
    override fun setActive(active: Boolean) {
        if (active && floating == null) floating = Look.float(emoji)
        if (!active) {
            Look.stop(floating, emoji)
            floating = null
        }
    }

    override fun applySize() {
        val small = placed.size == WidgetSize.S
        details.visibility = if (small) android.view.View.GONE else android.view.View.VISIBLE
        smallCity.visibility = if (small) android.view.View.VISIBLE else android.view.View.GONE
        emoji.textSize = if (small) 34f else 44f
        temp.textSize = if (small) 34f else 40f
        days.visibility = if (placed.size == WidgetSize.L) android.view.View.VISIBLE else android.view.View.GONE
        render()
    }

    override suspend fun load() {
        // Pas encore de ville : celle de la position de la tablette, sinon « Choisir ma ville ».
        if (!placed.config.has("lat") && Weather.autoCity(context, placed.config)) host.save()
        if (!placed.config.has("lat")) return render()
        if (current != null && SystemClock.elapsedRealtime() - fetchedAt < Weather.REFRESH_MS) return
        current = runCatching { Weather.forecast(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: current
        if (current != null) fetchedAt = SystemClock.elapsedRealtime()
        // L'air est un plus : sans mesure ou sans réponse, la météo s'affiche seule (dernier indice gardé).
        airIndex = runCatching { AirQuality.index(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: airIndex
        render()
    }

    private fun render() {
        val name = placed.config.optString("city")
        val f = current
        if (name.isBlank()) {
            emoji.text = "🌤"
            temp.text = ""
            sky.setText(R.string.widget_weather_choose)
            range.text = ""
            city.text = ""
            smallCity.visibility = android.view.View.GONE
            details.visibility = android.view.View.VISIBLE
            // Grand sans ville : pas de rangée des jours vide sous l'invitation, qui reste au milieu de la carte.
            days.visibility = android.view.View.GONE
            air.visibility = android.view.View.GONE
            return
        }
        days.visibility = if (placed.size == WidgetSize.L) android.view.View.VISIBLE else android.view.View.GONE
        city.text = name
        smallCity.text = name
        // Sans ville, les détails s'affichent même en petit (« Choisir ma ville ») : la ville trouvée, on revient à la taille.
        val small = placed.size == WidgetSize.S
        details.visibility = if (small) android.view.View.GONE else android.view.View.VISIBLE
        smallCity.visibility = if (small) android.view.View.VISIBLE else android.view.View.GONE
        if (f == null) {
            emoji.text = "…"
            temp.text = ""
            sky.setText(R.string.loading)
            range.text = ""
            air.visibility = android.view.View.GONE
            return
        }
        val weather = Weather.describe(f.code)
        emoji.text = weather.emoji
        temp.text = context.getString(R.string.widget_weather_temp, f.temperature.roundToInt())
        sky.setText(weather.text)
        range.text = context.getString(R.string.widget_weather_range, f.min.roundToInt(), f.max.roundToInt())
        renderAir()
        renderDays(f.days)
    }

    private fun renderAir() {
        val index = airIndex
        air.visibility = if (index == null) android.view.View.GONE else android.view.View.VISIBLE
        if (index == null) return
        val level = AirQuality.levelOf(index)
        air.text = context.getString(R.string.widget_weather_air, index, context.getString(level.text))
        air.setTextColor(themeColor(context, level.color))
    }

    private fun renderDays(list: List<Weather.Day>) {
        days.removeAllViews()
        if (placed.size != WidgetSize.L) return
        for (day in list.take(4)) {
            val name = runCatching { dayName.format(isoDate.parse(day.date)!!) }.getOrDefault("").replaceFirstChar { it.uppercase() }
            days.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(label(15f, R.attr.c_text_dim).apply { text = name })
                    addView(label(30f).apply { text = Weather.describe(day.code).emoji })
                    addView(label(15f).apply { text = context.getString(R.string.widget_weather_day, day.min.roundToInt(), day.max.roundToInt()) })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    override fun showSettings() = Weather.chooseCity(context, host.scope, placed.config) {
        current = null
        airIndex = null
        host.save()
        render()
        host.scope.launch { load() }
    }

    private companion object {
        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.FRANCE)
    }
}
