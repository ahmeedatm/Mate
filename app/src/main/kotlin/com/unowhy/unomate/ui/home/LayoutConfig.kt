package com.unowhy.unomate.ui.home

import com.unowhy.unomate.Mode
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.net.HomeFeatures
import com.unowhy.unomate.ui.topbar.TopBarModel
import com.unowhy.unomate.ui.topbar.TopItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration des profils et des barres envoyée depuis la page de l'ordinateur (ordre `layout`, remote/TabletLayout.kt).
 * Tout est vérifié ici avant d'écrire quoi que ce soit : un ordre refusé ne change rien. Les messages d'erreur sont en
 * français, la page les affiche tels quels. Pas de ressource Android : testable sur la JVM.
 */
object LayoutConfig {

    /** Textes que la tablette donne à la page : titre du widget, nom de la taille, nom d'un élément du bandeau, d'un outil. */
    class Labels(
        val widget: (WidgetType) -> String,
        val size: (WidgetSize) -> String,
        val top: (TopItem) -> String,
        val tool: (Mode) -> String,
    )

    /**
     * Disposition envoyée par la page, relue strictement (la lecture d'enregistrement, elle, laisse tomber ce qu'elle ne
     * comprend pas). `current` : disposition en place, dont les rôles (Accueil, Réunion) doivent rester.
     */
    fun parseLayout(json: JSONObject, current: HomeLayout): HomeLayout {
        val saved = json.optJSONArray("names") ?: fail("Disposition invalide : la liste des profils manque.")
        if (saved.length() == 0) fail("Il faut au moins un profil.")
        val names = MutableList<String?>(saved.length()) { i ->
            if (saved.isNull(i)) return@MutableList null
            val name = (saved.opt(i) as? String)?.trim() ?: fail("Le nom du profil ${i + 1} n'est pas un texte.")
            if (name.isEmpty()) fail("Le profil ${i + 1} n'a pas de nom.")
            if (name.length > ProfilesDialog.NAME_MAX) fail("Le nom du profil ${i + 1} dépasse ${ProfilesDialog.NAME_MAX} caractères.")
            name
        }
        val layout = HomeLayout(mutableListOf(), names)
        json.optJSONArray("icons")?.let { icons ->
            for (i in names.indices) if (i < icons.length() && !icons.isNull(i)) layout.setIcon(i, icons.optString(i))
        }

        layout.homeProfile = role(json, "home", names.size, required = true)
        layout.deckProfile = role(json, "deck", names.size, required = false)
        layout.callProfile = role(json, "call", names.size, required = false)
        layout.rolesSet = json.optBoolean("roles", false)
        if (current.rolesSet) {
            if (!layout.rolesSet) fail("Les rôles de l'Accueil et de Réunion doivent rester posés.")
            if (current.callProfile >= 0 && layout.callProfile < 0) fail("Le profil Réunion ne peut pas être supprimé.")
        }
        val roleIndexes = listOf(layout.homeProfile, layout.deckProfile, layout.callProfile).filter { it >= 0 }
        if (roleIndexes.size != roleIndexes.toSet().size) fail("L'Accueil, le Deck et Réunion doivent être trois profils différents.")

        val list = json.optJSONArray("widgets") ?: fail("Disposition invalide : la liste des widgets manque.")
        for (i in 0 until list.length()) {
            val w = list.optJSONObject(i) ?: fail("Le widget ${i + 1} est illisible.")
            val key = w.optString("t")
            val type = WidgetType.of(key) ?: fail("Widget inconnu : $key (mettre à jour l'appli ?).")
            val size = WidgetSize.entries.firstOrNull { it.name == w.optString("s") }
                ?: fail("Taille inconnue pour le widget $key : ${w.optString("s")}.")
            if (size !in type.sizes) fail("La taille ${size.name} n'est pas permise pour le widget $key.")
            if (!w.has("p") || !w.has("c") || !w.has("r")) fail("Le widget $key n'a pas de place.")
            val page = w.optInt("p", -1)
            if (page !in names.indices) fail("Le widget $key est sur un profil qui n'existe pas.")
            val col = w.optInt("c", -1)
            val row = w.optInt("r", -1)
            if (col < 0 || row < 0 || col + size.w > COLS || row + size.h > ROWS) {
                fail("Le widget $key sort de la grille ($COLS × $ROWS).")
            }
            if (!layout.fits(page, col, row, size)) fail("Le widget $key en chevauche un autre (profil ${page + 1}).")
            layout.widgets += Placed(type, size, page, col, row, JSONObject(w.optJSONObject("cfg")?.toString() ?: "{}"))
        }
        return layout
    }

