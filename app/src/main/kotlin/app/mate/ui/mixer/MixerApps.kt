package app.mate.ui.mixer

import app.mate.net.MixerChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applis choisies d'une table de mixage posée sur un profil (⚙ du mode Modifier), dans l'ordre voulu. Les ids
 * d'applis viennent de l'ordinateur (Windows) : le choix est rangé par ordinateur dans la config du widget,
 * `mixerApps[<id de l'ordinateur>] = [{id, name}]`. Sans choix : toutes les applis, comme avant.
 */
object MixerApps {
    data class App(val id: String, val name: String)

    private const val KEY = "mixerApps"

    fun read(config: JSONObject, computerId: String?): List<App> {
        val list = computerId?.let { config.optJSONObject(KEY)?.optJSONArray(it) } ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { o -> o.optString("id").takeIf { it.isNotEmpty() }?.let { App(it, o.optString("name", it)) } }
        }
    }

    /** Range le choix ; faux si rien n'a changé (pas d'écriture inutile). */
    fun write(config: JSONObject, computerId: String?, apps: List<App>): Boolean {
        if (computerId == null || read(config, computerId) == apps) return false
        val byComputer = config.optJSONObject(KEY) ?: JSONObject().also { config.put(KEY, it) }
        byComputer.put(computerId, JSONArray(apps.map { JSONObject().put("id", it.id).put("name", it.name) }))
        return true
    }

    /**
     * Voies d'applis à montrer : sans choix, toutes celles qui ont du son ; sinon le choix dans son ordre, une appli
     * sans son gardant sa place (volume null : grisée, ne se pilote pas). `live` null : pas de volume par appli (Mac).
     */
    fun channels(chosen: List<App>, live: List<MixerChannel>?): List<MixerChannel>? {
        if (live == null) return null
        if (chosen.isEmpty()) return live
        return chosen.map { app -> live.firstOrNull { it.id == app.id } ?: MixerChannel(app.id, app.name, null, false, null) }
    }

    /**
     * Applis proposées dans le ⚙ : les choisies d'abord (dans leur ordre), puis celles qui ont du son en ce moment,
     * sous leur nom affiché (`name`, celui de la table).
     */
    fun candidates(chosen: List<App>, live: List<MixerChannel>?, name: (MixerChannel) -> String = { it.name }): List<App> =
        chosen + live.orEmpty().filter { c -> chosen.none { it.id == c.id } }.map { App(it.id, name(it)) }

    /** Voie d'une appli choisie qui ne fait pas de son en ce moment (grisée, « pas de son ») ; jamais la sortie ni le micro. */
    fun isSilent(id: String, live: List<MixerChannel>?): Boolean =
        live != null && id != "master" && id != "mic" && live.none { it.id == id }
}
