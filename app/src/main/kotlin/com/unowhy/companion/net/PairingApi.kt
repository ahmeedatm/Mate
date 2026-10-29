package com.unowhy.companion.net

import org.json.JSONObject
import java.io.IOException

/** Compagnon d'avant le HTTPS : il ne connaît pas `/pair/reveal` (401 ou 404), il faut le mettre à jour. */
class OutdatedCompanionException : IOException("Compagnon de l'ordinateur à mettre à jour")

/** Ce que l'ordinateur dit de lui (GET /health, sans jeton) ; `auth` : le jeton envoyé est accepté. */
/** `accessibility` : Mac seulement, false = fenêtres et raccourcis bloqués (autorisation à donner sur le Mac). */
data class Identity(val id: String?, val name: String, val os: String, val auth: Boolean, val accessibility: Boolean? = null)

/** Résultat de l'appairage : jeton à garder, empreinte du certificat à épingler et identité de l'ordinateur. */
data class Pairing(val token: String, val fingerprint: String, val id: String, val name: String, val os: String)

/**
 * Identité de l'ordinateur et appairage (protocole : backend/src/pairing.ts) : la tablette s'engage sur un nombre
 * secret, reçoit celui de l'ordinateur, dévoile le sien, affiche le code commun ([PairingCode]) que l'utilisateur
 * saisit sur l'ordinateur, puis attend le jeton.
 */
class PairingApi(private val http: Http) {

    /** Identité de l'ordinateur ; un ancien backend ne renvoie pas d'`id` (et refuse sans jeton : 401). */
    suspend fun identity(): Identity = http.io {
        val j = JSONObject(http.get("/health", timeoutMs = 5_000))
        Identity(
            j.optStringOrNull("id"), j.optString("name", "Ordinateur"), j.optString("os", "mac"), j.optBoolean("auth", true),
            if (j.has("accessibility") && !j.isNull("accessibility")) j.getBoolean("accessibility") else null,
        )
    }

    /** Demande + engagement ; renvoie l'identifiant de la demande et le nombre de l'ordinateur. */
    suspend fun request(tablet: String, commit: String): Pair<String, String> = http.io {
        val j = JSONObject(http.postJson("/pair/request", JSONObject().put("tablet", tablet).put("commit", commit)))
        j.getString("request") to j.getString("nonce")
    }

    /** Dévoile le nombre de la tablette : l'ordinateur ouvre la page où saisir le code. */
    suspend fun reveal(request: String, nonce: String) = http.io {
        try {
            http.postJson("/pair/reveal", JSONObject().put("request", request).put("nonce", nonce))
        } catch (e: HttpException) {
            throw if (e.code == 401 || e.code == 404) OutdatedCompanionException() else e
        }
        Unit
    }

    /** null tant que le code n'a pas été saisi sur l'ordinateur. */
    suspend fun confirm(request: String): Pairing? = http.io {
        val j = JSONObject(http.postJson("/pair/confirm", JSONObject().put("request", request)))
        if (j.optBoolean("waiting")) {
            null
        } else {
            Pairing(j.getString("token"), j.optString("fingerprint"), j.getString("id"), j.getString("name"), j.optString("os"))
        }
    }
}
