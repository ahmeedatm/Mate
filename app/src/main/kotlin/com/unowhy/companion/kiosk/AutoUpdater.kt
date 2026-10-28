package com.unowhy.companion.kiosk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.unowhy.companion.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vérifie les mises à jour au démarrage puis toutes les 6 h, et installe seule la release de main la plus récente du
 * Companion, jamais un essai (bouton de Réglages).
 * Seulement en device owner ; sinon rien, et jamais d'erreur à l'écran (journal seulement). Singleton : survit aux
 * `recreate()` de l'activité. Android arrête l'appli pour la remplacer, `UpdatedReceiver` la relance.
 */
object AutoUpdater {

    /** Échéance regardée (heure murale) tous les quarts d'heure, ou plus tôt si le réseau revient. */
    private const val TICK_MS = 15 * 60_000L

    private var job: Job? = null
    private var lastCheckMs: Long? = null

    /** Réveille la boucle sans attendre le prochain quart d'heure (retour du réseau). Conflé : un réveil en attente suffit. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        watchNetwork(app)
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (UpdatePlan.isDue(lastCheckMs, now)) {
                    // Heure mémorisée seulement si une source a répondu : sinon (démarrage hors ligne) on réessaie au tick suivant.
                    if (runOnce(app)) lastCheckMs = now
                }
                // Dort jusqu'au prochain quart d'heure, ou se réveille dès que le réseau revient.
                withTimeoutOrNull(TICK_MS) { wake.receive() }
            }
        }
    }

    /** Au retour du réseau, réveille la boucle ; [isDue] décide ensuite s'il faut vraiment revérifier. API 24+. */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wake.trySend(Unit) }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Surveillance du réseau indisponible", it) }
    }

    /** @return false seulement si aucune source n'a pu être jointe (à refaire au tick suivant). */
    private suspend fun runOnce(context: Context): Boolean {
        if (!Kiosk.isDeviceOwner(context)) return true
        val check = try {
            Updater.check(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Vérification des mises à jour en échec", e)
            return false
        }
        check.errors.forEach { (source, e) -> Log.w(TAG, "Source $source en erreur", e) }
        if (!UpdatePlan.reachedAnySource(check.attempted.size, check.errors.size)) {
            Log.i(TAG, "Aucune source de mise à jour joignable, nouvel essai au prochain tick")
            return false
        }
        try {
            val installed = Updater.installed(context, context.packageName) ?: return true
            val update = UpdatePlan.auto(check.updates.map { it.first }, context.packageName, Updater.versionCode(installed)) ?: return true
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
        return true
    }

    private const val TAG = "AutoUpdater"
}
