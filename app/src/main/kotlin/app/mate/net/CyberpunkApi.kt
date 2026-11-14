package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

/** Ce que le sondage léger dit d'un ordinateur : `available` faux = plugin coupé ou compagnon plus ancien que la tablette. */
data class CyberpunkPresence(
    val available: Boolean,
    val gameFound: Boolean = false,
    val modInstalled: Boolean = false,
    val running: Boolean = false,
    val inGame: Boolean = false,
    /** Change à chaque partie lancée ; sert à ne pas rouvrir la page fermée à la main. */
    val session: String? = null,
) {
    companion object {
        val UNAVAILABLE = CyberpunkPresence(available = false)
    }
}

/** OFF : jeu fermé ; MENU : jeu lancé, pas de partie ; INGAME : partie chargée, état frais ; UNAVAILABLE : routes absentes ou plugin coupé. */
enum class CyberpunkStatus { UNAVAILABLE, OFF, MENU, INGAME }

data class CyberpunkInstall(
    val gameFound: Boolean,
    val cet: Boolean,
    val modInstalled: Boolean,
    val modVersion: String?,
    val current: String?,
    val pending: Boolean,
) {
    companion object {
        val NONE = CyberpunkInstall(gameFound = false, cet = false, modInstalled = false, modVersion = null, current = null, pending = false)
    }
}

data class CyberpunkAttrs(val body: Int, val ref: Int, val tech: Int, val int: Int, val cool: Int)

/** `health` et `humanity` en pour cent. */
data class CyberpunkPlayer(
    val level: Int,
    val cred: Int,
    val attrs: CyberpunkAttrs,
    val health: Int,
    val humanity: Int,
    val money: Long,
    val attrPoints: Int,
    val perkPoints: Int,
)

data class CyberpunkPos(val x: Double, val y: Double, val z: Double, val yaw: Double)

data class CyberpunkImplant(val tdb: String?, val name: String, val quality: String?)

/** `kind` : "quest" ou "pin". `x`, `y` : position de l'objectif dans le monde, si le mod la donne. */
data class CyberpunkTracked(val kind: String, val id: String?, val quest: String?, val desc: String?, val x: Double? = null, val y: Double? = null)

enum class CyberpunkCallPhase { INCOMING, ACTIVE }

data class CyberpunkCall(val phase: CyberpunkCallPhase, val contact: String?, val audio: Boolean)

/** Compteurs des blocs lents : un bloc se relit quand son compteur change. */
data class CyberpunkBlocks(val inv: Int, val contacts: Int, val quests: Int, val cyber: Int = 0)

enum class CyberpunkBody { MAN, WOMAN }

/** Implant posé ou possédé ; `key` = id TweakDB + graine, ce que l'ordre de pose renvoie au mod. */
data class CyberwareImplant(
    val key: String,
    val tdb: String?,
    val name: String,
    val quality: String?,
    val cost: Int?,
    val blocked: Boolean,
    val area: String? = null,
)

data class CyberwareSlot(val locked: Boolean, val item: CyberwareImplant?)

data class CyberwareCapacity(val free: Double?, val used: Double?, val max: Double?)

/** Écran cyberware (`cyberware.json` du mod) ; pas prêt tant que le mod (1.2.0 au moins) ne l'a pas écrit. */
data class CyberwareBlock(
    val body: CyberpunkBody,
    val capacity: CyberwareCapacity?,
    /** Zones que le mod refuse de changer (`zone-fermee`) : affichées sans pose possible. */
    val closed: Set<String>,
    val zones: Map<String, List<CyberwareSlot>>,
    val owned: List<CyberwareImplant>,
) {
    val ready get() = zones.isNotEmpty()
}

data class CyberpunkPin(val pid: String, val x: Double, val y: Double, val z: Double)

