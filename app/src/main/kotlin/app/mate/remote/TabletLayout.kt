package app.mate.remote

import android.content.Context
import app.mate.Computers
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HomeFeatures
import app.mate.ui.home.HomeLayout
import app.mate.ui.home.LayoutConfig
import app.mate.ui.themeBool
import app.mate.ui.topbar.TopBarModel
import app.mate.ui.topbar.TopItems
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Profils et barres lus et changés depuis la page de l'ordinateur (section Profils de backend/src/local-page.ts, ordre
 * `layout`). La tablette reste la source de vérité : `get` rend son état, `set` vérifie tout (LayoutConfig), enregistre,
 * rafraîchit l'écran et rend le même état.
 */
internal object TabletLayout {

    suspend fun get(activity: MainActivity): JSONObject = read(activity)

    suspend fun set(activity: MainActivity, command: JSONObject): JSONObject {
        // Tout est lu et vérifié avant d'écrire : un ordre refusé ne change rien.
        val layout = command.optJSONObject("layout")?.let { LayoutConfig.parseLayout(it, HomeLayout.load(activity)) }
        val topBar = command.optJSONArray("topBar")?.let(LayoutConfig::parseTopBar)
        val rail = command.optJSONObject("rail")
        val labels = rail?.takeIf { it.has("labels") }?.getBoolean("labels")
        val tools = rail?.optJSONArray("tools")?.let(LayoutConfig::parseRailTools)

        layout?.let { HomeLayout.save(activity, it) }
        topBar?.let { Prefs.setTopBar(activity, TopBarModel.serialize(it)) }
        labels?.let { Prefs.setRailLabels(activity, it) }
        tools?.let { shown -> Prefs.RAIL_TOOLS.forEach { Prefs.setRailTool(activity, it, it in shown) } }
        activity.onRemoteLayout()
        return read(activity)
    }

    private suspend fun read(activity: MainActivity): JSONObject {
        val computer = Computers.active(activity)
        val features = try {
            if (computer == null) HomeFeatures() else BackendClient(activity).deck.homeFeatures()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HomeFeatures()
        }
        return describe(activity, HomeLayout.load(activity), computer?.id, features)
    }

    private fun describe(context: Context, layout: HomeLayout, computerId: String?, features: HomeFeatures) =
        LayoutConfig.describe(
            layout, computerId, features,
            topItems = TopBarModel.parse(Prefs.topBar(context)),
            railLabels = Prefs.railLabels(context),
            railShown = Prefs.railTools(context),
            labels = LayoutConfig.Labels(
                widget = { context.getString(it.title) },
                size = { context.getString(it.label) },
                top = { context.getString(TopItems.label(it)) },
                tool = { context.getString(it.label) },
            ),
        )
}
