package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkItem

/**
 * Classement des objets et du cyberware de la page Cyberpunk (logique pure). Les types viennent du jeu
 * (`Con_Injector`, `Clo_Outer`…) : un type inconnu tombe dans « Divers », jamais une erreur.
 */
object CyberpunkItems {
    /** Dans l'ordre d'affichage : les soins d'abord, ce qu'on utilise en jouant. */
    enum class Category { HEAL, AMMO, WEAPON, CLOTHES, MOD, OTHER }

    enum class Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

    /** Dans l'ordre d'affichage de la page Cyber. */
    enum class Zone(val keys: List<String>) {
        OS(listOf("systemreplacement", "operatingsystem", "os")),
        FRONTAL(listOf("frontalcortex", "frontal")),
        EYES(listOf("eyes")),
        ARMS(listOf("arms")),
        HANDS(listOf("hands")),
        SKELETON(listOf("musculoskeletal", "skeleton")),
        NERVOUS(listOf("nervous")),
        CARDIO(listOf("cardio")),
        IMMUNE(listOf("immune")),
        SKIN(listOf("integumentary", "skin")),
        LEGS(listOf("legs")),
    }

    fun category(type: String?): Category {
        val t = type?.lowercase() ?: return Category.OTHER
        return when {
            t.contains("inhaler") || t.contains("injector") || t.contains("longlasting") || t.contains("edible") || t.contains("heal") -> Category.HEAL
            t.contains("ammo") -> Category.AMMO
            t.startsWith("wea") -> Category.WEAPON
            t.startsWith("clo") -> Category.CLOTHES
            t.startsWith("prt") -> Category.MOD
            else -> Category.OTHER
        }
    }

    /** Groupes non vides, dans l'ordre des catégories ; les objets gardent l'ordre du jeu. */
    fun grouped(items: List<CyberpunkItem>): List<Pair<Category, List<CyberpunkItem>>> =
        Category.entries.map { c -> c to items.filter { category(it.type) == c } }.filter { it.second.isNotEmpty() }

    /** Objet du bouton Soin : le premier soin utilisable, les injecteurs et inhalateurs avant la nourriture. */
    fun healItem(items: List<CyberpunkItem>): CyberpunkItem? {
        val usable = items.filter { it.usable && category(it.type) == Category.HEAL }
        return usable.firstOrNull { it.type?.lowercase()?.let { t -> t.contains("injector") || t.contains("inhaler") } == true } ?: usable.firstOrNull()
    }

    fun rarity(quality: String?): Rarity = when (quality?.lowercase()) {
        "uncommon" -> Rarity.UNCOMMON
        "rare" -> Rarity.RARE
        "epic" -> Rarity.EPIC
        "legendary", "iconic" -> Rarity.LEGENDARY
        else -> Rarity.COMMON
    }

    /** Zone d'après le nom donné par le mod (`EyesCW`, `eyes`…) ; null si inconnue. */
    fun zone(key: String): Zone? {
        val k = key.lowercase()
        return Zone.entries.firstOrNull { z -> z.keys.any { k.startsWith(it) } }
    }
}
