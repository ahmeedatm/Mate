package com.unowhy.companion.net

import org.json.JSONObject

/** `app` : nom de l'appli du Mac (tuile d'appli). */
/** `whenState` : touche d'une page d'appli d'appel, montrée seulement en appel (`call`), hors appel (`idle`), pendant un partage d'écran (`streaming`), en appel sans partage (`share`) ou sur l'écran d'avant-réunion (`lobby`). */
/** `lit` : touche allumée quand le micro est coupé (`muted`), le casque en sourdine (`deafened`) ou la caméra coupée (`camera`). */
data class DeckTile(
    val id: String,
    val label: String,
    val type: String,
    val glyph: String?,
    val color: String?,
    val app: String? = null,
    val whenState: String? = null,
    val lit: String? = null,
)
/** Appel en cours dans une appli (Discord) : salon, participants vus, micro et sourdine (null : inconnu). */
/** `lobby` : écran d'avant-réunion de Teams (« Rejoindre maintenant »), pas encore dans la réunion. */
data class VoiceState(val connected: Boolean, val channel: String?, val people: List<String>, val muted: Boolean?, val deafened: Boolean?, val streaming: Boolean = false, val lobby: Boolean = false, val cameraOff: Boolean? = null)
/**
 * `editable` : page Perso (touches à programmer) ; `apps` : page d'applis que l'on complète ;
 * `meeting` : page Réunion, ouverte après « Rejoindre » sur l'Accueil ; `app` : page d'une appli (Discord…),
 * ouverte en touchant l'appli (pas d'onglet), avec son volume propre au-dessus des touches ;
 * `media` : lecture en cours (vidéo, musique) au-dessus des touches ; `sound` : barre « Son de l'ordinateur » sur la page
 * (faux pour Word, Excel…) ; `mail` : page Outlook, courrier du compte Microsoft au-dessus des touches.
 */
data class DeckPage(
    val title: String,
    val tiles: List<DeckTile>,
    val editable: Boolean = false,
    val apps: Boolean = false,
    val meeting: Boolean = false,
    val app: String? = null,
    val media: Boolean = false,
    val sound: Boolean = true,
    val mail: Boolean = false,
)

data class TileResult(val windows: WindowsInfo?, val openedApp: String?)

data class LiveApps(val open: List<String>, val live: List<String>)

/** Cartes de l'Accueil selon les applis du deck : agenda (Outlook, Calendrier…), bouton Rejoindre (Teams). */
/** `linked` : l'ordinateur a un agenda relié (lien ICS, compte) en plus de l'agenda personnel. */
/** `meetings` : Rejoindre (tout lien de visio) ; `teams` : Teams dans le deck, sa page Réunion après Rejoindre. */
/** `agents` : agents Claude Code (Mac avec Claude Code installé) : onglet Agents du Deck. */
/** `gitlab` : jeton GitLab réglé sur l'ordinateur : onglet GitLab du Deck. */
data class HomeFeatures(
    val agenda: Boolean = true,
    val meetings: Boolean = true,
    val linked: Boolean = true,
    val teams: Boolean = false,
    val agents: Boolean = false,
    val gitlab: Boolean = false,
    /** Plugins coupés sur cet ordinateur (`open`, `mixer`, `pad`…) : leurs onglets ne sont pas affichés. */
    val off: Set<String> = emptySet(),
)

/** Choix proposés par l'éditeur de touche perso. */
data class DeckCatalog(val apps: List<String>, val shortcuts: List<String>, val displays: List<String>)
/** Site de la page Média ; `id` : identifiant de sa tuile (null pour un site seulement proposé). */
data class DeckLink(val label: String, val url: String, val id: String? = null)
data class DeckLinks(val links: List<DeckLink>, val suggestions: List<DeckLink>)

/** Deck : pages et tuiles, page Apps, page Média, touches perso, état des applis à page (Discord, Teams). */
class DeckApi(private val http: Http) {

    suspend fun pages(): List<DeckPage> = http.io {
        val pages = JSONObject(http.get("/deck")).getJSONArray("pages")
        (0 until pages.length()).map { i ->
            val p = pages.getJSONObject(i)
            val tiles = p.getJSONArray("tiles")
            DeckPage(p.getString("title"), (0 until tiles.length()).map { j ->
                val t = tiles.getJSONObject(j)
                DeckTile(t.getString("id"), t.getString("label"), t.getString("type"), t.optStringOrNull("glyph"), t.optStringOrNull("color"), t.optStringOrNull("app"), t.optStringOrNull("when"), t.optStringOrNull("lit"))
            }, p.optBoolean("editable"), p.optBoolean("apps"), p.optBoolean("meeting"), p.optStringOrNull("app"), p.optBoolean("media"),
                // Ordinateur pas encore à jour (sans `sound`) : barre Son comme avant.
                p.optBoolean("sound", true), p.optBoolean("mail"))
        }
    }

    // Page Apps : profil de base complété par les applis du Mac ajoutées depuis la tablette.

