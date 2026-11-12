package app.mate.installers

import android.content.Context
import android.util.Log
import app.mate.BuildConfig
import app.mate.device.Tablet
import app.mate.net.BackendClient
import app.mate.net.Identity
import app.mate.net.UpdatesApi
import app.mate.net.Wifi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installeurs de l'ordinateur (Mac, Windows) : téléchargés d'avance depuis GitHub quand une release en
 * publie de nouveaux (empreinte SHA-256 vérifiée, seule la dernière version gardée), puis servis sur le WiFi par
 * [InstallerHttpServer], sous le code affiché sur Bienvenue. Sur le WiFi seulement (aucun forfait n'est entamé) ; jamais d'erreur à l'écran.
 */
object InstallerService {

    const val PORT = 8080

    /** Si 8080 est pris (UnoMate installé à côté écoute le même) : le premier libre, annoncé à l'ordinateur ([port]). */
    private val FALLBACK_PORTS = (8081..8089).toList()
    private const val TAG = "InstallerSync"

    /** Marge laissée libre sur le stockage en plus du fichier : la tablette fait aussi tourner l'appli. */
    private const val FREE_MARGIN = 200L * 1_000_000

    private var server: InstallerHttpServer? = null
    private val syncing = Mutex()

    /** Code d'accès à la page : en mémoire seulement, tiré à chaque affichage de Bienvenue ([newCode]). */
    @Volatile private var code: String? = null

    private val _progress = MutableStateFlow<Int?>(null)

    /** Pourcentage du téléchargement en cours, null quand aucun ne tourne. */
    val progress: StateFlow<Int?> = _progress

    private fun store(context: Context) = InstallerStore(File(context.applicationContext.filesDir, "installers"))

    /** Écoute le port 8080, ou un suivant s'il est pris. Appli d'essai : rien, la stable sert déjà et a les mêmes fichiers. */
    fun start(context: Context) {
        if (server != null || BuildConfig.TRIAL) return
        val store = store(context)
        server = InstallerHttpServer(PORT, store.dir, store::onDisk, { code }, fallbacks = FALLBACK_PORTS).also { it.start() }
    }

    /** Port réellement écouté, envoyé dans l'état du poll ; null si aucun n'a pu l'être (et dans l'appli d'essai). */
    fun port(): Int? = server?.boundPort

    /**
     * Installeurs servis sur le port 8080, envoyés dans l'état du poll (HTTPS, jeton) : l'ordinateur y lit l'empreinte
     * attendue au lieu de la croire du port 8080 en clair (backend/src/app-update.ts). Appli d'essai : aucun.
     */
    fun served(context: Context): List<Installer> = if (BuildConfig.TRIAL) emptyList() else store(context).onDisk()

    /** Nouveau code, l'ancien ne marche plus. */
    @Synchronized
    fun newCode(): String = InstallerAccess.newCode().also { code = it }

    @Synchronized
    private fun code(): String = code ?: newCode()

    /**
     * Code courant, envoyé dans l'état du poll (HTTPS, jeton) : l'ordinateur appairé le lit là pour télécharger sous
     * `/<code>/…` sans jamais envoyer son jeton sur le port 8080. Appli d'essai : aucun.
     */
    fun servedCode(): String? = if (BuildConfig.TRIAL) null else code()

    /**
     * Bienvenue fermé : le code lu sur cet écran ne marche plus (un autre est tiré, le poll en a besoin). Seul Bienvenue
     * expire : le code lu dans Réglages ou dans « trop ancien » vit jusqu'au prochain tirage ou au redémarrage de l'appli.
     */
    fun retireCode() {
        if (!BuildConfig.TRIAL) newCode()
    }

    /** Adresse à ouvrir sur l'ordinateur : `http://<ip>:<port>/<code>` ; null sans réseau, sans port libre et dans l'appli d'essai. */
    fun link(): String? = if (BuildConfig.TRIAL) null else address()?.let { "$it/${code()}" }

    /** Au moins un installeur est complet sur la tablette. */
    suspend fun ready(context: Context): Boolean = withContext(Dispatchers.IO) { store(context).onDisk().isNotEmpty() }

    /** `http://<ip de la tablette>:<port écouté>`, null sans réseau ou sans port. */
    fun address(): String? {
        val port = port() ?: return null
        return Tablet.ipAddress()?.let { "http://$it:$port" }
    }

    /** Identité de l'ordinateur actif et installeur plus récent que son compagnon (null : à jour) ; null s'il est injoignable. */
    suspend fun check(context: Context): Pair<Identity, Installer?>? {
        if (BuildConfig.TRIAL) return null
        val identity = runCatching { BackendClient(context).pairing.identity() }.getOrNull() ?: return null
        val onDisk = withContext(Dispatchers.IO) { store(context).onDisk() }
        return identity to InstallerPlan.newerFor(identity.os, identity.version, onDisk)
    }

    /** Vérifie le manifeste de GitHub et met à jour les fichiers ; appelée à chaque vérification des mises à jour. */
    suspend fun sync(context: Context) {
        if (BuildConfig.TRIAL || Wifi.current(context) == null) return
        val api = UpdatesApi.server() ?: return
        if (!syncing.tryLock()) return
        try {
            val store = store(context)
            val announced = api.installers()
            val onDisk = withContext(Dispatchers.IO) { store.onDisk() }
            for (installer in InstallerPlan.toDownload(announced, onDisk)) download(api, store, installer)
            withContext(Dispatchers.IO) { store.remove(InstallerPlan.toDelete(announced, onDisk)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Manifeste absent (404), réseau coupé, disque plein : réessai à la prochaine vérification.
            Log.i(TAG, "Installeurs non mis à jour", e)
        } finally {
            _progress.value = null
            syncing.unlock()
        }
    }

    private suspend fun download(api: UpdatesApi, store: InstallerStore, installer: Installer) {
        val part = store.part(installer)
        try {
            if (part.parentFile!!.usableSpace < installer.size + FREE_MARGIN) {
                Log.w(TAG, "Pas assez de place pour ${installer.file}")
                return
            }
            val sha256 = api.downloadInstaller(installer, part) { _progress.value = it }
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
