package com.unowhy.companion.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
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
import com.unowhy.companion.BuildConfig
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.device.Position
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.showForTyping
import com.unowhy.companion.ui.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Météo lue par la tablette chez MET Norway (institut météorologique norvégien : gratuit, sans clé ni compte, usage
 * commercial permis, licence CC BY 4.0 citée dans Réglages > À propos) ; ville cherchée dans la liste embarquée
 * ([Cities]). Partagé par le widget Météo et le héros de l'Accueil, qui gardent chacun leur ville dans leur `placed.config`.
 * Conditions de MET Norway : un User-Agent qui identifie l'appli et un contact, pas plus d'une lecture par demi-heure
 * ([REFRESH_MS], leurs prévisions changent à ce rythme).
 */
object Weather {
    const val REFRESH_MS = 30 * 60_000L

    /** Aujourd'hui et les 4 jours suivants. */
    private const val DAYS = 5

    /** Temps actuel et min / max du jour ; [days] : les jours suivants (grand widget). */
    class Forecast(val temperature: Double, val code: Int, val min: Double, val max: Double, val days: List<Day> = emptyList())

    /** Un jour suivant : date (« 2026-10-29 », jour de la tablette), temps, min et max. */
    class Day(val date: String, val code: Int, val min: Double, val max: Double)
    class Place(val name: String, val label: String, val lat: Double, val lon: Double)

    /** Le temps qu'il fait : emoji (widget Météo), icône monochrome (héros Cadran) et libellé. */
    class Sky(val emoji: String, @DrawableRes val icon: Int, @StringRes val text: Int)

    /** MET Norway refuse plus de 4 décimales (et elles suffisent : 11 m). */
    suspend fun forecast(lat: Double, lon: Double): Forecast = parse(
        get(String.format(Locale.ROOT, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon)),
    )

