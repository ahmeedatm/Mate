package app.mate.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Corps envoyé à l'ordinateur pour enregistrer une touche perso. `cycle` ne compte qu'à partir de deux étapes :
 * une seule étape lancée à chaque appui, c'est déjà un raccourci simple. `hold` : étapes de la seconde action
 * (appui long), absente si elle est vide. `span` : 2 pour une touche large, absent sinon.
 */
fun keyJson(label: String, steps: List<JSONObject>, glyph: String?, color: String?, cycle: Boolean, hold: List<JSONObject> = emptyList(), span: Int = 1): JSONObject {
    // Touche à maintenir : une étape seule, sans cycle ni seconde action (l'ordinateur refuse sinon).
    val push = steps.firstOrNull { it.optBoolean("push") }
    val kept = if (push != null) listOf(push) else steps
    val key = JSONObject().put("label", label).put("steps", JSONArray(kept))
    glyph?.trim()?.takeIf { it.isNotEmpty() }?.let { key.put("glyph", it) }
    color?.let { key.put("color", it) }
    if (push == null && cycle && steps.size > 1) key.put("cycle", true)
    if (span > 1) key.put("span", span)
    if (push == null && hold.isNotEmpty()) key.put("hold", JSONArray(hold))
    return key
}
