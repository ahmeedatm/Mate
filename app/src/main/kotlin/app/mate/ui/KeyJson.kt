package app.mate.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Corps envoyé à l'ordinateur pour enregistrer une touche perso. `cycle` ne compte qu'à partir de deux étapes :
 * une seule étape lancée à chaque appui, c'est déjà un raccourci simple. `hold` : étapes de la seconde action
 * (appui long), absente si elle est vide.
 */
fun keyJson(label: String, steps: List<JSONObject>, glyph: String?, color: String?, cycle: Boolean, hold: List<JSONObject> = emptyList()): JSONObject {
    val key = JSONObject().put("label", label).put("steps", JSONArray(steps))
    glyph?.trim()?.takeIf { it.isNotEmpty() }?.let { key.put("glyph", it) }
    color?.let { key.put("color", it) }
    if (cycle && steps.size > 1) key.put("cycle", true)
    if (hold.isNotEmpty()) key.put("hold", JSONArray(hold))
    return key
}
