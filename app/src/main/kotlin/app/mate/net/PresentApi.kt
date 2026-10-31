package app.mate.net

import org.json.JSONObject

/** Geste du widget Présentation, envoyé à l'appli de diapos ouverte sur l'ordinateur (`key` : fin de la route). */
enum class PresentAction(val key: String) { NEXT("next"), PREVIOUS("previous"), START("start"), END("end") }

/** Appli de diapos de l'ordinateur (PowerPoint, Keynote) : diapo suivante ou précédente, début, fin. */
class PresentApi(private val http: Http) {

    /** Erreur 409 « Ouvre PowerPoint ou Keynote sur l'ordinateur » si aucune appli de diapos ne tourne. */
    suspend fun send(action: PresentAction) = http.io { http.postJson("/present/" + action.key, JSONObject()); Unit }
}
