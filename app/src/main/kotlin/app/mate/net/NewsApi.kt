package app.mate.net

import org.json.JSONObject

data class NewsItem(val title: String)

/** `feed` : adresse du flux réglé sur l'ordinateur, null s'il n'y en a pas ; `title` : nom du flux s'il en a un. */
data class News(val feed: String?, val title: String?, val items: List<NewsItem>) {
    val titles get() = items.map { it.title }
}

/** Derniers titres d'un flux RSS ou Atom, lu et ouvert par l'ordinateur (src/news.ts). */
class NewsApi(private val http: Http) {

    suspend fun latest(): News = http.io { parse(http.get("/news")) }

    /** Règle le flux (adresse vide : le retire). L'ordinateur refuse une adresse qui n'est pas http(s) ou qui porte un mot de passe. */
    suspend fun setFeed(url: String) = http.io {
        http.postJson("/news/feed", JSONObject().put("url", url.trim().ifEmpty { JSONObject.NULL }))
        Unit
    }

    /** Ouvre sur l'ordinateur l'article n° `index` de la dernière liste reçue : la tablette n'envoie jamais d'adresse. */
    suspend fun open(index: Int) = http.io { http.postJson("/news/open", JSONObject().put("index", index)); Unit }

    companion object {
        /** Cinq titres au plus : c'est ce que l'ordinateur envoie, la tuile n'en montre pas davantage. */
        const val MAX_ITEMS = 5

        fun parse(json: String): News {
            val root = JSONObject(json)
            val items = root.optJSONArray("items")
            return News(
                root.optString("feed").takeIf { it.isNotEmpty() && !root.isNull("feed") },
                root.optString("title").takeIf { it.isNotEmpty() && !root.isNull("title") },
                (0 until (items?.length() ?: 0))
                    .mapNotNull { items?.getJSONObject(it)?.optString("title")?.takeIf(String::isNotEmpty)?.let(::NewsItem) }
                    .take(MAX_ITEMS),
            )
        }
    }
}
