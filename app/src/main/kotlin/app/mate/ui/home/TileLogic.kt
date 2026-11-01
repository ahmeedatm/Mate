package app.mate.ui.home

import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.VoiceState
import org.json.JSONArray
import org.json.JSONObject

/** La touche [id] a une seconde action (appui long) d'après le deck lu sur l'ordinateur. */
fun tileHasHold(pages: List<DeckPage>, id: String) = pages.any { page -> page.tiles.any { it.id == id && it.hold } }

/** Touches à maintenir (parler en maintenant) d'après le deck lu sur l'ordinateur. */
fun tilePushIds(pages: List<DeckPage>): Set<String> = pages.flatMap { it.tiles }.filter { it.push }.map { it.id }.toSet()

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
data class KeyRef(val id: String, val label: String, val glyph: String? = null, val color: String? = null, val symbol: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("glyph", glyph ?: "").put("color", color ?: "")
        .apply { symbol?.let { put("symbol", it) } }

    companion object {
        /** Null pour un élément sans id : une config abîmée ne doit pas faire planter le rendu. */
        fun of(json: JSONObject): KeyRef? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return KeyRef(
                id,
                json.optString("label"),
                json.optString("glyph").takeIf { it.isNotEmpty() },
                json.optString("color").takeIf { it.isNotEmpty() },
                json.optString("symbol").takeIf { it.isNotEmpty() },
            )
        }

        fun of(tile: DeckTile) = KeyRef(tile.id, tile.label, tile.glyph, tile.color, tile.symbol)

        /** Config d'un widget Touches : titre de la page du deck dont il prend ses touches de départ (profil Deck). */
        const val DEFAULT_PAGE = "defaultPage"

        /** Touches de départ au plus : une grille de 3 × 3 dans le widget en L du profil Deck. */
        const val SEED_MAX = 9

        /** Ordre des touches de départ par symbole ; les autres suivent, dans l'ordre de la page. */
        private val SEED_PRIORITY = listOf("lock", "mic_off", "dnd", "screenshot", "clipboard", "windows", "search", "play_pause", "skip_previous", "skip_next")

        /**
         * Touches de départ d'un ordinateur : les [SEED_MAX] premières de la page [DEFAULT_PAGE], une seule fois (dès que
         * l'ordinateur a ses touches dans la config, même vidées, plus rien n'est posé). Null : rien à poser.
         */
        fun seed(config: JSONObject, computerId: String?, pages: List<DeckPage>): List<KeyRef>? {
            if (computerId == null || config.optJSONObject(BY_COMPUTER)?.has(computerId) == true) return null
            val title = config.optString(DEFAULT_PAGE).takeIf { it.isNotEmpty() } ?: return null
            val page = pages.firstOrNull { it.title == title } ?: return null
            // Essentielles bureau d'abord (Verrouiller, micro, Ne pas déranger…), quel que soit l'ordre de la page.
            val rank = { t: DeckTile -> SEED_PRIORITY.indexOf(t.symbol).let { if (it < 0) SEED_PRIORITY.size else it } }
            return page.tiles.filter { it.type != "empty" }.sortedBy(rank).take(SEED_MAX).map(::of).takeIf { it.isNotEmpty() }
        }

        /** Les touches sans celle d'id [id] (retrait du widget : le deck, lui, ne change pas). */
        fun without(keys: List<KeyRef>, id: String): List<KeyRef> = keys.filterNot { it.id == id }

        fun list(config: JSONObject): List<KeyRef> {
            val array = config.optJSONArray("keys") ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::of) }
        }

        fun put(config: JSONObject, keys: List<KeyRef>) {
            config.put("keys", JSONArray().apply { keys.forEach { put(it.toJson()) } })
        }

        /**
         * Touches d'un ordinateur : les ids (`k-N`, pages fixes) lui sont propres alors que la disposition est celle de la
         * tablette, donc la config les range dans `byComputer[<id>]`. Lecture seule : un ancien `keys` sert tant qu'il
         * n'est pas rattaché ([adopt]). Sans ordinateur actif, c'est lui qui est lu.
         */
        fun listFor(config: JSONObject, computerId: String?): List<KeyRef> {
            val own = computerId?.let { config.optJSONObject(BY_COMPUTER)?.optJSONObject(it) }
            return if (own != null) list(own) else list(config)
        }

        /** Écrit les touches de [computerId] sans toucher aux autres. Faux, sans rien écrire, sans ordinateur actif. */
        fun putFor(config: JSONObject, computerId: String?, keys: List<KeyRef>): Boolean {
            if (computerId == null) return false
            adopt(config, computerId)
            val all = config.optJSONObject(BY_COMPUTER) ?: JSONObject().also { config.put(BY_COMPUTER, it) }
            all.put(computerId, JSONObject().also { put(it, keys) })
            return true
        }

        /** Rattache l'ancien `keys` à [computerId] (sans écraser ses touches) puis le retire. Vrai si la config a changé. */
        fun adopt(config: JSONObject, computerId: String?): Boolean {
            if (computerId == null || !config.has("keys")) return false
            val all = config.optJSONObject(BY_COMPUTER) ?: JSONObject().also { config.put(BY_COMPUTER, it) }
            if (!all.has(computerId)) all.put(computerId, JSONObject().put("keys", config.get("keys")))
            config.remove("keys")
            return true
        }

        private const val BY_COMPUTER = "byComputer"
    }
}