data class CyberpunkState(
    val seq: Long,
    val inGame: Boolean,
    val session: String?,
    val player: CyberpunkPlayer?,
    val pos: CyberpunkPos?,
    /** Zone → emplacements (null : vide). */
    val cyberware: Map<String, List<CyberpunkImplant?>>,
    val tracked: CyberpunkTracked?,
    val call: CyberpunkCall?,
    val blocks: CyberpunkBlocks,
    val pins: List<CyberpunkPin>,
)

data class CyberpunkSnapshot(val status: CyberpunkStatus, val install: CyberpunkInstall, val state: CyberpunkState?)

data class CyberpunkItem(val tdb: String?, val name: String, val type: String?, val quality: String?, val qty: Int, val usable: Boolean)

data class CyberpunkContact(val id: String?, val name: String, val callable: Boolean, val unread: Int)

/** `hash` : identifiant stable du fil (chaîne de chiffres, vide si le mod est plus ancien) ; `canReply` : des choix de réponse sont ouverts. */
data class CyberpunkThread(
    val name: String,
    val preview: String?,
    val unread: Int,
    val time: String?,
    val hash: String = "",
    val multi: Boolean = false,
    val canReply: Boolean = false,
)

data class CyberpunkThreadMessage(val hash: String, val text: String, val player: Boolean)

data class CyberpunkThreadChoice(val hash: String, val text: String)

/** `version` : à rendre tel quel à `reply`, le mod refuse si le fil a bougé depuis. */
data class CyberpunkThreadDetail(val version: String, val messages: List<CyberpunkThreadMessage>, val choices: List<CyberpunkThreadChoice>)

enum class CyberpunkPhoneStatus { ABSENT, LOADING, READY, ERROR }

/** Le « look du jeu » préparé par l'ordinateur : `assets` = noms servis par `phoneAsset`. */
data class CyberpunkPhoneLook(val status: CyberpunkPhoneStatus, val assets: List<String>)

data class CyberpunkContacts(val contacts: List<CyberpunkContact>, val threads: List<CyberpunkThread>) {
    val unread get() = contacts.sumOf { it.unread } + threads.sumOf { it.unread }
}

data class CyberpunkQuest(val id: String?, val title: String, val kind: String?, val objective: String?, val tracked: Boolean)

data class CyberpunkInstallResult(val ok: Boolean, val deferred: Boolean)

data class CyberpunkActionResult(val ok: Boolean, val id: Int?, val res: String?)

/** Repère posé depuis la tablette, gardé par l'ordinateur. */
data class CyberpunkServerPin(val id: String, val x: Double, val y: Double, val z: Double, val name: String?, val tracked: Boolean)

enum class CyberpunkMapStatus { ABSENT, LOADING, READY, ERROR }

/** `affine` : [a, b, c, d, e, f], monde → pixels de la carte ; null tant que la carte n'est pas prête. */
data class CyberpunkMap(
    val status: CyberpunkMapStatus,
    val done: Int,
    val total: Int,
    val tile: Int,
    val levels: Int,
    val width: Int,
    val height: Int,
    val affine: List<Double>?,
)

/**
 * Cyberpunk 2077 sur l'ordinateur Windows, par le mod CET de Mate (backend/src/routes/cyberpunk.ts). Un compagnon sans
 * ces routes (404) ou le plugin coupé (403) se lit « indisponible », jamais comme une erreur.
 */
class CyberpunkApi(private val http: Http) {

    /** Sondage léger (toutes les 4 s, délai court : un ordinateur éteint ne doit pas bloquer les autres). */
    suspend fun presence(): CyberpunkPresence = http.io {
        try {
            parsePresence(http.get("/cyberpunk/presence", timeoutMs = PRESENCE_TIMEOUT_MS))
        } catch (e: HttpException) {
            if (isUnavailable(e)) CyberpunkPresence.UNAVAILABLE else throw e
        }
    }

