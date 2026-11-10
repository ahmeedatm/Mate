package app.mate.ui

/**
 * Catégories des Réglages, dans l'ordre de la rangée d'onglets (fragment_settings.xml, une page par entrée).
 * Apparence s'ouvre d'abord : c'est l'onglet qu'on ouvre le plus. Le technicien arrive sur Système (carte Kiosque).
 */
enum class SettingsTab {
    APPARENCE,
    TABLETTE,
    NOTIFS,
    VOIX,
    ORDINATEURS,
    SYSTEME,
    ;

    companion object {
        val DEFAULT = APPARENCE
        val TECHNICIAN = SYSTEME

        /** Onglet gardé à la recréation (choix d'un thème) ; inconnu ou absent : celui par défaut. */
        fun fromSaved(name: String?): SettingsTab = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
