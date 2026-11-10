package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

/** OFF : client Riot ou Valorant fermé ; QUEUE : en file ; PREGAME : sélection d'agents ; INGAME : en partie. */
enum class ValorantState { OFF, MENU, QUEUE, PREGAME, INGAME }

/**
 * `name` null : joueur incognito ; `agent` null : pas encore choisi, ou agent inconnu du compagnon. Les stats de tracker
 * (`level` à `party`) sont null tant que le compagnon ne les a pas lues, ou s'il est plus ancien que la tablette.
 */
data class ValorantPlayer(
    val name: String?,
    val agent: String?,
    val locked: Boolean,
    val tier: Int?,
    val rank: String?,
    val rr: Int?,
    val ally: Boolean,
    val me: Boolean,
    val agentId: String? = null,
    val level: Int? = null,
    val peakTier: Int? = null,
    val winRate: Int? = null,
    val kd: Double? = null,
    val hs: Int? = null,
    /** Joueurs venus ensemble : même numéro ; null : seul. */
    val party: Int? = null,
)

data class ValorantMe(val name: String, val tier: Int, val rank: String, val rr: Int?, val level: Int?, val peakTier: Int? = null)

data class ValorantScore(val ally: Int, val enemy: Int)

/** Une de mes 5 dernières classées : `won` null pour une égalité ; le reste null tant que le détail n'est pas lu. */
data class ValorantMatch(
    val won: Boolean?,
    val score: ValorantScore?,
    val map: String?,
    val agent: String?,
    val agentId: String?,
    val kills: Int?,
    val deaths: Int?,
    val assists: Int?,
    val acs: Int?,
    val rr: Int,
)

enum class ValorantEndStatus { LOADING, READY, MISSING }

/** Fin d'un round : élimination, spike explosé, spike désamorcé, temps écoulé. */
enum class ValorantRoundEnd { ELIMINATION, DETONATE, DEFUSE, TIME }

/** Un round vu de mon équipe : `won` null si l'ordre est inconnu (en partie) ou sans gagnant ; `end` null en partie. */
data class ValorantRound(val won: Boolean?, val end: ValorantRoundEnd?)

/** Mes classées d'aujourd'hui : RR gagnés ou perdus, victoires, défaites ; résultats de la plus ancienne (null : nul). */
data class ValorantToday(val rr: Int, val wins: Int, val losses: Int, val results: List<Boolean?>)

/** Mon bilan sur la carte jouée parmi mes `window` dernières classées ; résultats de la plus ancienne. */
data class ValorantMapStats(val wins: Int, val games: Int, val window: Int, val results: List<Boolean?>)

data class ValorantEndPlayer(
    val name: String?,
    val agent: String?,
    val agentId: String?,
    val tier: Int,
    val rank: String,
    val ally: Boolean,
    val me: Boolean,
    val acs: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val hs: Int?,
    val adr: Int,
    val firstKills: Int,
    val mvp: Boolean,
)

/** Tableau des scores de la partie qui vient de finir ; `ffa` : combat à mort, classé par kills. */
data class ValorantEnd(
    val status: ValorantEndStatus,
    val map: String?,
    val queue: String?,
    val ffa: Boolean,
    val won: Boolean?,
    val score: ValorantScore?,
    val players: List<ValorantEndPlayer>,
    /** Identifiant de la carte (image : /valorant/map) ; null pour un compagnon d'avant le 10/11. */
    val mapId: String? = null,
    val rounds: List<ValorantRound> = emptyList(),
    /** RR que cette partie m'a rapportés ; null tant que Riot ne l'a pas compté. */
    val rr: Int? = null,
)

data class Valorant(
    val state: ValorantState,
    val queue: String?,
    val map: String?,
    val score: ValorantScore?,
    val me: ValorantMe?,
    val players: List<ValorantPlayer>,
    val history: List<ValorantMatch> = emptyList(),
    val end: ValorantEnd? = null,
    /** Ajouts du 10/11, absents d'un compagnon plus ancien : carte, rounds en cours, journée, bilan sur la carte. */
    val mapId: String? = null,
    val rounds: List<ValorantRound> = emptyList(),
    val today: ValorantToday? = null,
    val mapStats: ValorantMapStats? = null,
) {
    /** Dans l'ordre du compagnon : du meilleur rang au plus bas. */
    val allies get() = players.filter { it.ally }
    val enemies get() = players.filter { !it.ally }
}

/** Partie Valorant en cours, lue par le compagnon dans le client Riot (src/valorant.ts) : aucun jeton Riot ne vient ici. */
class ValorantApi(private val http: Http) {

    suspend fun state(): Valorant = http.io { parse(http.get("/valorant")) }

    /** Icône carrée d'un agent (128 px), téléchargée par l'ordinateur ; null s'il ne la connaît pas. */
    suspend fun agentIcon(id: String): ByteArray? = http.io { http.bytesOrNull("/valorant/agent?id=" + http.encode(id), timeoutMs = 15_000) }

    /** Image de la carte (960 px de large), téléchargée et réduite par l'ordinateur ; null s'il ne la connaît pas. */
    suspend fun mapImage(id: String): ByteArray? = http.io { http.bytesOrNull("/valorant/map?id=" + http.encode(id), timeoutMs = 20_000) }