    /**
     * Réponse de MET Norway : une suite d'échéances en UTC (heure par heure, puis toutes les 6 h), chacune avec la
     * température et le temps de l'heure ou des 6 h suivantes. Regroupées par jour de la tablette : min / max du jour,
     * temps de la mi-journée ; la première échéance donne le temps actuel.
     */
    fun parse(json: JSONObject, zone: TimeZone = TimeZone.getDefault()): Forecast {
        val series = json.getJSONObject("properties").getJSONArray("timeseries")
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
        val hour = SimpleDateFormat("H", Locale.ROOT).apply { timeZone = zone }
        class Step(val date: String, val hour: Int, val temperature: Double, val symbol: String?)
        val steps = (0 until series.length()).map { i ->
            val t = series.getJSONObject(i)
            val time = utc.parse(t.getString("time"))!!
            val data = t.getJSONObject("data")
            val symbol = listOf("next_1_hours", "next_6_hours", "next_12_hours")
                .firstNotNullOfOrNull { data.optJSONObject(it)?.optJSONObject("summary")?.optString("symbol_code")?.ifEmpty { null } }
            Step(day.format(time), hour.format(time).toInt(), data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"), symbol)
        }
        val byDay = steps.groupBy { it.date }.entries.take(DAYS)
        fun dayOf(date: String, list: List<Step>) = Day(
            date,
            codeOf(list.filter { it.symbol != null }.minByOrNull { kotlin.math.abs(it.hour - 12) }?.symbol),
            list.minOf { it.temperature },
            list.maxOf { it.temperature },
        )
        val today = dayOf(byDay[0].key, byDay[0].value)
        return Forecast(steps[0].temperature, codeOf(steps[0].symbol), today.min, today.max, byDay.drop(1).map { dayOf(it.key, it.value) })
    }

    /**
     * Temps de MET Norway (« lightrainshowers_day ») → code de l'OMM (WMO) des icônes et libellés de [describe].
     * L'orage d'abord : « heavyrainshowersandthunder » est un orage, pas une averse.
     */
    fun codeOf(symbol: String?): Int {
        val s = symbol?.substringBefore('_') ?: return -1
        return when {
            "thunder" in s -> 95
            "snowshowers" in s || "sleetshowers" in s -> 85
            "rainshowers" in s -> if (s.startsWith("heavy")) 82 else 80
            "snow" in s -> if (s.startsWith("heavy")) 75 else if (s.startsWith("light")) 71 else 73
            "sleet" in s -> 67
            "rain" in s -> if (s.startsWith("heavy")) 65 else if (s.startsWith("light")) 61 else 63
            s == "fog" -> 45
            s == "cloudy" -> 3
            s == "partlycloudy" -> 2
            s == "fair" -> 1
            s == "clearsky" -> 0
            else -> -1
        }
    }

    /** Limite globale : sans Internet, la résolution du nom peut traîner bien au-delà des délais de connexion. */
    private suspend fun get(url: String): JSONObject = withTimeout(12_000) { fetch(url) }

    private suspend fun fetch(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Ville de la position de la tablette ([Position] puis [Cities.nearest]) ; null si elle ne se trouve pas. */
    suspend fun locate(context: Context): Place? {
        val here = Position.find(context) ?: return null
        return Cities.nearest(context, here.latitude, here.longitude)
    }

    /** Une seule détection à la fois pour le héros et le widget Météo, et au plus une par demi-heure si elle échoue. */
    private val autoLock = Mutex()
    private var autoFound: Place? = null
    private var autoTriedAt = 0L

    /**
     * Météo sans ville choisie : la ville de la position, mise dans `config` (`auto` : trouvée seule, à remplacer par un
     * choix). true si une ville a été trouvée (l'appelant enregistre la disposition).
     */
    suspend fun autoCity(context: Context, config: JSONObject): Boolean {
        if (config.has("lat") || !Prefs.weatherLocation(context)) return false
        val place = autoLock.withLock {
            autoFound ?: if (SystemClock.elapsedRealtime() - autoTriedAt < REFRESH_MS && autoTriedAt > 0) {
                null
            } else {
                autoTriedAt = SystemClock.elapsedRealtime()
                runCatching { locate(context) }.getOrNull().also { autoFound = it }
            }
        } ?: return false
        config.put("city", place.name).put("lat", place.lat).put("lon", place.lon).put("auto", true)
        return true
    }

    /** Identifiant demandé par MET Norway (sans lui, requêtes bloquées) : appli, version, site d'Unowhy pour le contact. */
    private val USER_AGENT = "Companion-Unowhy/${BuildConfig.VERSION_NAME} (+https://www.unowhy.com)"

    /** Codes météo de l'OMM (WMO), obtenus par [codeOf]. */
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

    /**
     * Ville cherchée par son nom (liste embarquée, [Cities]) ou trouvée par la position (« Ma position »), choisie dans la
     * liste : `city`, `lat` et `lon` de `config`.
     */
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
            .setNeutralButton(R.string.widget_weather_locate, null)
            .create()
        fun choose(place: Place, auto: Boolean = false) {
            hideKeyboard(context, input)
            config.put("city", place.name).put("lat", place.lat).put("lon", place.lon)
            if (auto) config.put("auto", true) else config.remove("auto")
            dialog.dismiss()
            onChosen()
        }
        fun useMyPosition() {
            results.removeAllViews()
            results.addView(text(15f, R.attr.c_text_dim).apply { setText(R.string.widget_weather_locating); setPadding(0, context.dp(12), 0, 0) })
            hideKeyboard(context, input)
            scope.launch {
                // Hors kiosque, la localisation n'est pas accordée d'office : demandée ici, jamais au démarrage.
                val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    (context as? MainActivity)?.requestLocationPermission() == true
                if (!allowed) {
                    results.removeAllViews()
                    results.addView(text(15f, R.attr.c_text_dim).apply { setText(R.string.widget_weather_no_permission); setPadding(0, context.dp(12), 0, 0) })
                    return@launch
                }
                val place = runCatching { locate(context) }.getOrNull()
                if (!dialog.isShowing) return@launch
                if (place != null) return@launch choose(place, auto = true)
                results.removeAllViews()
                results.addView(text(15f, R.attr.c_text_dim).apply { setText(R.string.widget_weather_no_position); setPadding(0, context.dp(12), 0, 0) })
            }
        }
        fun search() {
            val query = input.text.toString().trim()
            if (query.length < 2) return
            results.removeAllViews()
            results.addView(text(15f, R.attr.c_text_dim).apply { setText(R.string.loading); setPadding(0, context.dp(12), 0, 0) })
            scope.launch {
                val places = runCatching { Cities.search(context, query) }.getOrNull()
                // Clavier fermé : il cacherait la liste des villes.
                hideKeyboard(context, input)
                results.removeAllViews()
                if (places.isNullOrEmpty()) {
                    results.addView(text(15f, R.attr.c_text_dim).apply {
                        setText(R.string.widget_weather_none)
                        setPadding(0, context.dp(12), 0, 0)
                    })
                    return@launch
                }
                for (place in places) results.addView(text(17f, R.attr.c_text).apply {
                    this.text = place.label
                    setBackgroundResource(R.drawable.row_bg)
                    setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                    setOnClickListener { choose(place) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
            }
        }
        scope.launch { runCatching { Cities.preload(context) } }
        // Touche Entrée : une seule recherche (le clavier envoie l'appui puis le relâchement).
        input.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) search()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { search() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { useMyPosition() }
        }
        dialog.showForTyping(input)
    }

    private fun hideKeyboard(context: Context, input: EditText) {
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}
