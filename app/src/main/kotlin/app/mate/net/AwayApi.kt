package app.mate.net

import org.json.JSONObject

/** « Je m'absente / Reprendre » : l'ordinateur garde ses fenêtres ouvertes puis les remet devant (backend/src/away.ts). */
class AwayApi(private val http: Http) {

    /** Garde les fenêtres ouvertes ; renvoie leur nombre. Les lire prend quelques secondes sur Mac. */
    suspend fun start(): Int = http.io { JSONObject(http.postJson("/away/start", JSONObject())).optInt("saved") }

    /** Remet les fenêtres gardées devant ; renvoie le nombre remises (0 : rien n'était gardé). */
    suspend fun resume(): Int = http.io { JSONObject(http.postJson("/away/resume", JSONObject())).optInt("restored") }
}
