package com.unowhy.unomate.net

import org.json.JSONObject

/** Un des derniers messages non lus de la réception : expéditeur, objet (vides si le compagnon n'en a pas), heure de réception. */
data class MailMessage(val from: String, val subject: String, val receivedMs: Long)

/** Prochain rendez-vous du compte Microsoft. */
data class MailEvent(val title: String, val startMs: Long, val endMs: Long)

/** Réponse de `GET /mail/summary` : la page Outlook du Deck. */
sealed interface MailSummary {
    /** Aucun compte Microsoft relié sur l'ordinateur. */
    data object NoAccount : MailSummary

    /** Compte relié sans l'accès au courrier (relié avant), expiré ou refusé : à reconnecter. */
    data object Reconnect : MailSummary

    /** `unread`, `junk` : non lus de la réception et du courrier indésirable ; `drafts`, `sent` : messages du dossier. */
    data class Ok(
        val unread: Int,
        val junk: Int,
        val drafts: Int,
        val sent: Int,
        val recent: List<MailMessage>,
        val next: MailEvent?,
    ) : MailSummary
}

/** Un état inconnu (compagnon plus récent) est traité comme « à reconnecter » : la carte propose d'ouvrir la page de l'ordinateur. */
fun parseMail(body: String): MailSummary {
    val json = JSONObject(body)
    return when (json.optString("status")) {
        "no-account" -> MailSummary.NoAccount
        "ok" -> {
            val recent = json.optJSONArray("recent")
            val next = json.optJSONObject("next")
            MailSummary.Ok(
                json.optInt("unread"), json.optInt("junk"), json.optInt("drafts"), json.optInt("sent"),
                (0 until (recent?.length() ?: 0)).map { i ->
                    recent!!.getJSONObject(i).let { MailMessage(it.optString("from"), it.optString("subject"), it.optLong("receivedMs")) }
                },
                next?.let { MailEvent(it.optString("title"), it.getLong("startMs"), it.getLong("endMs")) },
            )
        }
        else -> MailSummary.Reconnect
    }
}

/** Courrier du compte Microsoft relié à l'ordinateur (page Outlook du Deck). */
class MailApi(private val http: Http) {
    suspend fun summary(): MailSummary = http.io { parseMail(http.get("/mail/summary", timeoutMs = 20_000)) }
}
