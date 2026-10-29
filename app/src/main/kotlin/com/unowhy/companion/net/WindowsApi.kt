package com.unowhy.companion.net

import org.json.JSONArray
import org.json.JSONObject

/** x, y, w, h : place dans l'arrangement des écrans du Mac (points). */
data class ComputerDisplay(val id: Int, val label: String, val main: Boolean, val x: Int, val y: Int, val w: Int, val h: Int)
/**
 * `elsewhere` : sur un autre bureau du Mac, sans écran connu (`index` 0, retrouvée par son titre).
 * `front`, `maximized` (vue « Ouvertes ») : au premier plan de l'ordinateur, agrandie ; `page` : page plein écran
 * du Mac qu'elle occupe (ComputerPage.id).
 */
data class ComputerWindow(
    val index: Int, val title: String, val minimized: Boolean, val fullscreen: Boolean, val display: Int?,
    val elsewhere: Boolean = false, val front: Boolean = false, val maximized: Boolean = false, val page: Int? = null,
)
/**
 * Mac : page du balayage à trois doigts, dans l'ordre du balayage ; `display` null : écrans qui partagent leurs pages ;
 * `app` : appli d'une page plein écran.
 */
data class ComputerPage(val id: Int, val display: Int?, val fullscreen: Boolean, val current: Boolean, val app: String?)
data class WindowsInfo(val running: Boolean, val windows: List<ComputerWindow>, val displays: List<ComputerDisplay>)

/** Vue « Ouvertes » : une appli de l'ordinateur et ses fenêtres ; `key` la désigne pour la piloter. */
data class OpenApp(val key: String, val name: String, val windows: List<ComputerWindow>)
/** `pages` : Mac seulement (vide ailleurs, ou si le Mac ne les lit pas). */
data class OpenWindows(val apps: List<OpenApp>, val displays: List<ComputerDisplay>, val pages: List<ComputerPage> = emptyList())

/** Fenêtres de l'ordinateur : celles d'une tuile du deck, et la vue « Ouvertes » (toutes les applis ouvertes). */
class WindowsApi(private val http: Http) {

    suspend fun ofTile(tileId: String): WindowsInfo = http.io { parseWindows(JSONObject(http.get("/deck/windows/" + http.encode(tileId)))) }

    /**
     * `place` : déplace vers `display` (id d'écran) et/ou `layout` (keep, left, right, max) ; sinon met au premier plan.
     * Sans `window` : toutes les fenêtres de l'appli.
     */
    suspend fun windowAction(tileId: String, window: ComputerWindow?, place: Boolean, display: Int? = null, layout: String? = null): WindowsInfo = http.io {
        val body = JSONObject().put("action", if (place) "place" else "focus")
        window?.let { body.put("window", JSONObject().put("index", it.index).put("title", it.title)) }
        display?.let { body.put("display", it) }
        layout?.let { body.put("layout", it) }
        parseWindows(JSONObject(http.postJson("/deck/windows/" + http.encode(tileId), body)))
    }

    suspend fun openWindows(): OpenWindows = http.io { parseOpenWindows(JSONObject(http.get("/windows/open", timeoutMs = 20_000))) }

    /**
     * `action` : focus (mettre devant), place (sur l'écran `display`, disposition `layout`), minimize (réduire),
     * zoom (agrandir, ou rendre sa taille), fullscreen (Mac) ou close. L'ordinateur répond dès l'action faite (null),
     * sans relire la liste ; un compagnon plus ancien rend quand même la liste à jour.
     */
    suspend fun openWindowAction(app: OpenApp, window: ComputerWindow, action: String, display: Int? = null, layout: String? = null): OpenWindows? = http.io {
        val body = JSONObject()
            .put("key", app.key)
            .put("action", action)
            .put("window", JSONObject().put("index", window.index).put("title", window.title))
            .put("list", false)
        display?.let { body.put("display", it) }
        layout?.let { body.put("layout", it) }
        JSONObject(http.postJson("/windows/open", body)).takeIf { it.has("apps") }?.let(::parseOpenWindows)
    }

    /** Mac : passe sur une page du balayage à trois doigts (comme Ctrl + flèche). */
    suspend fun showPage(page: ComputerPage): OpenWindows? = http.io {
        http.postJson("/windows/page", JSONObject().put("page", page.id))
        null
    }

    /** Mac : déplace une page à la place `to` de la bande (le Mac la glisse dans Mission Control, ~3 s). */
    suspend fun movePage(page: ComputerPage, to: Int): OpenWindows? = http.io {
        http.postJson("/windows/page/move", JSONObject().put("page", page.id).put("to", to))
        null
    }

    /** Icône PNG d'une appli de l'ordinateur d'après son nom, ou null si elle n'en a pas. */
    suspend fun appIcon(name: String): ByteArray? = http.io { http.bytesOrNull("/windows/icon?name=" + http.encode(name)) }

    private fun parseOpenWindows(j: JSONObject): OpenWindows {
        val apps = j.getJSONArray("apps")
        return OpenWindows(
            (0 until apps.length()).map { i ->
                val a = apps.getJSONObject(i)
                OpenApp(a.getString("key"), a.getString("name"), parseWindowList(a.getJSONArray("windows")))
            },
            parseDisplays(j.getJSONArray("displays")),
            j.optJSONArray("pages")?.let { pages ->
                (0 until pages.length()).map { i ->
                    val p = pages.getJSONObject(i)
                    ComputerPage(
                        p.getInt("id"), if (p.isNull("display")) null else p.getInt("display"), p.getBoolean("fullscreen"),
                        p.getBoolean("current"), p.optString("app").ifEmpty { null },
                    )
                }
            }.orEmpty(),
        )
    }
}

/** Partagé avec DeckApi.runTile (appli à plusieurs fenêtres). */
internal fun parseWindows(j: JSONObject) =
    WindowsInfo(j.getBoolean("running"), parseWindowList(j.getJSONArray("windows")), parseDisplays(j.getJSONArray("displays")))

private fun parseWindowList(windows: JSONArray) = (0 until windows.length()).map { i ->
    val w = windows.getJSONObject(i)
    ComputerWindow(
        w.getInt("index"), w.getString("title"), w.getBoolean("minimized"), w.getBoolean("fullscreen"),
        if (w.isNull("display")) null else w.getInt("display"), w.optBoolean("elsewhere"),
        w.optBoolean("front"), w.optBoolean("maximized"), if (w.has("page")) w.getInt("page") else null,
    )
}

private fun parseDisplays(displays: JSONArray) = (0 until displays.length()).map { i ->
    val d = displays.getJSONObject(i)
    ComputerDisplay(
        d.getInt("id"), d.getString("label"), d.getBoolean("main"),
        d.optInt("x"), d.optInt("y"), d.optInt("w", 1920), d.optInt("h", 1080),
    )
}
