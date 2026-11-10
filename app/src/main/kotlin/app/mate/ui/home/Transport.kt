package app.mate.ui.home

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.BuildConfig
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.showForTyping
import app.mate.ui.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Prochains passages à un arrêt d'Île-de-France, lus par la tablette chez IDFM (Île-de-France Mobilités, API temps réel
 * « stop-monitoring » au format SIRI). La clé entre au build ([BuildConfig.IDFM_TOKEN], secret Actions `IDFM_TOKEN` en
 * release, vide en debug : aucun passage lu), jamais dans le dépôt ni dans un échange en clair hors de la tablette.
 * L'arrêt se cherche par son nom chez IDFM (places navitia) ; une direction = une destination observée à cet arrêt.
 * Rien de lourd : un appel toutes les [REFRESH_MS], au plus [MAX] passages gardés ([tablette-4.2], 128 Mo de tas).
 */
object Transport {
    const val REFRESH_MS = 60_000L
    private const val MAX = 12
    private const val BASE = "https://prim.iledefrance-mobilites.fr/marketplace"

    /** Un passage : la ligne (référence IDFM), sa destination affichée et l'attente en millisecondes. */
    class Departure(val lineRef: String, val destination: String, val waitMs: Long)

    /** Un arrêt trouvé : son nom et son identifiant IDFM (le numéro de `stop_area:IDFM:<id>`). */
    class Stop(val name: String, val id: String)

    /**
     * Réponse SIRI d'IDFM → passages triés par attente croissante, ceux partis depuis plus d'une minute retirés
     * (un passage gardé faute de réseau ne doit pas afficher le passé). [now] : horloge de la tablette en ms.
     */
    fun parse(root: JSONObject, now: Long): List<Departure> {
        val visits = root.optJSONObject("Siri")
            ?.optJSONObject("ServiceDelivery")
            ?.optJSONArray("StopMonitoringDelivery")
            ?.optJSONObject(0)
            ?.optJSONArray("MonitoredStopVisit")
            ?: return emptyList()
        val out = ArrayList<Departure>(visits.length())
        for (i in 0 until visits.length()) {
            val mvj = visits.optJSONObject(i)?.optJSONObject("MonitoredVehicleJourney") ?: continue
            val call = mvj.optJSONObject("MonitoredCall") ?: continue
            val time = call.optString("ExpectedDepartureTime").ifBlank { call.optString("AimedDepartureTime") }
            val at = epoch(time) ?: continue
            val waitMs = at - now
            if (waitMs < -60_000L) continue
            out += Departure(
                lineRef = mvj.optJSONObject("LineRef")?.optString("value").orEmpty(),
                destination = firstValue(mvj, "DestinationName").ifBlank { firstValue(call, "DestinationDisplay") },
                waitMs = waitMs,
            )
        }
        return out.sortedBy { it.waitMs }
    }

    /** Garde les passages de la ligne [lineRef] (si donnée) et de la direction [direction] (destination, si donnée). */
    fun pick(all: List<Departure>, lineRef: String?, direction: String?) = all.filter {
        (lineRef.isNullOrBlank() || it.lineRef == lineRef) &&
            (direction.isNullOrBlank() || it.destination.contains(direction, ignoreCase = true))
    }

    /** Attente en minutes, arrondie vers le bas (« dans 3 min » = le passage arrive dans 3 à 4 min), jamais négative. */
    fun waitMinutes(ms: Long): Int = (ms.coerceAtLeast(0) / 60_000L).toInt()

    /** Premier `[{ "value": … }]` d'un champ SIRI (DestinationName, DestinationDisplay) ; vide s'il manque. */
    private fun firstValue(obj: JSONObject, field: String) =
        obj.optJSONArray(field)?.optJSONObject(0)?.optString("value").orEmpty()

