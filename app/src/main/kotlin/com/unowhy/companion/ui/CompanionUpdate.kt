package com.unowhy.companion.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.installers.InstallerService
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Identity
import kotlinx.coroutines.launch

/**
 * Compagnon de l'ordinateur actif comparé à l'installeur gardé par la tablette, tous les quarts d'heure : bandeau une fois
 * par version, qui ouvre les étapes (lien à ouvrir sur l'ordinateur, d'un bouton) ; une fois les étapes ouvertes, vérifié
 * chaque minute jusqu'à la nouvelle version, puis « à jour ».
 */
class CompanionUpdate(private val activity: MainActivity) {

    private var lastCheckMs = 0L
    private val notified = mutableSetOf<String>()

    /** Ordinateur (id) → version attendue, étapes ouvertes. */
    private val pending = mutableMapOf<String, String>()

    fun onMinute() {
        val now = System.currentTimeMillis()
        if (pending.isEmpty() && now - lastCheckMs < CHECK_MS) return
        lastCheckMs = now
        activity.lifecycleScope.launch { check() }
    }

    private suspend fun check() {
        val (identity, installer) = InstallerService.check(activity) ?: return
        val id = identity.id ?: return
        if (installer == null) {
            val version = identity.version ?: return
            if (pending.remove(id) != null) activity.showBanner(activity.getString(R.string.companion_updated, identity.name, version), 8)
            return
        }
        val address = InstallerService.address() ?: return
        if (!notified.add("$id:${installer.version}")) return
        val text = activity.getString(R.string.companion_update_available, identity.name, installer.version)
        activity.showBanner(text, 20) {
            pending[id] = installer.version
            showSteps(identity, address)
        }
    }

    private fun showSteps(identity: Identity, address: String) {
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
