package app.mate.net

import org.json.JSONObject
import kotlin.math.roundToInt

/** Réglages continus d'un écran lus par DDC/CI ; `key` = nom envoyé à l'ordinateur (backend/src/screens.ts). */
enum class MonitorControl(val key: String) {
    BRIGHTNESS("brightness"),
    CONTRAST("contrast"),
    SHARPNESS("sharpness"),
    RED("red"),
    GREEN("green"),
    BLUE("blue"),
}

/** Valeur et maximum annoncés par l'écran (souvent 100, pas toujours). */
data class MonitorLevel(val value: Int, val max: Int) {
    val percent get() = if (max <= 0) 0 else (value * 100f / max).roundToInt()

    /** Pourcentage du curseur → valeur de l'écran. */
    fun toValue(percent: Int) = (percent.coerceIn(0, 100) * max / 100f).roundToInt()
}

data class MonitorMode(val width: Int, val height: Int, val hz: List<Int>)

/** Préréglage de couleur (code VCP 0x14) : la valeur actuelle et celles que l'écran annonce. */
data class MonitorPreset(val value: Int, val options: List<Int>)

data class Monitor(
    val id: String,
    val name: String,
    val primary: Boolean,
    val width: Int,
    val height: Int,
    val hz: Int,
    val modes: List<MonitorMode>,
    val ddc: Boolean,
    val levels: Map<MonitorControl, MonitorLevel>,
    val preset: MonitorPreset?,
) {
    /** Fréquences possibles à la résolution actuelle, la plus haute d'abord. */
    fun frequencies() = modes.firstOrNull { it.width == width && it.height == height }?.hz.orEmpty()
}

/** Widget Écrans (Windows) : écrans de l'ordinateur, luminosité, couleur et mode (backend/src/routes/monitors.ts). */
class MonitorsApi(private val http: Http) {

    // DDC/CI est lent : la première lecture lit la chaîne de capacités de chaque écran (une à deux secondes).
    suspend fun list(): List<Monitor> = http.io { parse(http.get("/monitors", timeoutMs = 15_000)) }

    suspend fun setLevel(id: String, control: MonitorControl, value: Int): List<Monitor> = send(JSONObject().put("id", id).put("control", control.key).put("value", value))

    suspend fun setPreset(id: String, value: Int): List<Monitor> = send(JSONObject().put("id", id).put("control", "preset").put("value", value))

    suspend fun setMode(id: String, width: Int, height: Int, hz: Int): List<Monitor> = send(JSONObject().put("id", id).put("width", width).put("height", height).put("hz", hz))

    private suspend fun send(body: JSONObject) = http.io { parse(http.postJson("/monitors", body, timeoutMs = 15_000)) }

    companion object {
        fun parse(json: String): List<Monitor> {
            val array = JSONObject(json).optJSONArray("monitors") ?: return emptyList()
            return (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                val modes = m.optJSONArray("modes")
                val preset = m.optJSONObject("preset")
                Monitor(
                    id = m.getString("id"),
                    name = m.optString("name"),
                    primary = m.optBoolean("primary"),
                    width = m.optInt("width"),
                    height = m.optInt("height"),
                    hz = m.optInt("hz"),
                    modes = (0 until (modes?.length() ?: 0)).map { j ->
                        val mode = modes!!.getJSONObject(j)
                        val hz = mode.optJSONArray("hz")
                        MonitorMode(mode.optInt("width"), mode.optInt("height"), (0 until (hz?.length() ?: 0)).map { hz!!.getInt(it) })
                    },
                    ddc = m.optBoolean("ddc"),
                    levels = MonitorControl.entries.mapNotNull { c -> m.optJSONObject(c.key)?.let { c to MonitorLevel(it.optInt("value"), it.optInt("max")) } }.toMap(),
                    preset = preset?.let { p ->
                        val options = p.optJSONArray("options")
                        MonitorPreset(p.optInt("value"), (0 until (options?.length() ?: 0)).map { options!!.getInt(it) })
                    },
                )
            }
        }
    }
}
