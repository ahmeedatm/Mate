package app.mate.net

import androidx.annotation.StringRes
import app.mate.R
import org.json.JSONObject

enum class PointerButton(val wire: String) { LEFT("left"), RIGHT("right") }

/** Gestes de pavé moderne : le nom part tel quel au compagnon, qui le traduit selon le système (liste fermée). */
enum class PointerGesture(val wire: String, @StringRes val label: Int) {
    ZOOM_IN("zoom-in", R.string.gesture_zoom_in),
    ZOOM_OUT("zoom-out", R.string.gesture_zoom_out),
    BACK("back", R.string.gesture_back),
    FORWARD("forward", R.string.gesture_forward),
    TASK_VIEW("task-view", R.string.gesture_task_view),
    DESKTOP("desktop", R.string.gesture_desktop),
    APP_PREVIOUS("app-previous", R.string.gesture_app_previous),
    APP_NEXT("app-next", R.string.gesture_app_next),
    SEARCH("search", R.string.gesture_search),
    DESKTOP_PREVIOUS("desktop-previous", R.string.gesture_desktop_previous),
    DESKTOP_NEXT("desktop-next", R.string.gesture_desktop_next),
    NOTIFICATIONS("notifications", R.string.gesture_notifications),
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