/** Numéro d'emplacement d'une touche perso (tuile "k-<slot>"), null pour toute autre tuile. */
fun slotOf(id: String): Int? = if (id.startsWith("k-")) id.removePrefix("k-").toIntOrNull() else null

/** Page de touches nommée (rang, emplacement) d'une tuile `k<rang + 1>-<emplacement>`, null pour toute autre tuile. */
fun pageSlotOf(id: String): Pair<Int, Int>? {
    val (page, slot) = Regex("k(\\d+)-(\\d+)").matchEntire(id)?.destructured ?: return null
    return (page.toInt() - 1).takeIf { it >= 0 }?.let { it to slot.toInt() }
}

/** Premier emplacement vide de la page Perso (`editable`), null s'il n'y en a pas. */
fun nextFreeSlot(pages: List<DeckPage>): Int? =
    pages.firstOrNull { it.editable }?.tiles?.firstOrNull { it.type == "empty" }?.let { slotOf(it.id) }

/** Pages où copier une touche : Perso (rang null) et chaque page de touches nommée, dans l'ordre du deck. */
fun duplicateTargets(pages: List<DeckPage>): List<Pair<String, Int?>> =
    pages.filter { it.editable }.map { it.title to it.keyPage }

/**
 * Remet les touches choisies à jour d'après le deck : une touche retrouvée prend ses données à jour, une touche perso
 * vidée ou disparue est retirée, une touche d'une page fixe absente (page pas encore lue) est gardée.
 * Rend null quand rien ne change, et aussi quand `pages` est vide (deck pas encore lu) : tout paraîtrait absent.
 */
fun reconcileKeys(chosen: List<KeyRef>, pages: List<DeckPage>): List<KeyRef>? {
    if (pages.isEmpty()) return null
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

/**
 * Cotes d'une case de touche, en dp et sp. Normales : plaque de 52 dp, libellé de 13 sp. Grandes (thème Contraste
 * élevé, `c_big_keys`) : colonnes de 148 dp, plaque de 80 dp, libellé de 18 sp, assez pour deux lignes. La case
 * contient la plaque, [LABEL_GAP_DP] et deux lignes de libellé ; `TileGridView.newCell` l'agrandit si la taille du texte
 * choisie dans les Réglages dépasse ces cotes.
 */
class TileMetrics(val columnDp: Int, val cellDp: Int, val plateDp: Int, val labelSp: Float, val initialSp: Float) {
    companion object {
        /** Espace entre la plaque et le libellé. */
        const val LABEL_GAP_DP = 6

        val NORMAL = TileMetrics(columnDp = 96, cellDp = 100, plateDp = 52, labelSp = 13f, initialSp = 30f)
        val LARGE = TileMetrics(columnDp = 148, cellDp = 148, plateDp = 80, labelSp = 18f, initialSp = 42f)

        fun of(big: Boolean) = if (big) LARGE else NORMAL

        /** Rayon des coins d'une plaque : 22 sur 52, gardé en proportion. */
        fun radiusFor(plate: Int) = plate * 22 / 52

        /** Marge autour d'un symbole Material : 12 sur 52, l'icône garde un peu plus de la moitié de la plaque. */
        fun symbolPadFor(plate: Int) = plate * 12 / 52
    }
}
