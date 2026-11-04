package app.mate.ui.mixer

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Applis masquées de la table de mixage (« Ne plus afficher »), rangées par ordinateur dans les préférences de la
 * tablette : la liste vaut pour toutes les tables de cet ordinateur. Calculs dans [MixerApps].
 */
object MixerHidden {
    private const val FILE = "mixer"
    private const val KEY = "hidden"

    fun read(context: Context, computerId: String?): List<MixerApps.App> = MixerApps.read(store(context), computerId, MixerApps.HIDDEN_KEY)

    fun hide(context: Context, computerId: String?, app: MixerApps.App) = save(context) { MixerApps.hide(it, computerId, app) }

    fun unhide(context: Context, computerId: String?, id: String) = save(context) { MixerApps.unhide(it, computerId, id) }

    private fun save(context: Context, change: (JSONObject) -> Unit) {
        val store = store(context)
        change(store)
        prefs(context).edit().putString(KEY, store.toString()).apply()
    }

    /** Valeur illisible : repartir d'une liste vide plutôt que de casser la table. */
    private fun store(context: Context): JSONObject = try {
        JSONObject(prefs(context).getString(KEY, null) ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
