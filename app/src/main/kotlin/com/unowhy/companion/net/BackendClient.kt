package com.unowhy.companion.net

import android.content.Context
import com.unowhy.companion.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class ChatMessage(val role: String, val content: String)

/** Ce que l'ordinateur dit de lui (GET /health, sans jeton) ; `auth` : le jeton envoyé est accepté. */
/** `accessibility` : Mac seulement, false = fenêtres et raccourcis bloqués (autorisation à donner sur le Mac). */
data class Identity(val id: String?, val name: String, val os: String, val auth: Boolean, val mode: String = "mock", val accessibility: Boolean? = null)

/** Résultat de l'appairage par code : jeton à garder et identité de l'ordinateur. */
data class Pairing(val token: String, val id: String, val name: String, val os: String)

/** `app` : nom de l'appli du Mac (tuile d'appli). */
/** `whenState` : touche d'une page d'appli d'appel, montrée seulement en appel (`call`) ou hors appel (`idle`). */
data class DeckTile(val id: String, val label: String, val type: String, val glyph: String?, val color: String?, val app: String? = null, val whenState: String? = null)
/** Appel en cours dans une appli (Discord) : salon, participants vus, micro et sourdine (null : inconnu). */
data class VoiceState(val connected: Boolean, val channel: String?, val people: List<String>, val muted: Boolean?, val deafened: Boolean?)
/**
 * `editable` : page Perso (touches à programmer) ; `apps` : page d'applis que l'on complète ;
 * `meeting` : page Réunion, ouverte après « Rejoindre » sur l'Accueil ; `app` : page d'une appli (Discord…),
 * ouverte en touchant l'appli (pas d'onglet), avec son volume propre au-dessus des touches ;
 * `media` : lecture en cours (vidéo, musique) au-dessus des touches.
 */
data class DeckPage(
    val title: String,
    val tiles: List<DeckTile>,
    val editable: Boolean = false,
    val apps: Boolean = false,
    val meeting: Boolean = false,
    val app: String? = null,
    val media: Boolean = false,
)

/** Agenda relié à l'ordinateur (compte ou lien ICS, jamais le lien lui-même). */
data class LinkedCalendar(val id: String, val label: String, val detail: String, val removable: Boolean, val expired: Boolean)

data class TileResult(val windows: WindowsInfo?, val openedApp: String?)

data class LiveApps(val open: List<String>, val live: List<String>)

/** Cartes de l'Accueil selon les applis du deck : agenda (Outlook, Calendrier…), bouton Rejoindre (Teams). */
/** `linked` : l'ordinateur a un agenda relié (lien ICS, compte) en plus de l'agenda personnel. */
/** `meetings` : Rejoindre (tout lien de visio) ; `teams` : Teams dans le deck, sa page Réunion après Rejoindre. */
data class HomeFeatures(val agenda: Boolean = true, val meetings: Boolean = true, val linked: Boolean = true, val teams: Boolean = false)

/** Choix proposés par l'éditeur de touche perso. */
data class DeckCatalog(val apps: List<String>, val shortcuts: List<String>, val displays: List<String>)
/** Site de la page Média ; `id` : identifiant de sa tuile (null pour un site seulement proposé). */
data class DeckLink(val label: String, val url: String, val id: String? = null)
data class DeckLinks(val links: List<DeckLink>, val suggestions: List<DeckLink>)
/** Mise à jour proposée par le « serveur Unowhy » (simulé par l'ordinateur) : `sha256` vérifiée après téléchargement. */
data class AppUpdate(
    val pkg: String, val label: String, val versionCode: Long, val versionName: String,
    val file: String, val size: Long, val sha256: String,
)
/** Page ouverte dans le navigateur de l'ordinateur (le dernier utilisé). */
data class WebPage(val url: String, val title: String)

/** x, y, w, h : place dans l'arrangement des écrans du Mac (points). */
data class MacDisplay(val id: Int, val label: String, val main: Boolean, val x: Int, val y: Int, val w: Int, val h: Int)
/**
 * `elsewhere` : sur un autre bureau du Mac, sans écran connu (`index` 0, retrouvée par son titre).
 * `front`, `maximized` (vue « Ouvertes ») : au premier plan de l'ordinateur, agrandie.
 */
