package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkPoi
import app.mate.net.CyberpunkServerPin
import app.mate.net.PoiCategory
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Ce que Mate dit à la carte 3D embarquée (`assets/carte3d/`, moteur op77_map3d d'Open2077) et ce qu'il comprend de ses
 * événements. Contrat des événements `map3d:*` : docs/specs/2026-11-15-cyberpunk-carte-3d-design.md. Pur : testé en JVM.
 */
object Map3dBridge {
    /** Catégorie de la légende des repères posés depuis la tablette ; null dans les tables de couleurs et de libellés. */
    private const val PIN = "pin"
    private const val QUEST = "quest"

    /** Un repère existant à moins de 3 m de la destination posée sur la carte est celui qu'on suit. */
    private const val SAME_PIN_M = 3.0

    /** Icônes intégrées au moteur, par catégorie de point d'intérêt. */
    private val ICONS = mapOf(
        PoiCategory.QUEST to "mission",
        PoiCategory.GIG to "briefcase",
        PoiCategory.NCPD to "police",
        PoiCategory.TRAVEL to "bus",
        PoiCategory.SHOP to "store",
        PoiCategory.RIPPERDOC to "hospital",
        PoiCategory.OTHER to "pin",
    )
    private const val PIN_ICON = "flag"
    private const val OBJECTIVE_ICON = "target"

    sealed interface Action {
        /** Destination posée sur un endroit vide : nouveau repère, suivi aussitôt. */
        data class NewPin(val x: Double, val y: Double, val z: Double) : Action

        /** Destination posée sur un repère existant : on le suit. */
        data class Track(val pinId: String) : Action

        /** Itinéraire effacé (ou arrivé) : retour à la quête ; le repère suivi, s'il y en a un, est retiré. */
        data class Clear(val pinId: String?) : Action

        data object None : Action
    }

    /** Yaw du jeu (0 = nord, positif vers l'ouest) : même convention que la page. */
    fun player(x: Double, y: Double, z: Double?, yaw: Double, vehicle: Boolean, speed: Double?): JSONObject = JSONObject()
        .put("x", x).put("y", y).put("z", z ?: 0.0)
        .put("yaw", yaw).put("camYaw", yaw)
        .put("vehicle", vehicle).put("speed", speed ?: 0.0)

    /** Repères (`pin:<id>`), points d'intérêt (`poi:<id>`) et objectif de la quête suivie (`quest`, qui pulse). */
    fun blips(
        pins: List<CyberpunkServerPin>,
        pois: List<CyberpunkPoi>,
        objective: Pair<Double, Double>?,
        objectiveLabel: String?,
        colors: Map<PoiCategory?, String>,
        pinLabel: String,
    ): JSONArray {
        val out = JSONArray()
        for (p in pins) {
            out.put(blip("$PIN:${p.id}", p.x, p.y, PIN_ICON, PIN, colors[null]).put("label", p.name ?: pinLabel))
        }
        for (p in pois) {
            out.put(blip("poi:${p.id}", p.x, p.y, ICONS.getValue(p.cat), p.cat.key, colors[p.cat]).apply { p.name?.let { put("label", it) } })
        }
        objective?.let { (x, y) ->
            out.put(blip(QUEST, x, y, OBJECTIVE_ICON, PoiCategory.QUEST.key, colors[PoiCategory.QUEST]).put("pulse", true).put("edge", true).apply { objectiveLabel?.let { put("label", it) } })
        }
        return out
    }

    private fun blip(id: String, x: Double, y: Double, icon: String, category: String, color: String?) = JSONObject()
        .put("id", id).put("x", x).put("y", y).put("icon", icon).put("category", category).apply { color?.let { put("color", it) } }

    /** Destination montrée par la carte : le repère suivi, sinon l'objectif de la quête suivie. */
    fun waypoint(pins: List<CyberpunkServerPin>, objective: Pair<Double, Double>?): JSONObject? {
        pins.firstOrNull { it.tracked }?.let { return JSONObject().put("x", it.x).put("y", it.y).put("z", it.z) }
        return objective?.let { (x, y) -> JSONObject().put("x", x).put("y", y).put("z", 0.0) }
    }

    /** Événement `map3d:waypoint` de la page (`{x, y, z}` ou `{clear: true}`), posé par la personne sur la tablette. */
    fun action(event: JSONObject, pins: List<CyberpunkServerPin>): Action {
        if (event.optBoolean("clear")) return Action.Clear(pins.firstOrNull { it.tracked }?.id)
        val x = (event.opt("x") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return Action.None
        val y = (event.opt("y") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return Action.None
        val z = (event.opt("z") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
        pins.minByOrNull { hypot(it.x - x, it.y - y) }?.takeIf { hypot(it.x - x, it.y - y) <= SAME_PIN_M }?.let { return Action.Track(it.id) }
        return Action.NewPin(x, y, z)
    }

    /**
     * Réglages de la page : carte pleine (la tablette n'est qu'une carte), pas de minimap, double toucher = destination,
     * légende et textes en français. `colors` et `labels` : par catégorie, null = repères.
     */
    fun config(colors: Map<PoiCategory?, String>, labels: Map<PoiCategory?, String>, locale: Map<String, String>): JSONObject {
        val categories = JSONArray().put(category(PIN, labels[null], PIN_ICON, colors[null]))
        for (cat in PoiCategory.entries) categories.put(category(cat.key, labels[cat], ICONS.getValue(cat), colors[cat]))
        return JSONObject()
            .put("minimap", JSONObject().put("enabled", false))
            .put(
                "map",
                JSONObject()
                    .put("doubleClickWaypoint", true)
                    .put("rightClickWaypoint", false)
                    .put("showCoordinates", false)
                    .put("search", true)
                    .put("subtitle", "MATE")
                    // Rendu à la densité 1 (au lieu de 1,5) : fluide sur la tablette, mesuré le 15/11.
                    .put("resolution", RESOLUTION),
            )
            .put("gps", JSONObject().put("units", "metric"))
            .put("categories", categories)
            .put("locale", JSONObject(locale))
    }

    private fun category(id: String, label: String?, icon: String, color: String?) = JSONObject()
        .put("id", id).put("label", label ?: id).put("icon", icon).apply { color?.let { put("color", it) } }

    /** Fichiers de données à (re)télécharger : absents de la tablette ou d'empreinte différente de celle de l'ordinateur. */
    fun missing(remote: Map<String, String>, local: Map<String, String?>): Set<String> = remote.filter { (name, sha) -> local[name] != sha }.keys

    private const val RESOLUTION = 0.67

    /** Dernier envoi de chaque sorte d'événement : rien n'est renvoyé à la page tant que ça ne change pas. */
    class Sent {
        private val last = mutableMapOf<String, String>()

        /** Vrai (et retenu) si `json` diffère du dernier envoi de `name`. */
        fun changed(name: String, json: String): Boolean {
            if (last[name] == json) return false
            last[name] = json
            return true
        }

        fun forget(name: String) {
            last.remove(name)
        }

        fun clear() = last.clear()
    }

    /** Les quatre fichiers de données que la page lit sous `/data/` (même liste que `MAP3D_FILES` du compagnon). */
    val FILES = setOf("manifest.json", "world.glb", "roads.bin", "buildings.bin")
}
