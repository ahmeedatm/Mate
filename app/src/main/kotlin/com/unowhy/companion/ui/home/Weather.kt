package com.unowhy.companion.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.showForTyping
import com.unowhy.companion.ui.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Météo lue par la tablette chez Open-Meteo (gratuit, sans clé ni compte) : prévision d'une ville, recherche de ville.
 * Partagé par le widget Météo et le héros de l'Accueil, qui gardent chacun leur ville dans leur `placed.config`.
 */
object Weather {
    const val REFRESH_MS = 30 * 60_000L

    class Forecast(val temperature: Double, val code: Int, val min: Double, val max: Double)
    class Place(val name: String, val label: String, val lat: Double, val lon: Double)

    /** Le temps qu'il fait : emoji (widget Météo), icône monochrome (héros Cadran) et libellé. */
    class Sky(val emoji: String, @DrawableRes val icon: Int, @StringRes val text: Int)

    suspend fun forecast(lat: Double, lon: Double): Forecast {
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

    suspend fun geocode(query: String): List<Place> {
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
    fun describe(code: Int): Sky = when (code) {
        0 -> Sky("☀️", R.drawable.ic_wx_clear, R.string.sky_clear)
        1, 2 -> Sky("⛅", R.drawable.ic_wx_partly, R.string.sky_partly)
        3 -> Sky("☁️", R.drawable.ic_wx_cloudy, R.string.sky_cloudy)
        45, 48 -> Sky("🌫️", R.drawable.ic_wx_fog, R.string.sky_fog)
        in 51..57 -> Sky("🌦️", R.drawable.ic_wx_rain, R.string.sky_drizzle)
        in 61..67 -> Sky("🌧️", R.drawable.ic_wx_rain, R.string.sky_rain)
        in 71..77 -> Sky("❄️", R.drawable.ic_wx_snow, R.string.sky_snow)
        in 80..82 -> Sky("🌦️", R.drawable.ic_wx_rain, R.string.sky_showers)
        85, 86 -> Sky("🌨️", R.drawable.ic_wx_snow, R.string.sky_snow)
        in 95..99 -> Sky("⛈️", R.drawable.ic_wx_storm, R.string.sky_storm)
        else -> Sky("🌡️", R.drawable.ic_wx_partly, R.string.sky_unknown)
    }

    /** Ville cherchée par son nom (géocodage Open-Meteo), choisie dans la liste : `city`, `lat` et `lon` de `config`. */
    fun chooseCity(context: Context, scope: CoroutineScope, config: JSONObject, onChosen: () -> Unit) {
        fun text(size: Float, colorAttr: Int) = TextView(context).apply {
            textSize = size
            setTextColor(themeColor(context, colorAttr))
        }
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.setHint(R.string.widget_weather_city_hint)
        input.setSingleLine()
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
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
            results.addView(text(15f, R.attr.c_text_dim).apply { setText(R.string.loading); setPadding(0, context.dp(12), 0, 0) })
            scope.launch {
                val places = runCatching { geocode(query) }.getOrNull()
                // Clavier fermé : il cacherait la liste des villes.
                hideKeyboard(context, input)
                results.removeAllViews()
                if (places.isNullOrEmpty()) {
                    results.addView(text(15f, R.attr.c_text_dim).apply {
                        setText(if (places == null) R.string.widget_weather_offline else R.string.widget_weather_none)
                        setPadding(0, context.dp(12), 0, 0)
                    })
                    return@launch
                }
                for (place in places) results.addView(text(17f, R.attr.c_text).apply {
                    this.text = place.label
                    setBackgroundResource(R.drawable.row_bg)
                    setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                    setOnClickListener {
                        hideKeyboard(context, input)
                        config.put("city", place.name).put("lat", place.lat).put("lon", place.lon)
                        dialog.dismiss()
                        onChosen()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
            }
        }
        input.setOnEditorActionListener { _, _, _ -> search(); true }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { search() } }
        dialog.showForTyping(input)
    }

    private fun hideKeyboard(context: Context, input: EditText) {
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}
