package com.unowhy.unomate.ui.topbar

/**
 * Éléments du bandeau du haut, à droite du titre de l'écran. `computer` : a besoin de l'ordinateur (caché sans lui) ;
 * `self` : se cache seul quand il n'a rien à montrer (rien ne joue, pas de chrono, pas d'appel, aucun agent qui attend).
 */
enum class TopItem(val key: String, val computer: Boolean, val self: Boolean) {
    MUSIC("music", true, true),
    MEETING("meeting", true, true),
    FOCUS("focus", false, true),
    CALL("call", true, true),
    VOLUME("volume", true, false),
    AGENTS("agents", true, true),
    WEATHER("weather", false, false),
    DATE("date", false, false),
    CLOCK("clock", false, false),
    WIFI("wifi", false, false),
    BATTERY("battery", false, false),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Liste des éléments du bandeau, dans l'ordre affiché (de gauche à droite) : lecture, ajout, retrait, rangement, place. */
object TopBarModel {
    /** Le bandeau d'avant sa personnalisation : lecture en cours, heure, WiFi, batterie. */
    val DEFAULT = listOf(TopItem.MUSIC, TopItem.CLOCK, TopItem.WIFI, TopItem.BATTERY)

    /** Clés séparées par des virgules ; null (jamais réglé) : [DEFAULT]. Clé inconnue ou en double ignorée. */
    fun parse(text: String?): List<TopItem> = text?.split(',')?.mapNotNull { TopItem.of(it.trim()) }?.distinct() ?: DEFAULT

    fun serialize(items: List<TopItem>) = items.joinToString(",") { it.key }

    /** Ce qui peut s'ajouter : ce qui n'y est pas encore ; Agents seulement si l'ordinateur les offre (Mac avec Claude Code). */
    fun addable(items: List<TopItem>, agents: Boolean) = TopItem.entries.filter { it !in items && (it != TopItem.AGENTS || agents) }

    /** Ajouté au début (à gauche) : l'heure et la batterie restent au bout, on le range ensuite en le glissant. */
    fun add(items: List<TopItem>, item: TopItem) = if (item in items) items else listOf(item) + items

    fun remove(items: List<TopItem>, item: TopItem) = items - item

    /** `item` déplacé à la place `to` (bornée), les autres gardent leur ordre. */
    fun move(items: List<TopItem>, item: TopItem, to: Int): List<TopItem> {
        if (item !in items) return items
        val rest = items - item
        return rest.toMutableList().apply { add(to.coerceIn(0, rest.size), item) }
    }

    /**
     * Place : largeurs des éléments affichés (0 = caché, ne compte pas), espace `gap` entre deux, `room` disponible. Rend
     * l'indice du premier élément gardé : ceux du début sautent d'abord, l'heure et la batterie (au bout) restent.
     */
    fun firstShown(widths: List<Int>, gap: Int, room: Int): Int {
        var used = 0
        var count = 0
        for (i in widths.indices.reversed()) {
            if (widths[i] <= 0) continue
            val next = used + widths[i] + if (count > 0) gap else 0
            if (next > room) return i + 1
            used = next
            count++
        }
        return 0
    }
}
