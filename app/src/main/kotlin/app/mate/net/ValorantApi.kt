package app.mate.net

import org.json.JSONObject

/** OFF : client Riot ou Valorant fermé ; QUEUE : en file ; PREGAME : sélection d'agents ; INGAME : en partie. */
enum class ValorantState { OFF, MENU, QUEUE, PREGAME, INGAME }

/** `name` null : joueur incognito ; `agent` null : pas encore choisi, ou agent inconnu du compagnon. */
data class ValorantPlayer(
    val name: String?,
    val agent: String?,
    val locked: Boolean,
    val tier: Int?,
    val rank: String?,
    val rr: Int?,
    val ally: Boolean,
    val me: Boolean,
)

data class ValorantMe(val name: String, val tier: Int, val rank: String, val rr: Int?, val level: Int?)

data class ValorantScore(val ally: Int, val enemy: Int)

data class Valorant(
    val state: ValorantState,
    val queue: String?,
    val map: String?,
    val score: ValorantScore?,
    val me: ValorantMe?,
    val players: List<ValorantPlayer>,
) {
    /** Dans l'ordre du compagnon : du meilleur rang au plus bas. */
    val allies get() = players.filter { it.ally }
    val enemies get() = players.filter { !it.ally }
}

/** Partie Valorant en cours, lue par le compagnon dans le client Riot (src/valorant.ts) : aucun jeton Riot ne vient ici. */
class ValorantApi(private val http: Http) {

    suspend fun state(): Valorant = http.io { parse(http.get("/valorant")) }

    companion object {
        private fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
        private fun JSONObject.int(key: String) = if (isNull(key) || !has(key)) null else optInt(key)

        fun parse(json: String): Valorant {
            val o = JSONObject(json)
            val state = when (o.optString("state")) {
                "off" -> ValorantState.OFF
                "queue" -> ValorantState.QUEUE
                "pregame" -> ValorantState.PREGAME
                "ingame" -> ValorantState.INGAME
                else -> ValorantState.MENU
            }
            val rows = o.optJSONArray("players")
            val players = (0 until (rows?.length() ?: 0)).map {
                val p = rows!!.getJSONObject(it)
                ValorantPlayer(
                    p.text("name"),
                    p.text("agent"),
                    p.optBoolean("locked", true),
                    p.int("tier"),
                    p.text("rank"),
                    p.int("rr"),
                    p.optBoolean("ally"),
                    p.optBoolean("me"),
                )
            }
            val me = o.optJSONObject("me")?.let {
                ValorantMe(it.optString("name"), it.optInt("tier"), it.optString("rank"), it.int("rr"), it.int("level"))
            }
            val score = o.optJSONObject("score")?.let { ValorantScore(it.optInt("ally"), it.optInt("enemy")) }
            return Valorant(state, o.text("queue"), o.text("map"), score, me, players)
        }
    }
}
