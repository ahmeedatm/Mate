package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Entité Home Assistant de la liste blanche de l'ordinateur. `kind` « run » : scène ou script, qui se lance sans état ;
 * `available` faux : Home Assistant ne la joint pas.
 */
data class HaEntity(
    val id: String,
    val name: String,
    val isScene: Boolean,
    val on: Boolean,
    val available: Boolean,
    val light: HaLight? = null,
)

/**
 * Ce qu'une lumière sait régler (absent : elle ne fait qu'allumer et éteindre). `color` faux : luminosité seule ;
 * `brightness` en %, null éteinte ; `rgb` en 0xRRGGBB, null sans couleur lue.
 */
data class HaLight(val color: Boolean, val brightness: Int?, val rgb: Int?)

fun parseHaEntities(json: String): List<HaEntity> {
    val list = JSONObject(json).optJSONArray("entities") ?: return emptyList()
    return (0 until list.length()).map {
        val e = list.getJSONObject(it)
        val id = e.getString("id")
        HaEntity(id, e.optString("name", id), e.optString("kind") == "run", e.optBoolean("on"), e.optBoolean("available", true), e.optJSONObject("light")?.let(::parseHaLight))
    }
}

private fun parseHaLight(o: JSONObject): HaLight {
    val rgb = o.optJSONArray("rgb")?.takeIf { it.length() == 3 }
    return HaLight(
        color = o.optBoolean("color"),
        brightness = if (o.isNull("brightness")) null else o.optInt("brightness"),
        rgb = rgb?.let { (it.optInt(0) shl 16) or (it.optInt(1) shl 8) or it.optInt(2) },
    )
}

/** Corps du réglage : luminosité en %, couleur 0xRRGGBB envoyée en trois octets. */
fun haLightBody(brightness: Int?, rgb: Int?): JSONObject = JSONObject().apply {
    brightness?.let { put("brightness", it) }
    rgb?.let { put("rgb", JSONArray().put(it shr 16 and 0xFF).put(it shr 8 and 0xFF).put(it and 0xFF)) }
}

/** Home Assistant de la maison, par l'ordinateur (backend/src/ha.ts) : le jeton n'est jamais sur la tablette. */
class HaApi(private val http: Http) : LightSource {

    override suspend fun entities(): List<HaEntity> = http.io { parseHaEntities(http.get("/ha/entities", timeoutMs = 15_000)) }

    override suspend fun toggle(id: String) = http.io {
        http.postJson("/ha/entities/${http.encode(id)}/toggle", JSONObject())
        Unit
    }

    override suspend fun setLight(id: String, brightness: Int?, rgb: Int?) = http.io {
        http.postJson("/ha/entities/${http.encode(id)}/light", haLightBody(brightness, rgb))
        Unit
    }

    /** Adresse et jeton de Home Assistant de l'ordinateur, copiés une fois pour Maison (HTTPS ou USB seulement). */
    suspend fun settings(): HaSettings = http.io { parseHaSettings(http.get("/ha/settings", timeoutMs = 10_000)) }
}
