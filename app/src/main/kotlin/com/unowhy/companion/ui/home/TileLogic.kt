package com.unowhy.companion.ui.home

import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.VoiceState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Touche à montrer selon l'appel : `call` seulement en appel, `idle` hors appel, `streaming` pendant un partage
 * d'écran, `share` en appel sans partage (« Partager », remplacé par « Arrêter le partage »), `lobby` sur l'écran
 * d'avant-réunion de Teams (« Rejoindre maintenant »).
 */
fun tileShown(tile: DeckTile, voice: VoiceState?) = when (tile.whenState) {
    "call" -> voice?.connected == true
    "streaming" -> voice?.streaming == true
    "share" -> voice?.connected == true && voice.streaming != true
    "lobby" -> voice?.lobby == true
    "idle" -> voice?.connected != true
    else -> true
}

fun tileLit(tile: DeckTile, voice: VoiceState?) = voice?.connected == true && when (tile.lit) {
    // En sourdine, Discord coupe aussi le micro (il revient avec le casque, comme il était avant).
    "muted" -> voice.muted == true || voice.deafened == true
    "deafened" -> voice.deafened == true
    "camera" -> voice.cameraOff == true
    else -> false
}

/** Touche choisie dans un widget : assez pour la dessiner sans relire le deck. Le JSON garde l'ancien format de KeysWidget. */
data class KeyRef(val id: String, val label: String, val glyph: String? = null, val color: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("glyph", glyph ?: "").put("color", color ?: "")

    companion object {
        fun of(json: JSONObject) = KeyRef(
            json.getString("id"),
            json.optString("label"),
            json.optString("glyph").takeIf { it.isNotEmpty() },
            json.optString("color").takeIf { it.isNotEmpty() },
        )

        fun of(tile: DeckTile) = KeyRef(tile.id, tile.label, tile.glyph, tile.color)

        fun list(config: JSONObject): List<KeyRef> {
            val array = config.optJSONArray("keys") ?: return emptyList()
            return (0 until array.length()).map { of(array.getJSONObject(it)) }
        }

        fun put(config: JSONObject, keys: List<KeyRef>) {
            config.put("keys", JSONArray().apply { keys.forEach { put(it.toJson()) } })
        }
    }
}

/** Numéro d'emplacement d'une touche perso (tuile "k-<slot>"), null pour toute autre tuile. */
fun slotOf(id: String): Int? = if (id.startsWith("k-")) id.removePrefix("k-").toIntOrNull() else null

/** Premier emplacement vide de la page Perso (`editable`), null s'il n'y en a pas. */
fun nextFreeSlot(pages: List<DeckPage>): Int? =
    pages.firstOrNull { it.editable }?.tiles?.firstOrNull { it.type == "empty" }?.let { slotOf(it.id) }

/**
 * Remet les touches choisies à jour d'après le deck : une touche retrouvée prend ses données à jour, une touche perso
 * vidée ou disparue est retirée, une touche d'une page fixe absente (page pas encore lue) est gardée.
 * Rend null quand rien ne change.
 */
fun reconcileKeys(chosen: List<KeyRef>, pages: List<DeckPage>): List<KeyRef>? {
    val tiles = pages.flatMap { it.tiles }.associateBy { it.id }
    val result = chosen.mapNotNull { key ->
        val tile = tiles[key.id]
        when {
            tile != null && tile.type != "empty" -> KeyRef.of(tile)
            slotOf(key.id) != null -> null
            else -> key
        }
    }
    return result.takeIf { it != chosen }
}
