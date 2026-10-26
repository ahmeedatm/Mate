package com.unowhy.companion.net

import org.json.JSONObject

/** Ce que l'ordinateur dit de lui (GET /health, sans jeton) ; `auth` : le jeton envoyé est accepté. */
/** `accessibility` : Mac seulement, false = fenêtres et raccourcis bloqués (autorisation à donner sur le Mac). */
data class Identity(val id: String?, val name: String, val os: String, val auth: Boolean, val accessibility: Boolean? = null)

/** Résultat de l'appairage par code : jeton à garder et identité de l'ordinateur. */
data class Pairing(val token: String, val id: String, val name: String, val os: String)

/** Identité de l'ordinateur et appairage : l'ordinateur affiche un code, la tablette le renvoie et reçoit le jeton. */
class PairingApi(private val http: Http) {

    /** Identité de l'ordinateur ; un ancien backend ne renvoie pas d'`id` (et refuse sans jeton : 401). */
    suspend fun identity(): Identity = http.io {
        val j = JSONObject(http.get("/health", timeoutMs = 5_000))
        Identity(
            j.optStringOrNull("id"), j.optString("name", "Ordinateur"), j.optString("os", "mac"), j.optBoolean("auth", true),
            if (j.has("accessibility") && !j.isNull("accessibility")) j.getBoolean("accessibility") else null,
        )
    }

    /** Renvoie l'identifiant de la demande ; le code s'affiche sur l'ordinateur. */
    suspend fun requestPairing(tablet: String): String = http.io {
        JSONObject(http.postJson("/pair/request", JSONObject().put("tablet", tablet))).getString("request")
    }

    suspend fun confirmPairing(request: String, code: String): Pairing = http.io {
        val j = JSONObject(http.postJson("/pair/confirm", JSONObject().put("request", request).put("code", code)))
        Pairing(j.getString("token"), j.getString("id"), j.getString("name"), j.optString("os"))
    }
}
