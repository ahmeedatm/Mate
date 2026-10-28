package com.unowhy.companion.net

import org.json.JSONObject

enum class PointerButton(val wire: String) { LEFT("left"), RIGHT("right") }

/** Gestes de pavé moderne : le nom part tel quel au compagnon, qui le traduit selon le système (liste fermée). */
enum class PointerGesture(val wire: String) {
    ZOOM_IN("zoom-in"),
    ZOOM_OUT("zoom-out"),
    BACK("back"),
    FORWARD("forward"),
    TASK_VIEW("task-view"),
    DESKTOP("desktop"),
    APP_PREVIOUS("app-previous"),
    APP_NEXT("app-next"),
    SEARCH("search"),
    DESKTOP_PREVIOUS("desktop-previous"),
    DESKTOP_NEXT("desktop-next"),
    NOTIFICATIONS("notifications"),
}

/** Pavé tactile en HTTP (src/routes/pointer.ts) : les clics, toujours, et le secours des déplacements si l'UDP ne passe pas. */
class PointerApi(private val http: Http) {

    suspend fun move(dx: Int, dy: Int) = http.io { http.postJson("/pointer/move", JSONObject().put("dx", dx).put("dy", dy)); Unit }

    suspend fun scroll(sx: Int, sy: Int) = http.io { http.postJson("/pointer/scroll", JSONObject().put("sx", sx).put("sy", sy)); Unit }

    /** `action` : click, down ou up. */
    suspend fun button(button: PointerButton, action: String) = http.io {
        http.postJson("/pointer/button", JSONObject().put("button", button.wire).put("action", action)); Unit
    }

    suspend fun gesture(gesture: PointerGesture) = http.io {
        http.postJson("/pointer/gesture", JSONObject().put("gesture", gesture.wire)); Unit
    }
}