data class MacWindow(
    val index: Int, val title: String, val minimized: Boolean, val fullscreen: Boolean, val display: Int?,
    val elsewhere: Boolean = false, val front: Boolean = false, val maximized: Boolean = false,
)
data class WindowsInfo(val running: Boolean, val windows: List<MacWindow>, val displays: List<MacDisplay>)

/** Vue « Ouvertes » : une appli de l'ordinateur et ses fenêtres ; `key` la désigne pour la piloter. */
data class OpenApp(val key: String, val name: String, val windows: List<MacWindow>)
data class OpenWindows(val apps: List<OpenApp>, val displays: List<MacDisplay>)

data class AudioState(val output: Int?, val muted: Boolean, val input: Int, val micMuted: Boolean)

/**
 * Ce qui joue sur l'ordinateur (YouTube ou Twitch dans le navigateur, Spotify…). `app` : identifiant de l'appli
 * pour la piloter, `name` : son nom lisible ; `thumb` : clé de la vignette, change avec le morceau.
 */
data class MediaSession(
    val app: String, val name: String, val current: Boolean, val title: String, val artist: String, val playing: Boolean,
    val canPlayPause: Boolean, val canNext: Boolean, val canPrevious: Boolean, val canSeek: Boolean,
    val positionMs: Long?, val durationMs: Long?, val thumb: String?,
)

/** Son d'une appli de l'ordinateur (mélangeur de volume de Windows) ; `level` : niveau sonore instantané, 0-100. */
data class AppAudio(val running: Boolean, val volume: Int?, val muted: Boolean, val level: Int)

data class DocEntry(val name: String, val path: String, val kind: String, val size: Long, val mtimeMs: Long)
data class DocListing(val dir: String, val entries: List<DocEntry>)

data class RemoteCommand(val id: String, val command: JSONObject)

data class TaskItem(
    val id: String, val label: String, val daily: Boolean, val done: Boolean,
    /** Cochée par Jira : pas décochable à la main. */
    val auto: Boolean, val detail: String?, val hasUrl: Boolean,
    /** « Lun–ven · 17:30 », « Rappel à 15:00 » ; `deletable` : pas une tâche du fichier de l'ordinateur. */
    val schedule: String? = null, val deletable: Boolean = !daily,
)

/**
 * `joinable` : réunion Teams, l'ordinateur sait l'ouvrir (le lien reste sur l'ordinateur).
 * `localId` : événement de l'agenda personnel (modifiable depuis la tablette), `remindBefore` : son rappel en minutes.
 */
data class CalendarEvent(
    val id: String, val title: String, val location: String, val startMs: Long, val endMs: Long, val allDay: Boolean,
    val joinable: Boolean, val localId: String? = null, val remindBefore: Int? = null,
)

data class CalendarDays(val source: String, val events: List<CalendarEvent>, val error: String?)

/**
 * Client HTTP du backend (agent sur le Mac). HttpURLConnection + org.json, inclus dans Android,
 * pour garder l'APK sans dépendance réseau.
 */
class BackendClient(private val baseUrl: String, private val token: String = "", private val connectTimeoutMs: Int = 5_000) {

    constructor(context: Context) : this(Prefs.backendUrl(context), Prefs.token(context))

    /** Identité de l'ordinateur ; un ancien backend ne renvoie pas d'`id` (et refuse sans jeton : 401). */
    suspend fun identity(): Identity = io {
        val j = JSONObject(text(request("GET", "/health", timeoutMs = 5_000)))
        Identity(
            j.optStringOrNull("id"), j.optString("name", "Ordinateur"), j.optString("os", "mac"), j.optBoolean("auth", true), j.optString("mode", "mock"),
            if (j.has("accessibility") && !j.isNull("accessibility")) j.getBoolean("accessibility") else null,
        )
    }

    // --- Appairage : l'ordinateur affiche un code, la tablette le renvoie et reçoit le jeton.

