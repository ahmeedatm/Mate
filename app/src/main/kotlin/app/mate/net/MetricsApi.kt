package app.mate.net

import org.json.JSONObject

/** `value` : null quand la commande a échoué ou n'a rien affiché. */
data class Metric(val label: String, val value: String?)

/** Valeurs calculées par les commandes que l'utilisateur a écrites dans metrics.json sur l'ordinateur (src/metrics.ts). */
class MetricsApi(private val http: Http) {

    suspend fun latest(): List<Metric> = http.io { parse(http.get("/metrics")) }

    companion object {
        /** Quatre valeurs au plus : c'est ce que l'ordinateur envoie, la tuile n'en montre pas davantage. */
        const val MAX_METRICS = 4

        fun parse(json: String): List<Metric> {
            val items = JSONObject(json).optJSONArray("items")
            return (0 until (items?.length() ?: 0))
                .mapNotNull { items?.getJSONObject(it) }
                .map { Metric(it.optString("label"), it.optString("value").takeIf { v -> v.isNotEmpty() && !it.isNull("value") }) }
                .take(MAX_METRICS)
        }
    }
}