    /** Logo d'un rang (0 à 27, 128 px). */
    suspend fun rankIcon(tier: Int): ByteArray? = http.io { http.bytesOrNull("/valorant/rank?tier=$tier", timeoutMs = 15_000) }

    companion object {
        private fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
        private fun JSONObject.int(key: String) = if (isNull(key) || !has(key)) null else optInt(key)
        private fun JSONObject.double(key: String) = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }
        private fun JSONObject.bool(key: String) = if (isNull(key) || !has(key)) null else optBoolean(key)
        private fun JSONObject.score(key: String) = optJSONObject(key)?.let { ValorantScore(it.optInt("ally"), it.optInt("enemy")) }
        /** Éléments d'une liste ; un élément qui n'est pas un objet est sauté (le reste du widget s'affiche). */
        private fun <T> JSONArray?.items(read: (JSONObject) -> T) = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it)?.let(read) }

        /** Liste de booléens où null garde sa place (résultat inconnu). */
        private fun JSONArray?.results(): List<Boolean?> = (0 until (this?.length() ?: 0)).map { if (this!!.isNull(it)) null else optBoolean(it) }

        private val ROUND_ENDS = mapOf(
            "elimination" to ValorantRoundEnd.ELIMINATION,
            "detonate" to ValorantRoundEnd.DETONATE,
            "defuse" to ValorantRoundEnd.DEFUSE,
            "time" to ValorantRoundEnd.TIME,
        )

        private fun today(t: JSONObject) = ValorantToday(t.optInt("rr"), t.optInt("wins"), t.optInt("losses"), t.optJSONArray("results").results())

        private fun mapStats(m: JSONObject) =
            ValorantMapStats(m.optInt("wins"), m.optInt("games"), m.optInt("window"), m.optJSONArray("results").results())

        fun parse(json: String): Valorant {
            val o = JSONObject(json)
            val state = when (o.optString("state")) {
                "off" -> ValorantState.OFF
                "queue" -> ValorantState.QUEUE
                "pregame" -> ValorantState.PREGAME
                "ingame" -> ValorantState.INGAME
                else -> ValorantState.MENU
            }
            val players = o.optJSONArray("players").items { p ->
                ValorantPlayer(
                    p.text("name"),
                    p.text("agent"),
                    p.optBoolean("locked", true),
                    p.int("tier"),
                    p.text("rank"),
                    p.int("rr"),
                    p.optBoolean("ally"),
                    p.optBoolean("me"),
                    agentId = p.text("agentId"),
                    level = p.int("level"),
                    peakTier = p.int("peakTier"),
                    winRate = p.int("winRate"),
                    kd = p.double("kd"),
                    hs = p.int("hs"),
                    party = p.int("party"),
                )
            }
            val me = o.optJSONObject("me")?.let {
                ValorantMe(it.optString("name"), it.optInt("tier"), it.optString("rank"), it.int("rr"), it.int("level"), it.int("peakTier"))
            }
            val history = o.optJSONArray("history").items { m ->
                ValorantMatch(
                    m.bool("won"),
                    m.score("score"),
                    m.text("map"),
                    m.text("agent"),
                    m.text("agentId"),
                    m.int("kills"),
                    m.int("deaths"),
                    m.int("assists"),
                    m.int("acs"),
                    m.optInt("rr"),
                )
            }
            return Valorant(
                state,
                o.text("queue"),
                o.text("map"),
                o.score("score"),
                me,
                players,
                history,
                o.optJSONObject("end")?.let(::end),
                mapId = o.text("mapId"),
                rounds = o.optJSONArray("rounds").results().map { ValorantRound(it, null) },
                today = o.optJSONObject("today")?.let(::today),
                mapStats = o.optJSONObject("mapStats")?.let(::mapStats),
            )
        }

        private fun end(e: JSONObject): ValorantEnd {
            val status = when (e.optString("status")) {
                "ready" -> ValorantEndStatus.READY
                "missing" -> ValorantEndStatus.MISSING
                else -> ValorantEndStatus.LOADING
            }
            val players = e.optJSONArray("players").items { p ->
                ValorantEndPlayer(
                    p.text("name"),
                    p.text("agent"),
                    p.text("agentId"),
                    p.optInt("tier"),
                    p.optString("rank"),
                    p.optBoolean("ally"),
                    p.optBoolean("me"),
                    p.optInt("acs"),
                    p.optInt("kills"),
                    p.optInt("deaths"),
                    p.optInt("assists"),
                    p.int("hs"),
                    p.optInt("adr"),
                    p.optInt("firstKills"),
                    p.optBoolean("mvp"),
                )
            }
            val rounds = e.optJSONArray("rounds").items { r -> ValorantRound(r.bool("won"), ROUND_ENDS[r.optString("end")]) }
            return ValorantEnd(
                status,
                e.text("map"),
                e.text("queue"),
                e.optString("mode") == "ffa",
                e.bool("won"),
                e.score("score"),
                players,
                mapId = e.text("mapId"),
                rounds = rounds,
                rr = e.int("rr"),
            )
        }
    }
}