    suspend fun state(): CyberpunkSnapshot = http.io {
        try {
            parseSnapshot(http.get("/cyberpunk", timeoutMs = STATE_TIMEOUT_MS))
        } catch (e: HttpException) {
            if (isUnavailable(e)) CyberpunkSnapshot(CyberpunkStatus.UNAVAILABLE, CyberpunkInstall.NONE, null) else throw e
        }
    }

    suspend fun inventory(): List<CyberpunkItem> = http.io { parseInventory(http.get("/cyberpunk/inventory", timeoutMs = STATE_TIMEOUT_MS)) }

    suspend fun contacts(): CyberpunkContacts = http.io { parseContacts(http.get("/cyberpunk/contacts", timeoutMs = STATE_TIMEOUT_MS)) }

    /** Fil complet d'une conversation (le jeu la marque lue). 409 `res` `fil-introuvable` si le fil n'existe plus. */
    suspend fun thread(hash: String): CyberpunkThreadDetail = http.io { parseThread(http.get("/cyberpunk/threads/$hash", timeoutMs = ACTION_TIMEOUT_MS)) }

    /** Envoie un choix. 409 `res` `choix-perime` (voir [isStaleChoice]) ou `fil-introuvable` ; 504 sans réponse du mod. */
    suspend fun reply(hash: String, choice: String, version: String) = http.io {
        http.postJson("/cyberpunk/threads/$hash/reply", JSONObject().put("choice", choice).put("version", version), timeoutMs = ACTION_TIMEOUT_MS)
    }

    suspend fun cyberware(): CyberwareBlock = http.io { parseCyberware(http.get("/cyberpunk/cyberware", timeoutMs = STATE_TIMEOUT_MS)) }

    /** Pose l'implant `item` dans le slot. 409 avec `res` et une phrase si le jeu refuse ; 504 sans réponse du mod. */
    suspend fun equip(area: String, slot: Int, item: String) = http.io {
        http.postJson("/cyberpunk/cyberware/equip", JSONObject().put("area", area).put("slot", slot).put("item", item), timeoutMs = ACTION_TIMEOUT_MS)
    }

    suspend fun phoneStatus(): CyberpunkPhoneLook = http.io { parsePhone(http.get("/cyberpunk/phone", timeoutMs = STATE_TIMEOUT_MS)) }

    suspend fun preparePhone() = http.io { http.postJson("/cyberpunk/phone/prepare", JSONObject()) }

    /** Une pièce du téléphone (PNG ou TTF) de la liste de [phoneStatus]. */
    suspend fun phoneAsset(name: String): ByteArray = http.io { http.bytesOrNull("/cyberpunk/phone/" + http.encode(name), timeoutMs = TILE_TIMEOUT_MS) ?: throw HttpException(404, "") }

    suspend fun quests(): List<CyberpunkQuest> = http.io { parseQuests(http.get("/cyberpunk/quests", timeoutMs = STATE_TIMEOUT_MS)) }

    suspend fun install(): CyberpunkInstallResult = http.io { parseInstall(http.postJson("/cyberpunk/install", JSONObject())) }

    suspend fun uninstall(): CyberpunkInstallResult = http.io { parseInstall(http.postJson("/cyberpunk/uninstall", JSONObject())) }

    /** `type` : call, answer, hangup, summon, use, track. Hors partie le compagnon répond 409, sans réponse du mod en 5 s, 504. */
    suspend fun action(type: String, contact: String? = null, tdb: String? = null, kind: String? = null, id: String? = null): CyberpunkActionResult = http.io {
        val body = JSONObject().put("type", type)
        contact?.let { body.put("contact", it) }
        tdb?.let { body.put("tdb", it) }
        kind?.let { body.put("kind", it) }
        id?.let { body.put("id", it) }
        parseAction(http.postJson("/cyberpunk/action", body, timeoutMs = ACTION_TIMEOUT_MS))
    }

    suspend fun pins(): List<CyberpunkServerPin> = http.io { parsePins(http.get("/cyberpunk/pins", timeoutMs = STATE_TIMEOUT_MS)) }

