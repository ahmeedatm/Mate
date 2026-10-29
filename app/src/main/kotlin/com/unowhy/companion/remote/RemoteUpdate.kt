package com.unowhy.companion.remote

import android.content.Context
import com.unowhy.companion.R
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.kiosk.UpdatePlan
import com.unowhy.companion.kiosk.Updater
import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.ui.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Mise à jour du Companion demandée depuis la page de l'ordinateur (ordre `update`) : même choix que la mise à jour
 * automatique (release de main la plus récente, jamais un essai) et même installation que la carte Mises à jour.
 * Singleton : l'installation survit aux `recreate()` ; Android arrête l'appli pour la remplacer.
 */
internal object RemoteUpdate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Rien n'a encore été cherché : la page lance une recherche en s'ouvrant. */
    private var checked = false
    private var available: AppUpdate? = null
    private var installing = false
    private var failure: String? = null

    suspend fun check(context: Context) {
        failure = null
        val check = Updater.check(context)
        val installed = Updater.installed(context, context.packageName)?.let(Updater::versionCode) ?: 0
        available = UpdatePlan.auto(check.updates.map { it.first }, context.packageName, installed)
        checked = true
        // Toutes les sources en erreur : la réponse « à jour » ne voudrait rien dire.
        if (!UpdatePlan.reachedAnySource(check.attempted.size, check.errors.size)) {
            failure = context.errorText(check.errors.values.first())
        }
    }

    /** Lance l'installation et rend la main tout de suite : le téléchargement dure plus longtemps que l'ordre. */
    fun install(context: Context) {
        val update = available ?: error("Aucune mise à jour à installer : la tablette est à jour")
        if (installing) return
        if (!Kiosk.isDeviceOwner(context)) error(context.getString(R.string.update_needs_kiosk))
        val app = context.applicationContext
        installing = true
        failure = null
        scope.launch {
            try {
                Updater.install(app, update, progress = {})
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = app.errorText(e)
            } finally {
                installing = false
            }
        }
    }

    /** Envoyé à l'ordinateur avec l'état de la tablette (carte Réglages de la tablette). */
    fun state(context: Context): JSONObject = JSONObject()
        .put("checked", checked)
        .put("available", available?.versionName ?: JSONObject.NULL)
        .put("installing", installing)
        .put("error", failure ?: JSONObject.NULL)
        .put("kiosk", Kiosk.isDeviceOwner(context))
}