    /** Renvoie l'identifiant de la demande ; le code s'affiche sur l'ordinateur. */
    suspend fun requestPairing(tablet: String): String = io {
        JSONObject(postJson("/pair/request", JSONObject().put("tablet", tablet))).getString("request")
    }

    suspend fun confirmPairing(request: String, code: String): Pairing = io {
        val j = JSONObject(postJson("/pair/confirm", JSONObject().put("request", request).put("code", code)))
        Pairing(j.getString("token"), j.getString("id"), j.getString("name"), j.optString("os"))
    }

    // --- Assistant

    suspend fun chat(history: List<ChatMessage>): String = io {
        val messages = JSONArray()
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("messages", messages)
        JSONObject(postJson("/chat", body)).getString("reply")
    }

    suspend fun transcribe(audio: File, mimeType: String): String = io {
        JSONObject(postFile("/transcribe", "audio", audio, mimeType)).getString("text")
    }

    // --- Pilotage du Mac

    suspend fun deck(): List<DeckPage> = io {
        val pages = JSONObject(text(request("GET", "/deck"))).getJSONArray("pages")
        (0 until pages.length()).map { i ->
            val p = pages.getJSONObject(i)
            val tiles = p.getJSONArray("tiles")
            DeckPage(p.getString("title"), (0 until tiles.length()).map { j ->
                val t = tiles.getJSONObject(j)
                DeckTile(t.getString("id"), t.getString("label"), t.getString("type"), t.optStringOrNull("glyph"), t.optStringOrNull("color"), t.optStringOrNull("app"), t.optStringOrNull("when"))
            }, p.optBoolean("editable"), p.optBoolean("apps"), p.optBoolean("meeting"), p.optStringOrNull("app"), p.optBoolean("media"))
        }
    }

    // Page Apps : profil de base complété par les applis du Mac ajoutées depuis la tablette.

    /** Renvoie l'identifiant de la nouvelle tuile. */
    suspend fun addDeckApp(name: String): String = io { JSONObject(postJson("/deck/apps", JSONObject().put("name", name))).getString("id") }

    suspend fun removeDeckApp(tileId: String) = io { text(request("DELETE", "/deck/apps/" + encode(tileId))); Unit }

    suspend fun homeFeatures(): HomeFeatures = io {
        JSONObject(text(request("GET", "/deck/features"))).let {
            // Ordinateur pas encore à jour (sans `teams`) : `meetings` y voulait dire « Teams dans le deck ».
            HomeFeatures(it.optBoolean("agenda", true), it.optBoolean("meetings", true), it.optBoolean("linked", true), it.optBoolean("teams", it.optBoolean("meetings", true)))
        }
    }

