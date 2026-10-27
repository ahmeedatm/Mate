package com.unowhy.companion.kiosk

import android.content.Context
import android.util.Log
import com.unowhy.companion.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Vérifie les mises à jour au démarrage puis toutes les 6 h, et installe seule la plus récente du Companion.
 * Seulement en device owner ; sinon rien, et jamais d'erreur à l'écran (journal seulement). Singleton : survit aux
 * `recreate()` de l'activité. Android arrête l'appli pour la remplacer, `UpdatedReceiver` la relance.
 */
object AutoUpdater {

    /** Pas de réveil : on regarde l'échéance (heure murale) tous les quarts d'heure. */
    private const val TICK_MS = 15 * 60_000L

    private var job: Job? = null
    private var lastCheckMs: Long? = null

    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (UpdatePlan.isDue(lastCheckMs, now)) {
                    lastCheckMs = now
                    runOnce(app)
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun runOnce(context: Context) {
        if (!Kiosk.isDeviceOwner(context)) return
        try {
            val check = Updater.check(context)
            check.errors.forEach { (source, e) -> Log.w(TAG, "Source $source en erreur", e) }
            val installed = Updater.installed(context, context.packageName) ?: return
            val update = UpdatePlan.auto(check.updates.map { it.first }, context.packageName, Updater.versionCode(installed)) ?: return
            Log.i(TAG, "Installation de ${update.versionName} (${update.source})")
            Updater.install(context, update, progress = {})
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            // Déjà en cours depuis Réglages, empreinte fausse, refus d'Android : on réessaiera à la prochaine échéance.
            Log.w(TAG, "Mise à jour automatique non faite : ${context.getString(e.res, *e.args)}")
        } catch (e: Exception) {
            Log.w(TAG, "Mise à jour automatique en échec", e)
        }
    }

    private const val TAG = "AutoUpdater"
}