    private fun role(json: JSONObject, key: String, count: Int, required: Boolean): Int {
        val index = json.optInt(key, -1)
        return when {
            index in 0 until count -> index
            index == -1 && !required -> -1
            else -> fail("Disposition invalide : le profil « $key » n'existe pas.")
        }
    }

    /** Éléments du bandeau du haut envoyés par la page (clés, dans l'ordre) : clé inconnue ou en double refusée. */
    fun parseTopBar(keys: JSONArray): List<TopItem> {
        val items = (0 until keys.length()).map { i ->
            val key = keys.optString(i)
            TopItem.of(key) ?: fail("Élément du bandeau inconnu : $key (mettre à jour l'appli ?).")
        }
        if (items.size != items.toSet().size) fail("Un élément du bandeau est en double.")
        return items
    }

    /** Outils de la barre de gauche affichés (clés) : seuls ceux de la barre sont acceptés. */
    fun parseRailTools(keys: JSONArray): Set<Mode> {
        val tools = (0 until keys.length()).map { i ->
            val key = keys.optString(i)
            Prefs.RAIL_TOOLS.firstOrNull { it.key == key } ?: fail("Outil inconnu pour la barre de gauche : $key.")
        }
        return tools.toSet()
    }

    /** Ce que `get` rend : disposition, catalogue, bandeau du haut et barre de gauche, tels qu'ils sont réglés. */
    fun describe(
        layout: HomeLayout,
        cadran: Boolean,
        computerId: String?,
        features: HomeFeatures,
        topItems: List<TopItem>,
        railLabels: Boolean,
        railShown: List<Mode>,
        labels: Labels,
    ): JSONObject {
        fun size(s: WidgetSize) = JSONObject().put("key", s.name).put("w", s.w).put("h", s.h).put("label", labels.size(s))
        return JSONObject()
            .put("layout", JSONObject(layout.toJson()))
            .put("cadran", cadran)
            .put("computerId", computerId ?: JSONObject.NULL)
            .put("nameMax", ProfilesDialog.NAME_MAX)
            .put("cols", COLS)
            .put("rows", ROWS)
            .put("sizes", JSONArray().apply { WidgetSize.entries.forEach { put(size(it)) } })
            .put("catalog", JSONArray().apply {
                for (type in WidgetType.entries) {
                    put(
                        JSONObject()
                            .put("key", type.key)
                            .put("title", labels.widget(type))
                            .put("computer", type.computer)
                            .put("offered", type.offered(features))
                            .put("sizes", JSONArray().apply { type.sizes.forEach { put(size(it)) } }),
                    )
                }
            })
            .put(
                "topBar",
                JSONObject()
                    .put("items", JSONArray().apply { topItems.forEach { put(item(it, labels)) } })
                    .put("addable", JSONArray().apply { TopBarModel.addable(topItems, features.agents).forEach { put(item(it, labels)) } }),
            )
            .put(
                "rail",
                JSONObject()
                    .put("labels", railLabels)
                    .put("tools", JSONArray().apply { railShown.forEach { put(tool(it, labels)) } })
                    .put("available", JSONArray().apply { Prefs.RAIL_TOOLS.filter { it !in railShown }.forEach { put(tool(it, labels)) } }),
            )
    }

    private fun item(item: TopItem, labels: Labels) = JSONObject().put("key", item.key).put("label", labels.top(item))

    private fun tool(mode: Mode, labels: Labels) = JSONObject().put("key", mode.key).put("label", labels.tool(mode))

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
