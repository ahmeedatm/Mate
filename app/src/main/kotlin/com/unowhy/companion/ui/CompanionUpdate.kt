package com.unowhy.companion.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.installers.BackendPlan
import com.unowhy.companion.installers.BackendRelease
import com.unowhy.companion.installers.CompanionUpdater
import com.unowhy.companion.installers.InstallerService
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Identity
import kotlinx.coroutines.launch

/**
 * Compagnon de l'ordinateur actif, vérifié tous les quarts d'heure. Mise à jour publiée et compagnon qui sait la
 * recevoir : bandeau une fois par version, qui la lance après confirmation (installers/CompanionUpdater). Compagnon
 * plus ancien : comparé à l'appli gardée par la tablette, bandeau qui ouvre les étapes (panneau de l'appli, ou lien
 * à ouvrir sur l'ordinateur, d'un bouton) ; une fois les étapes ouvertes, vérifié chaque minute jusqu'à la nouvelle
 * version.
 */
class CompanionUpdate(private val activity: MainActivity) {

    private var lastCheckMs = 0L
    private val notified = mutableSetOf<String>()

    /** Ordinateur (id) → version attendue, étapes de l'installeur ouvertes. */
    private val pending = mutableMapOf<String, String>()

    fun onMinute() {
        val now = System.currentTimeMillis()
        if (pending.isEmpty() && now - lastCheckMs < CHECK_MS) return
        lastCheckMs = now
        activity.lifecycleScope.launch { check() }
    }

    private suspend fun check() {
        val status = CompanionUpdater.check(activity) ?: return
        val identity = status.identity ?: return
        val decision = status.decision
        if (decision is BackendPlan.Decision.Available) {
            val id = identity.id ?: return
            if (!notified.add("$id:${decision.release.version}")) return
            val text = activity.getString(R.string.companion_update_available, identity.name, decision.release.version)
            activity.showBanner(text, 20) { confirm(identity, decision.release) }
            return
        }
        // Compagnon qui se met à jour depuis la tablette : à jour, sur un essai ou plus récent, rien à proposer.
        if (!identity.backendUpdate) checkInstaller()
    }

    private suspend fun checkInstaller() {
        val (identity, installer) = InstallerService.check(activity) ?: return
        val id = identity.id ?: return
        if (installer == null) {
            val version = identity.version ?: return
            if (pending.remove(id) != null) activity.showBanner(activity.getString(R.string.companion_updated, identity.name, version), 8)
            return
        }
        if (!notified.add("$id:${installer.version}")) return
        val text = activity.getString(R.string.companion_update_available, identity.name, installer.version)
        activity.showBanner(text, 20) {
            if (showInstallSteps(identity)) pending[id] = installer.version
        }
    }

    /**
     * Confirmation, puis mise à jour en tâche de l'activité (elle survit à la fermeture des Réglages). `onState` : texte
     * de l'étape en cours ; `onDone` : réussite ou non. Résultat aussi dit par un bandeau.
     */
    fun confirm(identity: Identity, release: BackendRelease, onState: (String) -> Unit = {}, onDone: (Boolean) -> Unit = {}) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.companion_update_title, identity.name))
            .setMessage(activity.getString(R.string.companion_update_confirm, identity.name, release.version))
            .setNegativeButton(R.string.companion_update_later, null)
            .setPositiveButton(R.string.companion_update_go) { _, _ -> run(identity, release, onState, onDone) }
            .showImmersive()
    }

    private fun run(identity: Identity, release: BackendRelease, onState: (String) -> Unit, onDone: (Boolean) -> Unit) {
        activity.lifecycleScope.launch {
            val ok = try {
                var shown = -1
                CompanionUpdater.update(activity, release) { percent ->
                    if (percent / 5 != shown) {
                        shown = percent / 5
                        activity.runOnUiThread {
                            onState(activity.getString(if (percent >= 100) R.string.companion_update_sending else R.string.companion_update_downloading, percent))
                        }
                    }
                }
                activity.showBanner(activity.getString(R.string.companion_updated, identity.name, release.version), 8)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.showBanner(activity.errorText(e), 10)
                false
            }
            onDone(ok)
        }
    }

    /** Étapes de l'installeur ; faux si la tablette ne sert pas les installeurs (pas de WiFi, appli d'essai). */
    fun showInstallSteps(identity: Identity): Boolean {
        if (com.unowhy.companion.BuildConfig.TRIAL) return false
        val address = InstallerService.address() ?: return false
        val steps = if (identity.os == "windows") R.string.companion_update_steps_windows else R.string.companion_update_steps_mac
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.companion_update_title, identity.name))
            .setMessage(activity.getString(steps, address))
            .setPositiveButton(R.string.companion_update_open, null)
            .setNegativeButton(R.string.companion_update_later, null)
            .showImmersive()
            .apply {
                // Le bouton n'est pas celui par défaut : la boîte reste ouverte pour lire la suite des étapes.
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openOnComputer(address) }
            }
        return true
    }

    private fun openOnComputer(address: String) = activity.lifecycleScope.launch {
        val text = try {
            BackendClient(activity).docs.openUrlOnComputer(address)
            activity.getString(R.string.companion_update_opened)
        } catch (e: Exception) {
            activity.errorText(e)
        }
        activity.showBanner(text, 6)
    }

    private companion object {
        const val CHECK_MS = 15 * 60_000L
    }
}
