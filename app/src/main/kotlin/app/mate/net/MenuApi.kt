package app.mate.net

import org.json.JSONObject

/** Un jour du menu : ce qu'on mange à midi et le soir, chaîne vide quand rien n'est prévu. */
data class MenuDay(val lunch: String, val dinner: String)

/** Menu de la semaine réglé sur la page locale de l'ordinateur (src/menu.ts) ; la tablette ne fait que le lire. */
class MenuApi(private val http: Http) {

    /** Sept jours, du lundi au dimanche. */
    suspend fun week(): List<MenuDay> = http.io { parse(http.get("/menu")) }

    companion object {
        const val DAYS = 7

        /** Toujours [DAYS] jours : ce qui manque dans la réponse devient un jour sans repas. */
        fun parse(json: String): List<MenuDay> {
            val days = JSONObject(json).optJSONArray("days")
            return List(DAYS) { i ->
                val day = days?.optJSONObject(i)
                MenuDay(day?.optString("lunch").orEmpty(), day?.optString("dinner").orEmpty())
            }
        }
    }
}
