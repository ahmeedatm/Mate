package app.mate.net

import org.json.JSONObject

/** Pas d'adresse e-mail : la tuile ne l'affiche pas, elle reste sur l'ordinateur. `unread` : null quand le compte ne répond pas (alors `error` dit pourquoi, en une phrase). */
data class MailAccount(val provider: String, val label: String, val unread: Int?, val error: String?)

data class Mail(val accounts: List<MailAccount>) {
    val total get() = accounts.sumOf { it.unread ?: 0 }

    /** Au moins un compte a donné son nombre : sinon la tuile montre un tiret, pas 0. */
    val hasCount get() = accounts.any { it.unread != null }

    /** Compte dont la boîte s'ouvre au toucher : le premier qui a du courrier à lire, sinon le premier. */
    val target get() = accounts.firstOrNull { (it.unread ?: 0) > 0 } ?: accounts.firstOrNull()
}

/** Nombre de mails non lus de la boîte de réception des comptes connectés sur l'ordinateur (src/routes/mail.ts). */
class MailApi(private val http: Http) {

    suspend fun unread(): Mail = http.io { parse(http.get("/mail/unread")) }

    /** Ouvre sur l'ordinateur la boîte du fournisseur : la tablette n'envoie jamais d'adresse. */
    suspend fun open(provider: String) = http.io { http.postJson("/mail/open", JSONObject().put("provider", provider)); Unit }

    companion object {
        fun parse(json: String): Mail {
            val rows = JSONObject(json).optJSONArray("accounts")
            return Mail(
                (0 until (rows?.length() ?: 0)).map {
                    val row = rows!!.getJSONObject(it)
                    MailAccount(
                        row.getString("provider"),
                        row.optString("label"),
                        if (row.isNull("unread")) null else row.optInt("unread"),
                        row.optString("error").takeIf { e -> e.isNotEmpty() },
                    )
                },
            )
        }
    }
}
