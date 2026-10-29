package com.unowhy.companion.net

import org.json.JSONObject

/**
 * Notification affichée sur l'ordinateur (Windows, Mac), relayée par le compagnon (src/notifications.ts).
 * `title` et `body` peuvent être vides (toast sans texte) ; `at` : heure d'arrivée sur l'ordinateur, en ms.
 */
data class PcNotification(val id: String, val app: String, val title: String, val body: String, val at: Long)

/**
 * Une notification lue dans l'historique ou reçue en direct (ordre `notification`) ; null sans identifiant ou sans
 * appli (entrée incomplète, compagnon d'une autre version) : elle n'est pas affichée.
 */
fun parseNotification(j: JSONObject): PcNotification? {
    val id = j.optStringOrNull("id") ?: return null
    val app = j.optStringOrNull("app") ?: return null
    return PcNotification(id, app, j.optStringOrNull("title").orEmpty(), j.optStringOrNull("body").orEmpty(), j.optLong("at"))
}

/** Réponse de `GET /notifications` : `{ items }`, la plus récente en premier (ordre gardé tel quel). */
fun parseNotifications(json: String): List<PcNotification> {
    val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::parseNotification) }
}

/**
 * Historique des notifications de l'ordinateur, gardé en mémoire par le compagnon (24 h, 100 au plus).
 * Effacer ici ne touche pas au centre de notifications de l'ordinateur. 501 : système non pris en charge ;
 * 503 : lecture impossible (Mac sans accès complet au disque), avec la raison.
 */
class NotificationsApi(private val http: Http) {

    suspend fun list(): List<PcNotification> = http.io { parseNotifications(http.get("/notifications", timeoutMs = 10_000)) }

    /** Retire une notification de l'historique (404 si elle n'y est déjà plus). */
    suspend fun delete(id: String) = http.io { http.delete("/notifications/" + http.encode(id)); Unit }

    suspend fun clear() = http.io { http.delete("/notifications"); Unit }
}
