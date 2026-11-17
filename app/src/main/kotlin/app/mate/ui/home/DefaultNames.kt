package app.mate.ui.home

import android.content.Context
import android.content.res.Configuration
import app.mate.AppLanguage
import app.mate.R

/**
 * Noms d'écran donnés par Mate (premier écran, « Écran N », écran Réunion, écrans des modèles) : enregistrés dans la
 * langue du moment, montrés dans la langue choisie (Réglages > Langue). Un nom saisi par l'utilisateur reste tel quel.
 */
object DefaultNames {
    private val FIXED = listOf(
        R.string.profile_first,
        R.string.profile_call,
        R.string.template_call,
        R.string.template_screen_agents,
        R.string.template_screen_gitlab,
        R.string.template_screen_jira,
        R.string.template_screen_pipelines,
    )

    /** Nom à montrer : `name` s'il n'est un nom par défaut dans aucune langue, sinon son équivalent dans `current`. */
    fun shown(name: String, byLanguage: List<Names>, current: Names): String {
        for (names in byLanguage) {
            val i = names.fixed.indexOf(name)
            if (i >= 0) return current.fixed[i]
            val n = numberIn(name, names.numbered)
            if (n != null) return current.numbered.format(n)
        }
        return name
    }

    /** Noms par défaut d'une langue : les noms fixes dans l'ordre de [FIXED], et le modèle « Écran %d ». */
    class Names(val fixed: List<String>, val numbered: String)

    fun shown(context: Context, name: String): String {
        val all = AppLanguage.entries.map { names(context, it) }
        return shown(name, all, names(context, AppLanguage.current))
    }

    private fun names(context: Context, language: AppLanguage): Names {
        val res = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(language.locale) }).resources
        return Names(FIXED.map(res::getString), res.getString(R.string.profile_default))
    }

    private fun numberIn(name: String, pattern: String): Int? {
        val (before, after) = pattern.split("%d").takeIf { it.size == 2 } ?: return null
        if (!name.startsWith(before) || !name.endsWith(after)) return null
        return name.substring(before.length, name.length - after.length).toIntOrNull()
    }
}
