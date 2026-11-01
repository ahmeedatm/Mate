package app.mate.ui.mixer

import app.mate.net.MixerChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applis choisies d'une table de mixage posée sur un profil (⚙ du mode Modifier), dans l'ordre voulu. Les ids
 * d'applis viennent de l'ordinateur (Windows) : le choix est rangé par ordinateur dans la config du widget,
 * `mixerApps[<id de l'ordinateur>] = [{id, name}]`. Sans choix : toutes les applis, comme avant.
 *
 * Un groupe est un choix qui réunit plusieurs applis sur une voie (le jeu, Discord, la musique) : `{id: "group:…",
 * name, members: [{id, name}]}`. Son curseur et son Muet règlent tous les membres.
 */
object MixerApps {
    data class App(val id: String, val name: String, val members: List<App> = emptyList()) {
        val isGroup get() = members.isNotEmpty()
    }

    private const val GROUP_PREFIX = "group:"

    fun isGroupId(id: String) = id.startsWith(GROUP_PREFIX)

    /** Groupe de nom `name`, d'identifiant `key` (unique dans la config) ; ses membres sont des applis, jamais d'autres groupes. */
    fun group(key: String, name: String, members: List<App>) = App(GROUP_PREFIX + key, name, members)

    private const val KEY = "mixerApps"

    fun read(config: JSONObject, computerId: String?): List<App> {
        val list = computerId?.let { config.optJSONObject(KEY)?.optJSONArray(it) } ?: return emptyList()
        return parse(list)
    }

    private fun parse(list: JSONArray): List<App> = (0 until list.length()).mapNotNull { i ->
        list.optJSONObject(i)?.let { o ->
            o.optString("id").takeIf { it.isNotEmpty() }?.let { App(it, o.optString("name", it), o.optJSONArray("members")?.let(::parse).orEmpty()) }
        }
    }

    private fun json(apps: List<App>): JSONArray = JSONArray(
        apps.map { app ->
            JSONObject().put("id", app.id).put("name", app.name).also { if (app.isGroup) it.put("members", json(app.members)) }
        },
    )

    /** Range le choix ; faux si rien n'a changé (pas d'écriture inutile). */
    fun write(config: JSONObject, computerId: String?, apps: List<App>): Boolean {
        if (computerId == null || read(config, computerId) == apps) return false
        val byComputer = config.optJSONObject(KEY) ?: JSONObject().also { config.put(KEY, it) }
        byComputer.put(computerId, json(apps))
        return true
    }

    /**
     * Voies d'applis à montrer : sans choix, toutes celles qui ont du son ; sinon le choix dans son ordre, une appli
     * sans son gardant sa place (volume null : grisée, ne se pilote pas). `live` null : pas de volume par appli (Mac).
     */
    fun channels(chosen: List<App>, live: List<MixerChannel>?): List<MixerChannel>? {
        if (live == null) return null
        if (chosen.isEmpty()) return live
        return chosen.map { app ->
            if (app.isGroup) groupChannel(app, live) else live.firstOrNull { it.id == app.id } ?: MixerChannel(app.id, app.name, null, false, null)
        }
    }

    /**
     * Voie d'un groupe, d'après ses membres qui ont du son : le plus fort volume et le plus fort niveau (le curseur
     * suit le membre le plus haut), coupé seulement si tous le sont. Aucun membre ne joue : volume null, grisée.
     */
    private fun groupChannel(group: App, live: List<MixerChannel>): MixerChannel {
        val playing = live.filter { c -> group.members.any { it.id == c.id } }
        val volumes = playing.mapNotNull { it.volume }
        return MixerChannel(
            group.id,
            group.name,
            volumes.maxOrNull(),
            volumes.isNotEmpty() && playing.filter { it.volume != null }.all { it.muted },
            playing.mapNotNull { it.level }.maxOrNull(),
        )
    }

    /** Applis à régler ensemble quand on touche la voie `id` ; null si ce n'est pas un groupe choisi. */
    fun membersOf(chosen: List<App>, id: String): List<String>? = chosen.firstOrNull { it.id == id && it.isGroup }?.members?.map { it.id }

    /**
     * Applis proposées dans le ⚙ : les choisies d'abord (dans leur ordre), puis celles qui ont du son en ce moment,
     * sous leur nom affiché (`name`, celui de la table).
     */
    fun candidates(chosen: List<App>, live: List<MixerChannel>?, name: (MixerChannel) -> String = { it.name }): List<App> =
        chosen + live.orEmpty().filter { c -> chosen.none { it.id == c.id } }.map { App(it.id, name(it)) }

    /** Voie d'une appli choisie qui ne fait pas de son en ce moment (grisée, « pas de son ») ; jamais la sortie ni le micro. */
    fun isSilent(id: String, live: List<MixerChannel>?): Boolean =
        live != null && id != "master" && id != "mic" && live.none { it.id == id }

    /** Comme [isSilent] pour une voie affichée : un groupe est grisé quand aucun de ses membres ne joue (volume null). */
    fun isSilent(channel: MixerChannel, live: List<MixerChannel>?): Boolean =
        if (isGroupId(channel.id)) live != null && channel.volume == null else isSilent(channel.id, live)
}
