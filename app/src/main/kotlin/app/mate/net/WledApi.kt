package app.mate.net

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