    /** Horodatage ISO 8601 d'IDFM (« 2026-11-10T14:03:00.000Z » ou avec décalage) → ms ; null si illisible. */
    private fun epoch(iso: String): Long? {
        if (iso.isBlank()) return null
        val cleaned = iso.replace(Regex("\\.\\d+"), "")
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(cleaned)!!.time }.getOrNull()
    }

    /** Prochains passages de l'arrêt [stopId], lus chez IDFM. */
    suspend fun departures(stopId: String, now: Long): List<Departure> =
        parse(get("$BASE/stop-monitoring?MonitoringRef=STIF:StopArea:SP:$stopId:"), now).take(MAX)

    /** Arrêts dont le nom ressemble à [query] (places navitia d'IDFM). */
    suspend fun searchStops(query: String): List<Stop> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val places = get("$BASE/v2/navitia/places?q=$enc&type%5B%5D=stop_area&count=12").optJSONArray("places")
            ?: return emptyList()
        val out = ArrayList<Stop>(places.length())
        for (i in 0 until places.length()) {
            val area = places.optJSONObject(i)?.optJSONObject("stop_area") ?: continue
            val id = area.optString("id").substringAfterLast(':')
            if (id.isNotBlank()) out += Stop(area.optString("name"), id)
        }
        return out
    }

    /** Clé absente (debug ou release sans secret) : rien à lire chez IDFM. */
    val keyless get() = BuildConfig.IDFM_TOKEN.isBlank()

    private suspend fun get(url: String): JSONObject = withTimeout(12_000) {
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("apikey", BuildConfig.IDFM_TOKEN)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            try {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Choisir l'arrêt puis la direction : on tape le nom de l'arrêt, IDFM propose des arrêts ; en choisir un lit ses
     * prochains passages et montre « Toutes les directions » puis une ligne par destination observée. Enregistre
     * `stopId`, `stop`, et si une direction précise est prise, `line` et `direction` dans [config].
     */
    fun choose(context: Context, scope: CoroutineScope, config: JSONObject, onChosen: () -> Unit) {
        fun text(size: Float, colorAttr: Int) = TextView(context).apply {
            textSize = size
            setTextColor(themeColor(context, colorAttr))
        }
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.setHint(R.string.widget_transport_hint)
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
            .setTitle(R.string.widget_transport)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.widget_transport_search, null)
            .create()

        fun row(label: String, onClick: () -> Unit) = text(17f, R.attr.c_text).apply {
            this.text = label
            setBackgroundResource(R.drawable.row_bg)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            setOnClickListener { onClick() }
        }
        fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.dp(8) }
        fun note(res: Int) {
            results.removeAllViews()
            results.addView(text(15f, R.attr.c_text_dim).apply { setText(res); setPadding(0, context.dp(12), 0, 0) })
        }

        fun save(stop: Stop, lineRef: String?, direction: String?) {
            hideKeyboard(context, input)
            config.put("stopId", stop.id).put("stop", stop.name)
            if (direction.isNullOrBlank()) {
                config.remove("line"); config.remove("direction")
            } else {
                config.put("line", lineRef).put("direction", direction)
            }
            dialog.dismiss()
            onChosen()
        }

        fun chooseDirection(stop: Stop) {
            note(R.string.loading)
            scope.launch {
                val deps = runCatching { departures(stop.id, System.currentTimeMillis()) }.getOrNull()
                if (!dialog.isShowing) return@launch
                results.removeAllViews()
                results.addView(row(context.getString(R.string.widget_transport_all)) { save(stop, null, null) }, rowParams())
                // Une direction = une destination distincte observée à l'arrêt ; sa ligne sert au filtre précis.
                deps.orEmpty().distinctBy { it.destination }.filter { it.destination.isNotBlank() }
                    .forEach { d -> results.addView(row("→ ${d.destination}") { save(stop, d.lineRef, d.destination) }, rowParams()) }
            }
        }

        fun search() {
            val query = input.text.toString().trim()
            if (query.length < 2) return
            note(R.string.loading)
            scope.launch {
                val stops = runCatching { searchStops(query) }.getOrNull()
                hideKeyboard(context, input)
                results.removeAllViews()
                if (stops.isNullOrEmpty()) return@launch note(R.string.widget_transport_none)
                stops.forEach { stop -> results.addView(row(stop.name) { chooseDirection(stop) }, rowParams()) }
            }
        }

        input.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) search()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { search() }
            if (keyless) note(R.string.widget_transport_no_key)
        }
        dialog.showForTyping(input)
    }

    private fun hideKeyboard(context: Context, input: EditText) {
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}
