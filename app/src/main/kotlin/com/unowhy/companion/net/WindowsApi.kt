package com.unowhy.companion.net

import org.json.JSONArray
import org.json.JSONObject

/** x, y, w, h : place dans l'arrangement des écrans du Mac (points). */
data class ComputerDisplay(val id: Int, val label: String, val main: Boolean, val x: Int, val y: Int, val w: Int, val h: Int)
/**
 * `elsewhere` : sur un autre bureau du Mac, sans écran connu (`index` 0, retrouvée par son titre).
 * `front`, `maximized` (vue « Ouvertes ») : au premier plan de l'ordinateur, agrandie.
 */
data class ComputerWindow(
    val index: Int, val title: String, val minimized: Boolean, val fullscreen: Boolean, val display: Int?,
    val elsewhere: Boolean = false, val front: Boolean = false, val maximized: Boolean = false,
)
data class WindowsInfo(val running: Boolean, val windows: List<ComputerWindow>, val displays: List<ComputerDisplay>)

/** Vue « Ouvertes » : une appli de l'ordinateur et ses fenêtres ; `key` la désigne pour la piloter. */
data class OpenApp(val key: String, val name: String, val windows: List<ComputerWindow>)
data class OpenWindows(val apps: List<OpenApp>, val displays: List<ComputerDisplay>)

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
     * `action` : focus (mettre devant), place (sur l'écran `display`, disposition `layout`), minimize (réduire) ou
     * zoom (agrandir, ou rendre sa taille) ; renvoie la liste à jour.
     */
    suspend fun openWindowAction(app: OpenApp, window: ComputerWindow, action: String, display: Int? = null, layout: String? = null): OpenWindows = http.io {
        val body = JSONObject()
            .put("key", app.key)
            .put("action", action)
            .put("window", JSONObject().put("index", window.index).put("title", window.title))
        display?.let { body.put("display", it) }
        layout?.let { body.put("layout", it) }
        parseOpenWindows(JSONObject(http.postJson("/windows/open", body)))
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
        w.optBoolean("front"), w.optBoolean("maximized"),
    )
}

private fun parseDisplays(displays: JSONArray) = (0 until displays.length()).map { i ->
    val d = displays.getJSONObject(i)
    ComputerDisplay(
        d.getInt("id"), d.getString("label"), d.getBoolean("main"),
        d.optInt("x"), d.optInt("y"), d.optInt("w", 1920), d.optInt("h", 1080),
    )
}