    /** Pose un repère ; rend son identifiant. */
    suspend fun addPin(x: Double, y: Double, z: Double? = null, name: String? = null): String = http.io {
        val body = JSONObject().put("x", x).put("y", y)
        z?.let { body.put("z", it) }
        name?.let { body.put("name", it) }
        JSONObject(http.postJson("/cyberpunk/pins", body)).optString("id")
    }

    suspend fun deletePin(id: String) = http.io { http.delete("/cyberpunk/pins/" + http.encode(id)) }

    suspend fun trackPin(id: String) = http.io { http.postJson("/cyberpunk/pins/" + http.encode(id) + "/track", JSONObject()) }

    suspend fun map(): CyberpunkMap = http.io { parseMap(http.get("/cyberpunk/map", timeoutMs = STATE_TIMEOUT_MS)) }

    suspend fun prepareMap() = http.io { http.postJson("/cyberpunk/map/prepare", JSONObject()) }

    /** Tuile WebP (512 px au plus) ; null si l'ordinateur ne l'a pas. */
    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? = http.io { http.bytesOrNull(tilePath(z, x, y), timeoutMs = TILE_TIMEOUT_MS) }

    companion object {
        private const val PRESENCE_TIMEOUT_MS = 2_000
        private const val STATE_TIMEOUT_MS = 4_000
        private const val ACTION_TIMEOUT_MS = 8_000
        private const val TILE_TIMEOUT_MS = 10_000

        fun tilePath(z: Int, x: Int, y: Int) = "/cyberpunk/tiles/$z/$x/$y.webp"

        /** Route inconnue (compagnon plus ancien) ou plugin coupé : ni jeu, ni erreur. */
        fun isUnavailable(e: HttpException) = e.code == 404 || e.code == 403

        /** Le choix proposé n'est plus ouvert ou le fil a bougé : recharger le fil, ne pas renvoyer. */
        fun isStaleChoice(e: Throwable) = (e as? HttpException)?.res == "choix-perime"

        private fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
        private fun JSONObject.double(key: String) = optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it }

        /** Nombre, ou booléen lu 1 / 0 (un « non lu » sans compte). */
        private fun JSONObject.count(key: String): Int = when (val v = opt(key)) {
            is Number -> v.toInt()
            is Boolean -> if (v) 1 else 0
            else -> 0
        }

