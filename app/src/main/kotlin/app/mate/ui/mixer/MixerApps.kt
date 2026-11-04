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
 *
 * Deux voies spéciales se choisissent comme une appli, sans membres rangés (Windows) : « premier plan » suit l'appli qu'on
 * regarde sur l'ordinateur ; « autres » réunit les applis qui n'ont pas de voie (ni choisie, ni dans un groupe, ni au
 * premier plan quand sa voie est là), le son du système exclu.
 *
 * Applis masquées (« Ne plus afficher », appui long sur une voie) : rangées par ordinateur comme le choix, mais pour
 * toutes les tables (`mixerHidden[<id de l'ordinateur>]`, préférences de la tablette, [MixerHidden]). Elles n'ont plus
 * de voie, même choisies, et ne comptent ni dans un groupe ni dans « autres » ; la voie « premier plan » les suit encore.
 */
object MixerApps {
    data class App(val id: String, val name: String, val members: List<App> = emptyList()) {
        val isGroup get() = members.isNotEmpty()

        /** Voie qui se calcule (groupe, premier plan, autres) : elle n'est pas une appli de l'ordinateur. */
        val isVirtual get() = isVirtualId(id)
    }

    const val FRONT_ID = "special:front"
    const val OTHERS_ID = "special:others"
    private const val SYSTEM_ID = "system"

    fun isVirtualId(id: String) = isGroupId(id) || id == FRONT_ID || id == OTHERS_ID

    fun front(name: String) = App(FRONT_ID, name)

    fun others(name: String) = App(OTHERS_ID, name)

    private const val GROUP_PREFIX = "group:"

    fun isGroupId(id: String) = id.startsWith(GROUP_PREFIX)

    /** Groupe de nom `name`, d'identifiant `key` (unique dans la config) ; ses membres sont des applis, jamais d'autres groupes. */
    fun group(key: String, name: String, members: List<App>) = App(GROUP_PREFIX + key, name, members)

    private const val KEY = "mixerApps"
    const val HIDDEN_KEY = "mixerHidden"

