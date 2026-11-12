package app.mate.net

import app.mate.R
import app.mate.UserFacingException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Luminosité maximale de WLED (`bri`, 0 à 255). */
private const val WLED_BRI_MAX = 255

/**
 * Une WLED lue en direct (`GET /json/state`), au format d'une entité Home Assistant : le widget Maison ne voit pas la
 * différence. Luminosité en %, couleur = première couleur du premier segment.
 */
fun parseWledState(id: String, name: String, json: String): HaEntity {
    val o = JSONObject(json)
    val on = o.optBoolean("on")
    val col = o.optJSONArray("seg")?.optJSONObject(0)?.optJSONArray("col")?.optJSONArray(0)?.takeIf { it.length() >= 3 }
    val light = HaLight(
        color = true,
        brightness = if (on) (o.optInt("bri") * 100.0 / WLED_BRI_MAX).roundToInt() else null,
        rgb = col?.let { (it.optInt(0) shl 16) or (it.optInt(1) shl 8) or it.optInt(2) },
    )
    return HaEntity(id, name, isScene = false, on = on, available = true, light = light)
}

/** Corps de `POST /json/state` : allume, luminosité % en `bri` sur 255, couleur dans le premier segment. */
fun wledStateBody(brightness: Int?, rgb: Int?): JSONObject = JSONObject().apply {
    put("on", true)
    brightness?.let { put("bri", (it.coerceIn(0, 100) * WLED_BRI_MAX / 100.0).roundToInt()) }
    rgb?.let {
        val color = JSONArray().put(it shr 16 and 0xFF).put(it shr 8 and 0xFF).put(it and 0xFF)
        put("seg", JSONArray().put(JSONObject().put("col", JSONArray().put(color))))
    }
}

/** Une WLED connue de la tablette : trouvée sur le WiFi (mDNS) ou saisie. `id` : « wled:<adresse MAC> ». */
data class WledDevice(val id: String, val host: String, val name: String)

/** `GET /json/info` → la WLED telle que la tablette la retient (null : ce n'est pas une WLED). */
fun parseWledInfo(host: String, json: String): WledDevice? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val mac = o.optString("mac").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { return null }
    return WledDevice("wled:$mac", host, o.optString("name").ifEmpty { host })
}

/** Une WLED en direct, sans Home Assistant ni ordinateur : son API JSON en HTTP sur le WiFi, sans jeton. */
class WledApi(private val device: WledDevice) : LightSource {
    private val http = Http("http://${device.host}", "", CONNECT_TIMEOUT_MS, followRedirects = false)

    /** Éteinte au mur ou hors du WiFi : la lumière reste listée, grisée. */
    override suspend fun entities(): List<HaEntity> = http.io {
        val entity = try {
            parseWledState(device.id, device.name, http.get("/json/state", timeoutMs = READ_TIMEOUT_MS))
        } catch (e: java.io.IOException) {
            HaEntity(device.id, device.name, isScene = false, on = false, available = false)
        }
        listOf(entity)
    }

    override suspend fun toggle(id: String) = send(JSONObject().put("on", "t"))

    override suspend fun setLight(id: String, brightness: Int?, rgb: Int?) = send(wledStateBody(brightness, rgb))

    /** Une erreur HTTP de la WLED dit son nom, pas « Reconnecte-toi à l'ordinateur ». */
    private suspend fun send(body: JSONObject) = http.io {
        try {
            http.postJson("/json/state", body, timeoutMs = READ_TIMEOUT_MS)
        } catch (e: HttpException) {
            throw UserFacingException(R.string.home_wled_failed, device.name)
        }
        Unit
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 4_000

        /** Lit la WLED à cette adresse (trouvée ou saisie) ; null si ce n'en est pas une. */
        fun info(host: String): WledDevice? {
            val http = Http("http://$host", "", CONNECT_TIMEOUT_MS, followRedirects = false)
            return parseWledInfo(host, http.get("/json/info", timeoutMs = READ_TIMEOUT_MS))
        }
    }
}
