package app.mate

import android.content.Context
import android.content.SharedPreferences
import app.mate.ui.home.HomeLayout

/**
 * Config propre à chaque ordinateur appairé : écrans de l'Accueil, bandeau du haut, barre de gauche et choix du
 * modèle. Chaque clé est rangée sous `<clé>@<id de l'ordinateur>` ; sans ordinateur, sous la clé seule. Le thème et
 * les autres réglages restent ceux de la tablette.
 */
object ComputerConfig {
    private const val KEY_MIGRATED = "per_computer_config"

    /** Clés propres à chaque ordinateur, dans le fichier [Prefs.FILE]. */
    val KEYS = setOf(
        HomeLayout.KEY, "top_bar", "top_bar_bell", "top_weather", "rail_hidden_tools", "rail_labels", "rail_talk", "templates_offered",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    /** Clé de `base` pour l'ordinateur actif. */
    fun key(context: Context, base: String): String {
        val id = Computers.active(context)?.id ?: return base
        migrate(context)
        return scoped(base, id)
    }

    /** Ordinateur oublié : sa config part avec lui. */
    fun forget(context: Context, id: String) {
        val edit = prefs(context).edit()
        KEYS.forEach { edit.remove(scoped(it, id)) }
        edit.apply()
    }

    /** Ordinateur provisoire adopté sous son vrai `id` : sa config le suit, sauf si le vrai en a déjà une. */
    fun move(context: Context, from: String, to: String) {
        val p = prefs(context)
        if (from == to || KEYS.any { p.contains(scoped(it, to)) }) return
        val edit = p.edit()
        KEYS.forEach { copy(p, edit, scoped(it, from), scoped(it, to)) }
        edit.apply()
        forget(context, from)
    }

    /**
     * Une fois, au premier ordinateur connu : la config commune d'avant devient celle de chaque ordinateur déjà
     * appairé. Un ordinateur appairé ensuite part de la config de base.
     */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val edit = p.edit()
        for (computer in Computers.all(context)) KEYS.forEach { copy(p, edit, it, scoped(it, computer.id)) }
        KEYS.forEach { edit.remove(it) }
        edit.putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun scoped(base: String, id: String) = "$base@$id"

    @Suppress("UNCHECKED_CAST")
    private fun copy(p: SharedPreferences, edit: SharedPreferences.Editor, from: String, to: String) {
        when (val value = p.all[from]) {
            is String -> edit.putString(to, value)
            is Boolean -> edit.putBoolean(to, value)
            is Set<*> -> edit.putStringSet(to, (value as Set<String>).toSet())
            is Long -> edit.putLong(to, value)
            is Int -> edit.putInt(to, value)
            is Float -> edit.putFloat(to, value)
        }
    }
}