    fun read(config: JSONObject, computerId: String?, key: String = KEY): List<App> {
        val list = computerId?.let { config.optJSONObject(key)?.optJSONArray(it) } ?: return emptyList()
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
    fun write(config: JSONObject, computerId: String?, apps: List<App>, key: String = KEY): Boolean {
        if (computerId == null || read(config, computerId, key) == apps) return false
        val byComputer = config.optJSONObject(key) ?: JSONObject().also { config.put(key, it) }
        byComputer.put(computerId, json(apps))
        return true
    }

    /** Seule une appli de l'ordinateur se masque : ni la sortie, ni le micro, ni une voie calculée. */
    fun canHide(id: String) = id != "master" && id != "mic" && !isVirtualId(id)

    /** Masque `app` pour cet ordinateur ; rend la liste des masquées (vide sans ordinateur). */
    fun hide(store: JSONObject, computerId: String?, app: App): List<App> {
        val hidden = read(store, computerId, HIDDEN_KEY)
        val next = if (hidden.any { it.id == app.id }) hidden else hidden + app
        write(store, computerId, next, HIDDEN_KEY)
        return if (computerId == null) emptyList() else next
    }

    /**
     * Choix refait dans le ⚙ (`edited`, sans les masquées qu'il ne montre pas) : les applis de `previous` masquées y
     * gardent leur place, pour revenir sur la table quand on les affiche de nouveau.
     */
    fun keepHidden(previous: List<App>, edited: List<App>, hidden: Set<String>): List<App> {
        val result = edited.toMutableList()
        previous.forEachIndexed { i, app -> if (app.id in hidden) result.add(i.coerceAtMost(result.size), app) }
        return result.toList()
    }

    /** Fait revenir l'appli `id` ; rend la liste des masquées. */
    fun unhide(store: JSONObject, computerId: String?, id: String): List<App> {
        val next = read(store, computerId, HIDDEN_KEY).filterNot { it.id == id }
        write(store, computerId, next, HIDDEN_KEY)
        return next
    }

    /**
     * Voies d'applis à montrer : sans choix, toutes celles qui ont du son ; sinon le choix dans son ordre, une appli
     * sans son gardant sa place (volume null : grisée, ne se pilote pas). `live` null : pas de volume par appli (Mac).
     * `front` : appli au premier plan de l'ordinateur (id de session), pour la voie « premier plan ». `hidden` : ids
     * des applis masquées.
     */
    fun channels(chosen: List<App>, live: List<MixerChannel>?, front: String? = null, hidden: Set<String> = emptySet()): List<MixerChannel>? {
        if (live == null) return null
        val shown = live.filter { it.id !in hidden }
        if (chosen.isEmpty()) return shown
        val picked = chosen.filter { it.id !in hidden }
        return picked.map { app ->
            when {
                app.isGroup -> combine(app, shown.filter { c -> app.members.any { it.id == c.id } })
                app.id == FRONT_ID -> frontChannel(app, live, front)
                app.id == OTHERS_ID -> combine(app, shown.filter { it.id in othersIds(picked, shown, front) })
                else -> shown.firstOrNull { it.id == app.id } ?: MixerChannel(app.id, app.name, null, false, null)
            }
        }
    }

    /** Voie de l'appli au premier plan, sous le nom de cette appli ; sans son (ou inconnue) : grisée, au nom de la voie. */
    private fun frontChannel(app: App, live: List<MixerChannel>, front: String?): MixerChannel {
        val c = live.firstOrNull { it.id == front } ?: return MixerChannel(app.id, app.name, null, false, null)
        return c.copy(id = app.id)
    }

    /**
     * Voie qui réunit `playing`, d'après ses membres qui ont du son : le plus fort volume et le plus fort niveau (le
     * curseur suit le membre le plus haut), coupé seulement si tous le sont. Aucun ne joue : volume null, grisée.
     */
    private fun combine(voie: App, playing: List<MixerChannel>): MixerChannel {
        val volumes = playing.mapNotNull { it.volume }
        return MixerChannel(
            voie.id,
            voie.name,
            volumes.maxOrNull(),
            volumes.isNotEmpty() && playing.filter { it.volume != null }.all { it.muted },
            playing.mapNotNull { it.level }.maxOrNull(),
        )
    }

    /** Applis qui ont du son et pas de voie à elles : ni choisies, ni dans un groupe, ni devant si sa voie est là. */
    private fun othersIds(chosen: List<App>, live: List<MixerChannel>, front: String?): List<String> {
        val taken = chosen.flatMap { if (it.isGroup) it.members.map(App::id) else listOf(it.id) }.toSet()
        val devant = front.takeIf { chosen.any { it.id == FRONT_ID } }
        return live.map { it.id }.filter { it != SYSTEM_ID && it !in taken && it != devant }
    }

    /**
     * Applis à régler ensemble quand on touche la voie `id` ; null si c'est une voie simple. Premier plan : l'appli
     * devant (liste vide si elle ne fait pas de son). `live` et `front` : le dernier état lu de l'ordinateur.
     */
    fun membersOf(chosen: List<App>, id: String, live: List<MixerChannel>? = null, front: String? = null, hidden: Set<String> = emptySet()): List<String>? {
        val app = chosen.firstOrNull { it.id == id } ?: return null
        val sound = live.orEmpty()
        return when {
            app.isGroup -> app.members.map { it.id }.filter { it !in hidden }
            id == FRONT_ID -> listOfNotNull(front).filter { f -> sound.any { it.id == f } }
            id == OTHERS_ID -> othersIds(chosen.filter { it.id !in hidden }, sound.filter { it.id !in hidden }, front)
            else -> null
        }
    }

    /**
     * Applis proposées dans le ⚙ : les choisies d'abord (dans leur ordre), puis les voies spéciales pas encore
     * choisies (`specials`, Windows seulement : `live` non null), puis celles qui ont du son en ce moment, sous leur
     * nom affiché (`name`, celui de la table). Les masquées (`hidden`) n'y sont pas : elles reviennent par leur liste.
     */
    fun candidates(
        chosen: List<App>,
        live: List<MixerChannel>?,
        name: (MixerChannel) -> String = { it.name },
        specials: List<App> = emptyList(),
        hidden: Set<String> = emptySet(),
    ): List<App> =
        chosen.filter { it.id !in hidden } +
            (if (live == null) emptyList() else specials.filter { s -> chosen.none { it.id == s.id } }) +
            live.orEmpty().filter { c -> c.id !in hidden && chosen.none { it.id == c.id } }.map { App(it.id, name(it)) }

    /** Voie d'une appli choisie qui ne fait pas de son en ce moment (grisée, « pas de son ») ; jamais la sortie ni le micro. */
    fun isSilent(id: String, live: List<MixerChannel>?): Boolean =
        live != null && id != "master" && id != "mic" && live.none { it.id == id }

    /** Comme [isSilent] pour une voie affichée : une voie calculée est grisée quand rien ne joue dessus (volume null). */
    fun isSilent(channel: MixerChannel, live: List<MixerChannel>?): Boolean =
        if (isVirtualId(channel.id)) live != null && channel.volume == null else isSilent(channel.id, live)
}