        private fun <T> JSONArray?.items(read: (JSONObject) -> T) = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it)?.let(read) }

        fun parsePresence(json: String): CyberpunkPresence {
            val o = JSONObject(json)
            return CyberpunkPresence(
                o.optBoolean("available"), o.optBoolean("gameFound"), o.optBoolean("modInstalled"),
                o.optBoolean("running"), o.optBoolean("inGame"), o.text("session"),
            )
        }

        fun parseSnapshot(json: String): CyberpunkSnapshot {
            val o = JSONObject(json)
            val status = when (o.optString("status")) {
                "ingame" -> CyberpunkStatus.INGAME
                "menu" -> CyberpunkStatus.MENU
                else -> CyberpunkStatus.OFF
            }
            val install = o.optJSONObject("install")?.let {
                CyberpunkInstall(it.optBoolean("gameFound"), it.optBoolean("cet"), it.optBoolean("modInstalled"), it.text("modVersion"), it.text("current"), it.optBoolean("pending"))
            } ?: CyberpunkInstall.NONE
            return CyberpunkSnapshot(status, install, o.optJSONObject("state")?.let(::state))
        }

        private fun state(s: JSONObject): CyberpunkState {
            val blocks = s.optJSONObject("blocks")
            return CyberpunkState(
                seq = s.optLong("seq"),
                inGame = s.optBoolean("inGame"),
                session = s.text("session"),
                player = s.optJSONObject("player")?.let(::player),
                pos = s.optJSONObject("pos")?.let { CyberpunkPos(it.double("x"), it.double("y"), it.double("z"), it.double("yaw")) },
                cyberware = cyberware(s.optJSONObject("cyberware")),
                tracked = s.optJSONObject("tracked")?.let { tracked(it) },
                call = s.optJSONObject("call")?.let(::call),
                blocks = CyberpunkBlocks(
                    blocks?.optInt("inv") ?: 0,
                    blocks?.optInt("contacts") ?: 0,
                    blocks?.optInt("quests") ?: 0,
                    blocks?.optInt("cyber") ?: 0,
                ),
                pins = s.optJSONArray("pins").items { CyberpunkPin(it.optString("pid"), it.double("x"), it.double("y"), it.double("z")) },
            )
        }

        private fun tracked(t: JSONObject): CyberpunkTracked {
            val pos = t.optJSONObject("pos")?.takeIf { it.has("x") && it.has("y") }
            return CyberpunkTracked(t.optString("kind"), t.text("id"), t.text("quest"), t.text("desc"), pos?.double("x"), pos?.double("y"))
        }

        private fun player(p: JSONObject): CyberpunkPlayer {
            val a = p.optJSONObject("attrs")
            val pts = p.optJSONObject("pts")
            return CyberpunkPlayer(
                p.optInt("level"), p.optInt("cred"),
                CyberpunkAttrs(a?.optInt("body") ?: 0, a?.optInt("ref") ?: 0, a?.optInt("tech") ?: 0, a?.optInt("int") ?: 0, a?.optInt("cool") ?: 0),
                p.optInt("health"), p.optInt("humanity"), p.optLong("money"), pts?.optInt("attr") ?: 0, pts?.optInt("perk") ?: 0,
            )
        }

        /** Un emplacement vide vaut `false` (ou null) dans le JSON du mod. */
        private fun cyberware(zones: JSONObject?): Map<String, List<CyberpunkImplant?>> {
            if (zones == null) return emptyMap()
            return zones.keys().asSequence().associateWith { zone ->
                val slots = zones.optJSONArray(zone)
                (0 until (slots?.length() ?: 0)).map { i ->
                    slots!!.optJSONObject(i)?.let { CyberpunkImplant(it.text("tdb"), it.optString("name"), it.text("quality")) }
                }
            }
        }

        private fun call(c: JSONObject): CyberpunkCall? {
            val phase = when (c.optString("phase")) {
                "incoming" -> CyberpunkCallPhase.INCOMING
                "active" -> CyberpunkCallPhase.ACTIVE
                else -> return null
            }
            return CyberpunkCall(phase, c.text("contact"), c.optBoolean("audio", true))
        }

        fun parseInventory(json: String): List<CyberpunkItem> = JSONObject(json).optJSONArray("items").items {
            CyberpunkItem(it.text("tdb"), it.optString("name"), it.text("type"), it.text("quality"), it.optInt("qty", 1), it.optBoolean("usable"))
        }

        fun parseContacts(json: String): CyberpunkContacts {
            val o = JSONObject(json)
            return CyberpunkContacts(
                o.optJSONArray("contacts").items { CyberpunkContact(it.text("id"), it.optString("name"), it.optBoolean("callable"), it.count("unread")) },
                o.optJSONArray("threads").items { CyberpunkThread(it.optString("name"), it.text("preview"), it.count("unread"), it.text("time"), it.optString("hash"), it.optBoolean("multi"), it.optBoolean("canReply")) },
            )
        }

        fun parseThread(json: String): CyberpunkThreadDetail {
            val o = JSONObject(json)
            return CyberpunkThreadDetail(
                o.optString("version"),
                o.optJSONArray("messages").items { CyberpunkThreadMessage(it.optString("hash"), it.optString("text"), it.optBoolean("player")) },
                o.optJSONArray("choices").items { CyberpunkThreadChoice(it.optString("hash"), it.optString("text")) },
            )
        }

        fun parsePhone(json: String): CyberpunkPhoneLook {
            val o = JSONObject(json)
            val status = when (o.optString("status")) {
                "ready" -> CyberpunkPhoneStatus.READY
                "loading" -> CyberpunkPhoneStatus.LOADING
                "error" -> CyberpunkPhoneStatus.ERROR
                else -> CyberpunkPhoneStatus.ABSENT
            }
            val names = o.optJSONArray("assets")
            return CyberpunkPhoneLook(status, (0 until (names?.length() ?: 0)).map { names!!.optString(it) })
        }

        fun parseCyberware(json: String): CyberwareBlock {
            val o = JSONObject(json)
            fun implant(i: JSONObject) = i.text("key")?.let { key ->
                CyberwareImplant(key, i.text("tdb"), i.optString("name").ifEmpty { key }, i.text("quality"), if (i.has("cost")) i.optInt("cost") else null, i.optBoolean("blocked"), i.text("area"))
            }
            val zones = o.optJSONObject("zones")
            val cap = o.optJSONObject("capacity")
            return CyberwareBlock(
                body = if (o.optString("body") == "woman") CyberpunkBody.WOMAN else CyberpunkBody.MAN,
                capacity = cap?.let { c -> CyberwareCapacity(c.num("free"), c.num("used"), c.num("max")) },
                closed = (0 until (o.optJSONArray("closed")?.length() ?: 0)).mapNotNull { o.optJSONArray("closed")?.optString(it) }.toSet(),
                zones = zones?.keys()?.asSequence()?.associateWith { k -> zones.optJSONArray(k).items { CyberwareSlot(it.optBoolean("locked"), it.optJSONObject("item")?.let(::implant)) } }.orEmpty(),
                owned = o.optJSONArray("owned").items(::implant).filterNotNull(),
            )
        }

        private fun JSONObject.num(key: String) = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        fun parseQuests(json: String): List<CyberpunkQuest> = JSONObject(json).optJSONArray("quests").items {
            CyberpunkQuest(it.text("id"), it.optString("title"), it.text("kind"), it.text("objective"), it.optBoolean("tracked"))
        }

        fun parseInstall(json: String): CyberpunkInstallResult = JSONObject(json).let { CyberpunkInstallResult(it.optBoolean("ok"), it.optBoolean("deferred")) }

        fun parseAction(json: String): CyberpunkActionResult =
            JSONObject(json).let { CyberpunkActionResult(it.optBoolean("ok"), if (it.has("id")) it.optInt("id") else null, it.text("res")) }

        /** `{ pins: [{ id, x, y, z, name?, at }], tracked?: id }` ou directement la liste. */
        fun parsePins(json: String): List<CyberpunkServerPin> {
            val root = if (json.trimStart().startsWith("[")) null else JSONObject(json)
            val array = root?.optJSONArray("pins") ?: if (root == null) JSONArray(json) else null
            val tracked = root?.text("tracked")
            return array.items {
                val id = it.text("id") ?: it.optString("pid")
                CyberpunkServerPin(id, it.double("x"), it.double("y"), it.double("z"), it.text("name"), it.optBoolean("tracked") || id == tracked)
            }
        }

        fun parseMap(json: String): CyberpunkMap {
            val o = JSONObject(json)
            val status = when (o.optString("status")) {
                "ready" -> CyberpunkMapStatus.READY
                "loading" -> CyberpunkMapStatus.LOADING
                "error" -> CyberpunkMapStatus.ERROR
                else -> CyberpunkMapStatus.ABSENT
            }
            val size = o.optJSONArray("size")
            val affine = o.optJSONArray("affine")?.takeIf { it.length() == 6 }?.let { a -> (0 until 6).map { a.optDouble(it) } }
            return CyberpunkMap(status, o.optInt("done"), o.optInt("total"), o.optInt("tile", 512), o.optInt("levels"), size?.optInt(0) ?: 0, size?.optInt(1) ?: 0, affine)
        }
    }
}
