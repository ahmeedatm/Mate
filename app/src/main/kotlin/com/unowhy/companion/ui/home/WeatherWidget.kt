package com.unowhy.companion.ui.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.unowhy.companion.R
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.themeRes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Météo de la ville choisie, lue par la tablette chez Open-Meteo (gratuit, sans clé ni compte), toutes les 30 min.
 * Petit : emoji et température ; moyen : en plus le temps qu'il fait, min / max du jour et la ville.
 */
class WeatherWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val emoji = label(44f)
    private val temp = label(40f, light = true)
    private val sky = label(17f).apply { maxLines = 2 }
    private val range = label(15f, R.attr.c_text_dim).apply { isSingleLine = true }
    private val city = label(14f, R.attr.c_text_faint).apply { isSingleLine = true }
    private val details = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sky)
        addView(range)
        addView(city)
    }
    override val view = LinearLayout(context).asCard(16).apply {
        setBackgroundResource(themeRes(context, R.attr.c_tile_bg))
        setPadding(dp(18), dp(14), dp(18), dp(14))
        gravity = Gravity.CENTER_VERTICAL
        addView(emoji)
        addView(temp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        setOnClickListener { if (!placed.config.has("lat")) showSettings() }
    }

    private var fetchedAt = 0L
    private var floating: android.animation.ValueAnimator? = null
    private var current: Weather.Forecast? = null

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
        emoji.textSize = if (small) 34f else 44f
        temp.textSize = if (small) 34f else 40f
        render()
    }

    override suspend fun load() {
        if (!placed.config.has("lat")) return render()
        if (current != null && SystemClock.elapsedRealtime() - fetchedAt < Weather.REFRESH_MS) return
        current = runCatching { Weather.forecast(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: current
        if (current != null) fetchedAt = SystemClock.elapsedRealtime()
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
            details.visibility = android.view.View.VISIBLE
            return
        }
        city.text = name
        if (f == null) {
            emoji.text = "…"
            temp.text = ""
            sky.setText(R.string.loading)
            range.text = ""
            return
        }
        val weather = Weather.describe(f.code)
        emoji.text = weather.emoji
        temp.text = context.getString(R.string.widget_weather_temp, f.temperature.roundToInt())
        sky.setText(weather.text)
        range.text = context.getString(R.string.widget_weather_range, f.min.roundToInt(), f.max.roundToInt())
    }

    override fun showSettings() = Weather.chooseCity(context, host.scope, placed.config) {
        current = null
        host.save()
        render()
        host.scope.launch { load() }
    }
}