    /** Renvoie l'identifiant de la nouvelle tuile. */
    suspend fun addApp(name: String): String = http.io { JSONObject(http.postJson("/deck/apps", JSONObject().put("name", name))).getString("id") }

    suspend fun removeApp(tileId: String) = http.io { http.delete("/deck/apps/" + http.encode(tileId)); Unit }

    /** Retour au profil de base. */
    suspend fun resetApps() = http.io { http.delete("/deck/apps"); Unit }

    suspend fun homeFeatures(): HomeFeatures = http.io { parseHomeFeatures(http.get("/deck/features")) }

    /** Applis à page (Discord, Teams) ouvertes sur l'ordinateur, et celles en appel (micro utilisé). */
    suspend fun liveApps(): LiveApps = http.io {
        val json = JSONObject(http.get("/deck/live", timeoutMs = 10_000))
        fun names(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val live = names("apps")
        // Ordinateur pas encore à jour (sans `open`) : seulement les applis en appel.
        LiveApps(open = if (json.has("open")) names("open") else live, live = live)
    }

    /** État de l'appel dans l'appli ; null si l'ordinateur ne sait pas le lire (Mac, appli fermée, ancienne version). */
    suspend fun voice(app: String): VoiceState? = http.io {
        val v = JSONObject(http.get("/deck/voice?app=" + http.encode(app), timeoutMs = 10_000)).optJSONObject("voice")
        if (v == null || !v.optBoolean("found")) return@io null
        fun flag(key: String) = if (v.isNull(key)) null else v.optBoolean(key)
        val people = v.optJSONArray("people")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        VoiceState(v.optBoolean("connected"), v.optStringOrNull("channel"), people, flag("muted"), flag("deafened"), v.optBoolean("streaming"), v.optBoolean("lobby"), flag("cameraOff"))
    }

    // Page Média : sites ajoutés (vide au départ) et sites proposés.

    suspend fun links(): DeckLinks = http.io {
        val j = JSONObject(http.get("/deck/links"))
        fun list(key: String) = j.getJSONArray(key).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { l -> DeckLink(l.getString("label"), l.getString("url"), l.optStringOrNull("id")) } }
        }
        DeckLinks(list("links"), list("suggestions"))
    }

    /** `url` peut être tapée sans https:// ; nom vide : tiré de l'adresse par l'ordinateur. */
    suspend fun addLink(url: String, label: String?): String = http.io {
        val body = JSONObject().put("url", url)
        if (!label.isNullOrBlank()) body.put("label", label)
        JSONObject(http.postJson("/deck/links", body)).getString("id")
    }

    suspend fun removeLink(tileId: String) = http.io { http.delete("/deck/links/" + http.encode(tileId)); Unit }

    // Touches perso : `slot` de 0 à 13, tuile "k-<slot>". Une touche = {label, glyph?, color?, steps:[…]}.

    suspend fun catalog(): DeckCatalog = http.io {
        val j = JSONObject(http.get("/deck/catalog"))
        fun list(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        DeckCatalog(list("apps"), list("shortcuts"), list("displays"))
    }

    suspend fun persoKey(slot: Int): JSONObject? = http.io { JSONObject(http.get("/deck/keys/$slot")).optJSONObject("key") }

    suspend fun savePersoKey(slot: Int, key: JSONObject) = http.io { http.putJson("/deck/keys/$slot", key); Unit }

    suspend fun clearPersoKey(slot: Int) = http.io { http.delete("/deck/keys/$slot"); Unit }

    /** Lance l'action d'une tuile ; renvoie les fenêtres si l'appli en a plusieurs (rien n'est alors activé). */
    /** `windows` : appli à plusieurs fenêtres, à choisir ; `openedApp` : lien de la page Média ouvert dans son appli. */
    suspend fun runTile(id: String): TileResult = http.io {
        val json = JSONObject(http.postJson("/deck/run", JSONObject().put("id", id)))
        TileResult(json.optJSONObject("windows")?.let(::parseWindows), json.optStringOrNull("app")?.takeIf { json.optString("opened") == "app" })
    }

    /** Icône PNG d'une tuile, ou null si l'appli n'en a pas. */
    suspend fun tileIcon(id: String): ByteArray? = http.io { http.bytesOrNull("/deck/icon/" + http.encode(id)) }
}

internal fun parseHomeFeatures(text: String): HomeFeatures = JSONObject(text).let {
    // Ordinateur pas encore à jour (sans `teams`) : `meetings` y voulait dire « Teams dans le deck ».
    HomeFeatures(
        it.optBoolean("agenda", true), it.optBoolean("meetings", true), it.optBoolean("linked", true),
        it.optBoolean("teams", it.optBoolean("meetings", true)), it.optBoolean("agents"), it.optBoolean("gitlab"),
        it.optJSONArray("off")?.let { a -> (0 until a.length()).map { i -> a.getString(i) }.toSet() }.orEmpty(),
    )
}
