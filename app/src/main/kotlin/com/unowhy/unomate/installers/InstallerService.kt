package com.unowhy.unomate.installers

import android.content.Context
import android.util.Log
import com.unowhy.unomate.BuildConfig
import com.unowhy.unomate.device.Tablet
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.Identity
import com.unowhy.unomate.net.UpdatesApi
import com.unowhy.unomate.net.Wifi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installeurs de l'ordinateur (Mac, Windows) : téléchargés d'avance depuis le serveur central quand une release en
 * publie de nouveaux (empreinte SHA-256 vérifiée, seule la dernière version gardée), puis servis sur le WiFi par
 * [InstallerHttpServer]. Sur le WiFi seulement (aucun forfait n'est entamé) ; jamais d'erreur à l'écran.
 */
object InstallerService {

    const val PORT = 8080
    private const val TAG = "InstallerSync"

    /** Marge laissée libre sur le stockage en plus du fichier : la tablette fait aussi tourner l'appli. */
    private const val FREE_MARGIN = 200L * 1_000_000

    private var server: InstallerHttpServer? = null

    private fun store(context: Context) = InstallerStore(File(context.applicationContext.filesDir, "installers"))

    /** Écoute le port 8080. Appli d'essai : rien, la stable sert déjà (même port) et a les mêmes fichiers. */
    fun start(context: Context) {
        if (server != null || BuildConfig.TRIAL) return
        val store = store(context)
        server = InstallerHttpServer(PORT, store.dir, store::onDisk).also { it.start() }
    }

    /** `http://<ip de la tablette>:8080`, null sans réseau. Affiché sur Bienvenue et dans Réglages. */
    fun address(): String? = Tablet.ipAddress()?.let { "http://$it:$PORT" }

    /** Identité de l'ordinateur actif et installeur plus récent que son compagnon (null : à jour) ; null s'il est injoignable. */
    suspend fun check(context: Context): Pair<Identity, Installer?>? {
        if (BuildConfig.TRIAL) return null
        val identity = runCatching { BackendClient(context).pairing.identity() }.getOrNull() ?: return null
        val onDisk = withContext(Dispatchers.IO) { store(context).onDisk() }
        return identity to InstallerPlan.newerFor(identity.os, identity.version, onDisk)
    }

    /** Vérifie le manifeste du serveur central et met à jour les fichiers ; appelée à chaque vérification des mises à jour. */
    suspend fun sync(context: Context) {
        if (BuildConfig.TRIAL || Wifi.current(context) == null) return
        val api = UpdatesApi.server() ?: return
        val store = store(context)
        try {
            val announced = api.installers()
            val onDisk = withContext(Dispatchers.IO) { store.onDisk() }
            for (installer in InstallerPlan.toDownload(announced, onDisk)) download(api, store, installer)
            withContext(Dispatchers.IO) { store.remove(InstallerPlan.toDelete(announced, onDisk)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Manifeste absent (404, Pages privées), réseau coupé, disque plein : réessai à la prochaine vérification.
            Log.i(TAG, "Installeurs non mis à jour", e)
        }
    }

    private suspend fun download(api: UpdatesApi, store: InstallerStore, installer: Installer) {
        val part = store.part(installer)
        try {
            if (part.parentFile!!.usableSpace < installer.size + FREE_MARGIN) {
                Log.w(TAG, "Pas assez de place pour ${installer.file}")
                return
            }
            val sha256 = api.downloadInstaller(installer, part)
            if (!sha256.equals(installer.sha256, ignoreCase = true)) {
                Log.w(TAG, "Empreinte de ${installer.file} fausse, fichier jeté")
                return
            }
            withContext(Dispatchers.IO) { store.commit(installer) }
            Log.i(TAG, "${installer.file} ${installer.version} prêt")
        } finally {
            part.delete()
        }
    }
}
