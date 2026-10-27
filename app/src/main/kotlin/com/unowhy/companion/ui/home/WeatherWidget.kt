package com.unowhy.companion.ui.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.showForTyping
import com.unowhy.companion.ui.themeRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    private var current: Forecast? = null

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
        if (current != null && SystemClock.elapsedRealtime() - fetchedAt < REFRESH_MS) return
        current = runCatching { forecast(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: current
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
        val (icon, text) = describe(f.code)
        emoji.text = icon
        temp.text = context.getString(R.string.widget_weather_temp, f.temperature.roundToInt())
        sky.setText(text)
        range.text = context.getString(R.string.widget_weather_range, f.min.roundToInt(), f.max.roundToInt())
    }

    /** Ville cherchée par son nom (géocodage Open-Meteo), choisie dans la liste. */
    override fun showSettings() {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.setHint(R.string.widget_weather_city_hint)
        input.setSingleLine()
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(results)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.widget_weather)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.widget_weather_search, null)
            .create()
        fun search() {
            val query = input.text.toString().trim()
            if (query.length < 2) return
            results.removeAllViews()
            results.addView(label(15f, R.attr.c_text_dim).apply { setText(R.string.loading); setPadding(0, dp(12), 0, 0) })
            host.scope.launch {
                val places = runCatching { geocode(query) }.getOrNull()
                // Clavier fermé : il cacherait la liste des villes.
                hideKeyboard(input)
                results.removeAllViews()
                if (places.isNullOrEmpty()) {
                    results.addView(label(15f, R.attr.c_text_dim).apply {
                        setText(if (places == null) R.string.widget_weather_offline else R.string.widget_weather_none)
                        setPadding(0, dp(12), 0, 0)
                    })
                    return@launch
                }
                for (place in places) results.addView(TextView(context).apply {
                    text = place.label
                    textSize = 17f
                    setTextColor(color(R.attr.c_text))
                    setBackgroundResource(R.drawable.row_bg)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener {
                        hideKeyboard(input)
                        placed.config.put("city", place.name).put("lat", place.lat).put("lon", place.lon)
                        current = null
                        host.save()
                        dialog.dismiss()
                        render()
                        host.scope.launch { load() }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
        input.setOnEditorActionListener { _, _, _ -> search(); true }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { search() } }
        dialog.showForTyping(input)
    }

    private fun hideKeyboard(input: EditText) {
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private class Forecast(val temperature: Double, val code: Int, val min: Double, val max: Double)
    private class Place(val name: String, val label: String, val lat: Double, val lon: Double)

    private suspend fun forecast(lat: Double, lon: Double): Forecast {
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
        )
        val now = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        return Forecast(
            now.getDouble("temperature_2m"), now.getInt("weather_code"),
            daily.getJSONArray("temperature_2m_min").getDouble(0), daily.getJSONArray("temperature_2m_max").getDouble(0),
        )
    }

    private suspend fun geocode(query: String): List<Place> {
        val json = get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=fr&format=json&name=" + URLEncoder.encode(query, "UTF-8"))
        val list = json.optJSONArray("results") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val name = p.getString("name")
            val where = listOf(p.optString("admin1"), p.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            Place(name, if (where.isBlank()) name else "$name · $where", p.getDouble("latitude"), p.getDouble("longitude"))
        }
    }

    /** Limite globale : sans Internet, la résolution du nom peut traîner bien au-delà des délais de connexion. */
    private suspend fun get(url: String): JSONObject = withTimeout(12_000) { fetch(url) }

    private suspend fun fetch(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Codes météo de l'OMM (WMO) utilisés par Open-Meteo. */
    private fun describe(code: Int): Pair<String, Int> = when (code) {
        0 -> "☀️" to R.string.sky_clear
        1, 2 -> "⛅" to R.string.sky_partly
        3 -> "☁️" to R.string.sky_cloudy
        45, 48 -> "🌫️" to R.string.sky_fog
        in 51..57 -> "🌦️" to R.string.sky_drizzle
        in 61..67 -> "🌧️" to R.string.sky_rain
        in 71..77 -> "❄️" to R.string.sky_snow
        in 80..82 -> "🌦️" to R.string.sky_showers
        85, 86 -> "🌨️" to R.string.sky_snow
        in 95..99 -> "⛈️" to R.string.sky_storm
        else -> "🌡️" to R.string.sky_unknown
    }

    private companion object {
        const val REFRESH_MS = 30 * 60_000L
    }
}
