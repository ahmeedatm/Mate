package app.mate.net

import app.mate.R
import app.mate.UserFacingException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import kotlin.math.roundToInt

/** Domaines pilotés en direct : les mêmes que la liste blanche de l'ordinateur (backend/src/ha.ts, `HA_ENTITY_ID`). */
private val HA_ENTITY_ID = Regex("^(light|switch|media_player|scene)\\.[a-z0-9_]+$")

/** Modes où une lumière prend une couleur (« brightness », « color_temp », « white » n'en prennent pas). */
private val COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

/** Une télé est allumée dès qu'elle n'est ni éteinte ni en veille. */
private val MEDIA_OFF = setOf("off", "standby")

private const val HA_BYTE_MAX = 255

/** Réseaux privés IPv4 (RFC 1918) et liens locaux : là seulement le jeton peut passer en HTTP, case cochée. */
private val PRIVATE_IPV4 = Regex("^(10\\.\\d+|192\\.168|172\\.(1[6-9]|2\\d|3[01])|169\\.254)\\.\\d+\\.\\d+$")

/** URL en `http://` vers une adresse du réseau local (IP privée littérale ou nom en `.local`), sans résolution DNS. */
fun isPrivateHttpUrl(url: String): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    if (parsed.protocol != "http") return false
    val host = parsed.host.lowercase()
    return PRIVATE_IPV4.matches(host) || (host.endsWith(".local") && host.length > ".local".length)
}

/**
 * `GET /api/states` de Home Assistant → entités pilotables, dans l'ordre de Home Assistant. Mêmes règles que
 * l'ordinateur (backend/src/ha.ts, `haEntities`) : indisponible = grisée, télé allumée hors veille, scène = « run ».
 */
fun parseHaStates(json: String): List<HaEntity> {
    val list = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
        val s = list.optJSONObject(i) ?: return@mapNotNull null
        val id = s.optString("entity_id")
        if (!HA_ENTITY_ID.matches(id)) return@mapNotNull null
        val state = s.optString("state")
        val attributes = s.optJSONObject("attributes") ?: JSONObject()
        val domain = id.substringBefore('.')
        val available = state != "unavailable" && state != "unknown"
        val on = if (domain == "media_player") state !in MEDIA_OFF else state == "on"
        HaEntity(
            id = id,
            name = attributes.optString("friendly_name").ifEmpty { id },
            isScene = domain == "scene",
            on = available && on,
            available = available,
            light = if (available && domain == "light") lightOf(attributes) else null,
        )
    }
}

private fun lightOf(attributes: JSONObject): HaLight? {
    val modes = attributes.optJSONArray("supported_color_modes")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty()
    if (modes.none { it != "onoff" }) return null
    val bri = attributes.opt("brightness") as? Number
    val rgb = attributes.optJSONArray("rgb_color")?.takeIf { it.length() == 3 }
    return HaLight(
        color = modes.any { it in COLOR_MODES },
        brightness = bri?.let { (it.toDouble() / HA_BYTE_MAX * 100).roundToInt() },
        rgb = rgb?.let { (it.optInt(0) shl 16) or (it.optInt(1) shl 8) or it.optInt(2) },
    )
}

/** Service qui bascule l'entité (scène : la lance) et son corps. */
fun haDirectToggle(id: String): Pair<String, JSONObject> {
    val domain = id.substringBefore('.')
    val service = if (domain == "scene") "turn_on" else "toggle"
    return "/api/services/$domain/$service" to JSONObject().put("entity_id", id)
}

/** Corps de `light.turn_on` : luminosité en %, couleur en trois octets. */
fun haDirectLightBody(id: String, brightness: Int?, rgb: Int?): JSONObject = JSONObject().apply {
    put("entity_id", id)
    brightness?.let { put("brightness_pct", it) }
    rgb?.let { put("rgb_color", JSONArray().put(it shr 16 and 0xFF).put(it shr 8 and 0xFF).put(it and 0xFF)) }
}

/** Home Assistant en direct, sans ordinateur : le jeton est celui de [HaConfig], chiffré sur la tablette. */
class HaDirectApi(config: HaConfig) : LightSource {
    private val http = Http(config.url, config.token, CONNECT_TIMEOUT_MS, allowPrivateCleartext = config.allowHttp, followRedirects = false)

    override suspend fun entities(): List<HaEntity> = call { parseHaStates(http.get("/api/states", timeoutMs = READ_TIMEOUT_MS)) }

    override suspend fun toggle(id: String) = call {
        require(HA_ENTITY_ID.matches(id))
        val (path, body) = haDirectToggle(id)
        http.postJson(path, body, timeoutMs = READ_TIMEOUT_MS)
        Unit
    }

    override suspend fun setLight(id: String, brightness: Int?, rgb: Int?) = call {
        require(id.startsWith("light.") && HA_ENTITY_ID.matches(id))
        http.postJson("/api/services/light/turn_on", haDirectLightBody(id, brightness, rgb), timeoutMs = READ_TIMEOUT_MS)
        Unit
    }

    /** Jeton refusé : une phrase qui dit où le ressaisir, pas le « 401: Unauthorized » de Home Assistant. */
    private suspend fun <T> call(block: () -> T): T = http.io {
        try {
            block()
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) throw UserFacingException(R.string.ha_direct_refused)
            throw UserFacingException(R.string.ha_direct_failed)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

/** Adresse saisie → URL : sans protocole, `http://` (Home Assistant répond en HTTP sur le port 8123 par défaut). */
fun normalizeHaUrl(input: String): String {
    val url = input.trim().trimEnd('/')
    return if (url.contains("://")) url else "http://$url"
}

/** Ce qui empêche d'enregistrer l'adresse (texte de strings.xml), ou null si elle convient. */
fun haUrlProblem(url: String, allowHttp: Boolean): Int? {
    val parsed = runCatching { URL(url) }.getOrNull()
    if (parsed == null || parsed.host.isNullOrEmpty() || parsed.protocol !in setOf("http", "https")) return R.string.home_ha_bad_url
    if (parsed.protocol == "https") return null
    if (!isPrivateHttpUrl(url)) return R.string.home_ha_http_public
    return if (allowHttp) null else R.string.home_ha_need_http
}

/** Réglages copiés de l'ordinateur appairé (`GET /ha/settings`, backend/src/routes/ha-settings.ts). */
class HaSettings(val url: String, val token: String, val allowHttp: Boolean) {
    override fun toString() = "HaSettings(url=$url, allowHttp=$allowHttp)"
}

fun parseHaSettings(json: String): HaSettings = JSONObject(json).let {
    HaSettings(it.getString("url"), it.getString("token"), it.optBoolean("allowHttp"))
}
