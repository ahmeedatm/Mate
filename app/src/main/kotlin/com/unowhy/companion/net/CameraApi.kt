package com.unowhy.companion.net

import org.json.JSONObject

/** Visualiseur : images de la caméra rediffusées par l'ordinateur sur /camera. */
class CameraApi(private val http: Http) {

    /** Une image JPEG du flux ; appel bloquant, fait depuis le fil d'envoi du visualiseur. */
    fun sendFrame(jpeg: ByteArray) {
        http.text(http.request("POST", "/camera/frame", jpeg, "image/jpeg", timeoutMs = 5_000))
    }

    /** `status` : live, frozen ou off ; `rotation` : degrés pour remettre l'image à l'endroit. */
    suspend fun state(status: String, rotation: Int) = http.io {
        http.postJson("/camera/state", JSONObject().put("status", status).put("rotation", rotation)); Unit
    }

    /** Ouvre la page du visualiseur dans le navigateur du Mac. */
    suspend fun openCameraOnMac() = http.io { http.postJson("/camera/open", JSONObject()); Unit }

    /** Nombre de pages du visualiseur ouvertes sur l'ordinateur (flux MJPEG en cours). */
    suspend fun viewers(): Int = http.io {
        JSONObject(http.get("/camera/status", timeoutMs = 5_000)).optInt("viewers")
    }
}