    /** Applis des pages d'appli (Discord, Teams) en appel en ce moment : elles ont un onglet tant que dure l'appel. */
    /** Applis à page (Discord, Teams) ouvertes sur l'ordinateur, et celles en appel (micro utilisé). */
    suspend fun liveApps(): LiveApps = io {
        val json = JSONObject(text(request("GET", "/deck/live", timeoutMs = 10_000)))
        fun names(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val live = names("apps")
        // Ordinateur pas encore à jour (sans `open`) : seulement les applis en appel.
        LiveApps(open = if (json.has("open")) names("open") else live, live = live)
    }

    /** État de l'appel dans l'appli ; null si l'ordinateur ne sait pas le lire (Mac, appli fermée, ancienne version). */
    suspend fun voice(app: String): VoiceState? = io {
        val v = JSONObject(text(request("GET", "/deck/voice?app=" + encode(app), timeoutMs = 10_000))).optJSONObject("voice")
        if (v == null || !v.optBoolean("found")) return@io null
        fun flag(key: String) = if (v.isNull(key)) null else v.optBoolean(key)
        val people = v.optJSONArray("people")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        VoiceState(v.optBoolean("connected"), v.optStringOrNull("channel"), people, flag("muted"), flag("deafened"))
    }

    /** Retour au profil de base. */
    suspend fun resetDeckApps() = io { text(request("DELETE", "/deck/apps")); Unit }

    // Page Média : sites ajoutés (vide au départ) et sites proposés.

    suspend fun deckLinks(): DeckLinks = io {
        val j = JSONObject(text(request("GET", "/deck/links")))
        fun list(key: String) = j.getJSONArray(key).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { l -> DeckLink(l.getString("label"), l.getString("url"), l.optStringOrNull("id")) } }
        }
        DeckLinks(list("links"), list("suggestions"))
    }

    /** `url` peut être tapée sans https:// ; nom vide : tiré de l'adresse par l'ordinateur. */
    suspend fun addDeckLink(url: String, label: String?): String = io {
        val body = JSONObject().put("url", url)
        if (!label.isNullOrBlank()) body.put("label", label)
        JSONObject(postJson("/deck/links", body)).getString("id")
    }

    suspend fun removeDeckLink(tileId: String) = io { text(request("DELETE", "/deck/links/" + encode(tileId))); Unit }

    // Touches perso : `slot` de 0 à 13, tuile "k-<slot>". Une touche = {label, glyph?, color?, steps:[…]}.

    suspend fun deckCatalog(): DeckCatalog = io {
        val j = JSONObject(text(request("GET", "/deck/catalog")))
        fun list(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        DeckCatalog(list("apps"), list("shortcuts"), list("displays"))
    }

    suspend fun persoKey(slot: Int): JSONObject? = io { JSONObject(text(request("GET", "/deck/keys/$slot"))).optJSONObject("key") }

    suspend fun savePersoKey(slot: Int, key: JSONObject) = io {
        text(request("PUT", "/deck/keys/$slot", key.toString().toByteArray(), "application/json; charset=utf-8")); Unit
    }

    suspend fun clearPersoKey(slot: Int) = io { text(request("DELETE", "/deck/keys/$slot")); Unit }

    /** Lance l'action d'une tuile ; renvoie les fenêtres si l'appli en a plusieurs (rien n'est alors activé). */
    /** `windows` : appli à plusieurs fenêtres, à choisir ; `openedApp` : lien de la page Média ouvert dans son appli. */
    suspend fun runTile(id: String): TileResult = io {
        val json = JSONObject(postJson("/deck/run", JSONObject().put("id", id)))
        TileResult(json.optJSONObject("windows")?.let(::parseWindows), json.optStringOrNull("app")?.takeIf { json.optString("opened") == "app" })
    }

    suspend fun windows(tileId: String): WindowsInfo = io { parseWindows(JSONObject(text(request("GET", "/deck/windows/" + encode(tileId))))) }

    /**
     * `place` : déplace vers `display` (id d'écran) et/ou `layout` (keep, left, right, max) ; sinon met au premier plan.
     * Sans `window` : toutes les fenêtres de l'appli.
     */
    suspend fun windowAction(tileId: String, window: MacWindow?, place: Boolean, display: Int? = null, layout: String? = null): WindowsInfo = io {
        val body = JSONObject().put("action", if (place) "place" else "focus")
        window?.let { body.put("window", JSONObject().put("index", it.index).put("title", it.title)) }
        display?.let { body.put("display", it) }
        layout?.let { body.put("layout", it) }
        parseWindows(JSONObject(postJson("/deck/windows/" + encode(tileId), body)))
    }

    // Vue « Ouvertes » : toutes les applis ouvertes sur l'ordinateur et l'écran de leurs fenêtres.

    suspend fun openWindows(): OpenWindows = io { parseOpenWindows(JSONObject(text(request("GET", "/windows/open", timeoutMs = 20_000)))) }

    /**
     * `action` : focus (mettre devant), place (sur l'écran `display`, disposition `layout`), minimize (réduire) ou
     * zoom (agrandir, ou rendre sa taille) ; renvoie la liste à jour.
     */
    suspend fun openWindowAction(app: OpenApp, window: MacWindow, action: String, display: Int? = null, layout: String? = null): OpenWindows = io {
        val body = JSONObject()
            .put("key", app.key)
            .put("action", action)
            .put("window", JSONObject().put("index", window.index).put("title", window.title))
        display?.let { body.put("display", it) }
        layout?.let { body.put("layout", it) }
        parseOpenWindows(JSONObject(postJson("/windows/open", body)))
    }

    /** Icône PNG d'une appli de l'ordinateur d'après son nom, ou null si elle n'en a pas. */
    suspend fun appIcon(name: String): ByteArray? = io {
        try { bytes(request("GET", "/windows/icon?name=" + encode(name))) } catch (e: HttpException) { if (e.code == 404) null else throw e }
    }

    /** Icône PNG d'une tuile, ou null si l'appli n'en a pas. */
    suspend fun tileIcon(id: String): ByteArray? = io {
        try { bytes(request("GET", "/deck/icon/" + encode(id))) } catch (e: HttpException) { if (e.code == 404) null else throw e }
    }

    suspend fun audio(): AudioState = io { parseAudio(text(request("GET", "/system/audio"))) }

    suspend fun setAudio(output: Int? = null, outputDelta: Int? = null, muted: Boolean? = null, micMuted: Boolean? = null): AudioState = io {
        val body = JSONObject()
        output?.let { body.put("output", it) }
        outputDelta?.let { body.put("outputDelta", it) }
        muted?.let { body.put("muted", it) }
        micMuted?.let { body.put("micMuted", it) }
        parseAudio(postJson("/system/audio", body))
    }

    /** Son propre à une appli ; HTTP 501 si l'ordinateur ne sait pas le piloter (Mac). */
    suspend fun appAudio(name: String): AppAudio = io {
        parseAppAudio(text(request("GET", "/system/audio/app?name=" + encode(name), timeoutMs = 5_000)))
    }

    suspend fun setAppAudio(name: String, volume: Int? = null, muted: Boolean? = null): AppAudio = io {
        val body = JSONObject().put("name", name)
        volume?.let { body.put("volume", it) }
        muted?.let { body.put("muted", it) }
        parseAppAudio(postJson("/system/audio/app", body))
    }

    // Lecture en cours (Windows ; HTTP 501 sur Mac).

    suspend fun media(): List<MediaSession> = io {
        val sessions = JSONObject(text(request("GET", "/system/media", timeoutMs = 10_000))).getJSONArray("sessions")
        (0 until sessions.length()).map { i ->
            val s = sessions.getJSONObject(i)
            MediaSession(
                s.getString("app"), s.getString("name"), s.optBoolean("current"), s.optString("title"), s.optString("artist"),
                s.optString("status") == "playing", s.optBoolean("canPlayPause"), s.optBoolean("canNext"),
                s.optBoolean("canPrevious"), s.optBoolean("canSeek"),
                if (s.isNull("positionMs")) null else s.getLong("positionMs"),
                if (s.isNull("durationMs")) null else s.getLong("durationMs"),
                s.optStringOrNull("thumb"),
            )
        }
    }

    /** `action` : play-pause, next, previous ou seek (avec `positionMs`). */
    suspend fun mediaControl(action: String, app: String?, positionMs: Long? = null) = io {
        val body = JSONObject().put("action", action)
        app?.let { body.put("app", it) }
        positionMs?.let { body.put("positionMs", it) }
        postJson("/system/media", body)
        Unit
    }

    suspend fun mediaThumb(app: String, key: String): ByteArray? = io {
        try { bytes(request("GET", "/system/media/thumb?app=${encode(app)}&key=${encode(key)}", timeoutMs = 10_000)) }
        catch (e: HttpException) { if (e.code == 404) null else throw e }
    }

    // --- Documents

    suspend fun docs(dir: String): DocListing = io {
        val json = JSONObject(text(request("GET", "/docs?dir=" + encode(dir))))
        val entries = json.getJSONArray("entries")
        DocListing(json.getString("dir"), (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            DocEntry(e.getString("name"), e.getString("path"), e.getString("kind"), e.getLong("size"), e.getLong("mtimeMs"))
        })
    }

    suspend fun downloadDoc(path: String, dest: File) = io {
        val conn = request("GET", "/docs/file?path=" + encode(path))
        try { conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } } } finally { conn.disconnect() }
    }

    suspend fun openOnMac(path: String) = io { postJson("/docs/open", JSONObject().put("path", path)); Unit }

    /** Page ouverte dans le navigateur de l'ordinateur ; erreur 404 (message lisible) s'il n'y en a pas. */
    suspend fun browserPage(): WebPage = io {
        JSONObject(text(request("GET", "/browser/page"))).let { WebPage(it.getString("url"), it.getString("title")) }
    }

    suspend fun openUrlOnComputer(url: String) = io { postJson("/browser/open", JSONObject().put("url", url)); Unit }

    suspend fun updates(): List<AppUpdate> = io {
        val a = JSONObject(text(request("GET", "/updates"))).getJSONArray("updates")
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                AppUpdate(it.getString("package"), it.getString("label"), it.getLong("versionCode"), it.getString("versionName"),
                    it.getString("file"), it.getLong("size"), it.getString("sha256"))
            }
        }
    }

    /** Télécharge une mise à jour dans `dest` ; `progress` (0 à 100, appelé hors du fil principal). Renvoie l'empreinte SHA-256 reçue. */
    suspend fun downloadUpdate(update: AppUpdate, dest: File, progress: (Int) -> Unit): String = io {
        val conn = request("GET", "/updates/file/" + encode(update.file), timeoutMs = 120_000)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (update.size > 0) (done * 100 / update.size).toInt() else 0
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Envoie une photo dans le dossier partagé du Mac ; renvoie son chemin relatif. */
    suspend fun uploadPhoto(photo: File): String = io {
        JSONObject(postFile("/docs/upload", "photo", photo, "image/jpeg")).getString("path")
    }

    // --- Visualiseur (images de la caméra rediffusées par le Mac sur /camera)

    /** Une image JPEG du flux ; appel bloquant, fait depuis le fil d'envoi du visualiseur. */
    fun sendFrame(jpeg: ByteArray) {
        text(request("POST", "/camera/frame", jpeg, "image/jpeg", timeoutMs = 5_000))
    }

    /** `status` : live, frozen ou off ; `rotation` : degrés pour remettre l'image à l'endroit. */
    suspend fun cameraState(status: String, rotation: Int) = io {
        postJson("/camera/state", JSONObject().put("status", status).put("rotation", rotation)); Unit
    }

    /** Ouvre la page du visualiseur dans le navigateur du Mac. */
    suspend fun openCameraOnMac() = io { postJson("/camera/open", JSONObject()); Unit }

    /** Mac : ouvre le réglage Accessibilité, le compagnon déjà dans la liste. */
    suspend fun openAccessibility() = io { postJson("/system/accessibility", JSONObject()); Unit }

    /** Ouvre la page du compagnon sur l'ordinateur ; `section` : "agenda" pour y relier un agenda. */
    suspend fun openLocalPage(section: String? = null) = io {
        postJson("/local/open", JSONObject().apply { if (section != null) put("section", section) }); Unit
    }

    // --- Agenda

    suspend fun linkedCalendars(): List<LinkedCalendar> = io {
        val list = JSONObject(text(request("GET", "/calendar/linked"))).getJSONArray("calendars")
        (0 until list.length()).map { i ->
            val c = list.getJSONObject(i)
            LinkedCalendar(c.getString("id"), c.getString("label"), c.optString("detail"), c.optBoolean("removable"), c.optBoolean("expired"))
        }
    }

    suspend fun unlinkCalendar(id: String) = io { text(request("DELETE", "/calendar/linked/" + encode(id))); Unit }

    /** Événements des `days` prochains jours ; `error` : agenda relié injoignable (l'agenda personnel est là quand même). */
    suspend fun calendar(days: Int = 1): CalendarDays = io {
        val json = JSONObject(text(request("GET", "/calendar?days=$days")))
        val events = json.getJSONArray("events")
        CalendarDays(
            json.getString("source"),
            (0 until events.length()).map { i ->
                val e = events.getJSONObject(i)
                CalendarEvent(
                    e.optString("id"), e.getString("title"), e.optString("location"), e.getLong("startMs"), e.getLong("endMs"),
                    e.getBoolean("allDay"), e.optBoolean("joinable"), e.optStringOrNull("localId"),
                    if (e.isNull("remindBefore") || !e.has("remindBefore")) null else e.getInt("remindBefore"),
                )
            },
            json.optStringOrNull("error"),
        )
    }

    /** Agenda personnel : `id` null = nouvel événement ; `remindBefore` en minutes (0 = à l'heure), null = pas de rappel. */
    suspend fun saveEvent(id: String?, title: String, startMs: Long, allDay: Boolean, remindBefore: Int?) = io {
        val body = JSONObject().put("title", title).put("startMs", startMs).put("allDay", allDay)
            .put("remindBefore", remindBefore ?: JSONObject.NULL)
        if (id == null) postJson("/calendar/events", body)
        else text(request("PUT", "/calendar/events/" + encode(id), body.toString().toByteArray(), "application/json; charset=utf-8"))
        Unit
    }

    suspend fun deleteEvent(id: String) = io { text(request("DELETE", "/calendar/events/" + encode(id))); Unit }

    /** Ouvre la réunion dans Teams sur le Mac. */
    /** Ouvre la réunion sur l'ordinateur ; renvoie le service (teams, meet, zoom, webex) ou null. */
    suspend fun joinMeeting(id: String): String? = io { JSONObject(postJson("/calendar/join", JSONObject().put("id", id))).optStringOrNull("kind") }

    /** Option « Rejoindre automatiquement 1 min avant », gardée sur le Mac (marche tablette éteinte). */
    suspend fun autoJoin(): Boolean = io { JSONObject(text(request("GET", "/calendar/autojoin"))).getBoolean("enabled") }

    suspend fun setAutoJoin(enabled: Boolean) = io {
        val body = JSONObject().put("enabled", enabled).toString().toByteArray()
        text(request("PUT", "/calendar/autojoin", body, "application/json; charset=utf-8")); Unit
    }

    // --- Tâches

    /** `refresh` : force une nouvelle lecture de Jira (sinon gardée 5 min sur le Mac). */
    suspend fun tasks(refresh: Boolean = false): List<TaskItem> = io {
        parseTasks(JSONObject(text(request("GET", "/tasks" + if (refresh) "?refresh=1" else ""))).getJSONArray("tasks"))
    }

    /** Toutes les tâches récurrentes, même celles qui ne tombent pas aujourd'hui. */
    suspend fun recurringTasks(): List<TaskItem> = io {
        parseTasks(JSONObject(text(request("GET", "/tasks/recurring"))).getJSONArray("tasks"))
    }

    private fun parseTasks(tasks: JSONArray) = (0 until tasks.length()).map { i ->
        val t = tasks.getJSONObject(i)
        val daily = t.getString("kind") == "daily"
        TaskItem(
            t.getString("id"), t.getString("label"), daily, t.getBoolean("done"),
            t.getBoolean("auto"), t.optStringOrNull("detail"), t.getBoolean("hasUrl"),
            t.optStringOrNull("schedule"), t.optBoolean("deletable", !daily),
        )
    }

    /**
     * Nouvelle tâche ; `days` (1 = lundi) : récurrente ; `remindAt` ("HH:MM") : rappel parlé.
     * Renvoie sa fréquence lisible (« Lun–ven · 17:30 »), ou null pour une tâche simple.
     */
    suspend fun addTask(label: String, remindAt: String? = null, days: List<Int>? = null): String? = io {
        val body = JSONObject().put("label", label)
        if (remindAt != null) body.put("remindAt", remindAt)
        if (!days.isNullOrEmpty()) body.put("days", JSONArray(days))
        JSONObject(postJson("/tasks", body)).optStringOrNull("schedule")
    }

    suspend fun setTaskDone(id: String, done: Boolean) = io { postJson("/tasks/${encode(id)}/done", JSONObject().put("done", done)); Unit }

    suspend fun deleteTask(id: String) = io { text(request("DELETE", "/tasks/" + encode(id))); Unit }

    suspend fun openTaskOnMac(id: String) = io { postJson("/tasks/${encode(id)}/open", JSONObject()); Unit }

    // --- Canal ordinateur → tablette (voir remote/RemoteChannel.kt)

    /** Attend les ordres de l'ordinateur (le backend garde la requête ouverte jusqu'à 25 s). */
    suspend fun poll(state: JSONObject): List<RemoteCommand> = io {
        val commands = JSONObject(postJson("/tablet/poll", JSONObject().put("state", state))).getJSONArray("commands")
        (0 until commands.length()).map { i ->
            val c = commands.getJSONObject(i)
            RemoteCommand(c.getString("id"), c.getJSONObject("command"))
        }
    }

    suspend fun sendResult(id: String, result: JSONObject, state: JSONObject) = io {
        postJson("/tablet/result", result.put("id", id).put("state", state)); Unit
    }

    // --- Plomberie HTTP

    /** `reason` : message de l'ordinateur, rédigé pour être affiché (voir ui/errorText). */
    class HttpException(val code: Int, val reason: String) : IOException("HTTP $code $reason".trim())

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun parseWindows(j: JSONObject) =
        WindowsInfo(j.getBoolean("running"), parseWindowList(j.getJSONArray("windows")), parseDisplays(j.getJSONArray("displays")))

    private fun parseOpenWindows(j: JSONObject): OpenWindows {
        val apps = j.getJSONArray("apps")
        return OpenWindows(
            (0 until apps.length()).map { i ->
                val a = apps.getJSONObject(i)
                OpenApp(a.getString("key"), a.getString("name"), parseWindowList(a.getJSONArray("windows")))
            },
            parseDisplays(j.getJSONArray("displays")),
        )
    }

    private fun parseWindowList(windows: JSONArray) = (0 until windows.length()).map { i ->
        val w = windows.getJSONObject(i)
        MacWindow(
            w.getInt("index"), w.getString("title"), w.getBoolean("minimized"), w.getBoolean("fullscreen"),
            if (w.isNull("display")) null else w.getInt("display"), w.optBoolean("elsewhere"),
            w.optBoolean("front"), w.optBoolean("maximized"),
        )
    }

    private fun parseDisplays(displays: JSONArray) = (0 until displays.length()).map { i ->
        val d = displays.getJSONObject(i)
        MacDisplay(
            d.getInt("id"), d.getString("label"), d.getBoolean("main"),
            d.optInt("x"), d.optInt("y"), d.optInt("w", 1920), d.optInt("h", 1080),
        )
    }

    private fun parseAudio(raw: String): AudioState {
        val j = JSONObject(raw)
        return AudioState(if (j.isNull("output")) null else j.getInt("output"), j.getBoolean("muted"), j.getInt("input"), j.getBoolean("micMuted"))
    }

    private fun parseAppAudio(raw: String): AppAudio {
        val j = JSONObject(raw)
        return AppAudio(j.getBoolean("running"), if (j.isNull("volume")) null else j.getInt("volume"), j.getBoolean("muted"), j.getInt("level"))
    }

    private fun postJson(path: String, body: JSONObject): String =
        text(request("POST", path, body.toString().toByteArray(), "application/json; charset=utf-8"))

    private fun postFile(path: String, field: String, file: File, mimeType: String): String {
        val boundary = "----companion" + UUID.randomUUID().toString().replace("-", "")
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: $mimeType\r\n\r\n"
        val body = head.toByteArray() + file.readBytes() + "\r\n--$boundary--\r\n".toByteArray()
        return text(request("POST", path, body, "multipart/form-data; boundary=$boundary"))
    }

    private fun text(conn: HttpURLConnection): String =
        try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }

    private fun bytes(conn: HttpURLConnection): ByteArray =
        try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }

    /** Ouvre la connexion et vérifie le code HTTP ; l'appelant lit puis déconnecte. */
    private fun request(method: String, path: String, body: ByteArray? = null, contentType: String? = null, timeoutMs: Int = 60_000): HttpURLConnection {
        // DELETE sans corps : Android y met quand même un type de contenu, que Fastify refuse (415).
        if (method == "DELETE" && body == null) return request(method, path, "{}".toByteArray(), "application/json", timeoutMs)
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = timeoutMs
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            // Fastify : { error: "Internal Server Error", message: <la vraie raison> } ; nos erreurs : { error }.
            val message = runCatching { JSONObject(error).let { it.optString("message").ifEmpty { it.optString("error") } } }.getOrNull()
            throw HttpException(code, message ?: error.take(200))
        }
        return conn
    }
}
