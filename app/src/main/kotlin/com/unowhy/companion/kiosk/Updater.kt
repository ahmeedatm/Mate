package com.unowhy.companion.kiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.unowhy.companion.Computers
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.UserFacingException
import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.net.UpdateSource
import com.unowhy.companion.net.UpdatesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mises à jour installées par le Companion lui-même, sans personne : moteur web (WebView, figé en 81 par le kiosque)
 * et le Companion. Deux sources : l'ordinateur relié (« serveur Unowhy » simulé) et le serveur central (GitLab Pages,
 * `BuildConfig.UPDATE_URL`), qui marche sans ordinateur. Device owner : Android installe sans demander de
 * confirmation. Sécurité : empreinte SHA-256 du fichier comparée à celle annoncée, puis Android refuse toute
 * mise à jour qui n'est pas signée par le même éditeur (Google pour le WebView).
 */
object Updater {

    /** Version installée d'un paquet, null s'il est absent. */
    fun installed(context: Context, pkg: String): PackageInfo? =
        try { context.packageManager.getPackageInfo(pkg, 0) } catch (e: PackageManager.NameNotFoundException) { null }

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Résultat d'une recherche : annonces fusionnées (voir [UpdatePlan.merge]), sources interrogées et sources en erreur. */
    class Check(val updates: List<Pair<AppUpdate, PackageInfo?>>, val attempted: Set<UpdateSource>, val errors: Map<UpdateSource, Exception>)

    /** Une seule installation à la fois : bouton de Réglages et [AutoUpdater]. */
    private val busy = AtomicBoolean(false)

    /** Interroge les deux sources en parallèle ; l'ordinateur seulement s'il y en a un d'appairé. */
    suspend fun check(context: Context): Check = coroutineScope {
        val computer = if (Computers.all(context).isEmpty()) null else async { fetch { BackendClient(context).updates.list() } }
        val server = UpdatesApi.server()?.let { api -> async { fetch { serverList(api) } } }
        val results = mapOf(UpdateSource.COMPUTER to computer?.await(), UpdateSource.SERVER to server?.await())
        val merged = UpdatePlan.merge(*results.values.map { it?.getOrNull().orEmpty() }.toTypedArray())
        Check(
            merged.map { it to installed(context, it.pkg) },
            results.mapNotNull { (source, r) -> if (r != null) source else null }.toSet(),
            results.mapNotNull { (source, r) -> r?.exceptionOrNull()?.let { source to it as Exception } }.toMap(),
        )
    }

    private suspend fun fetch(block: suspend () -> List<AppUpdate>): Result<List<AppUpdate>> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    /**
     * Serveur central. Rien de publié (404) ou Pages privées (redirection vers la page de connexion GitLab, donc du
     * HTML) : aucune mise à jour, pas une erreur. Réseau absent : phrase propre au serveur (errorText parlerait de l'ordinateur).
     */
    private suspend fun serverList(api: UpdatesApi): List<AppUpdate> = try {
        api.list()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code in 300..499) emptyList<AppUpdate>().also { Log.i("Updater", "Serveur central : rien de publié (${e.code})") } else throw serverError(e)
    } catch (e: JSONException) {
        Log.i("Updater", "Serveur central : pas de manifest lisible", e)
        emptyList()
    } catch (e: Exception) {
        throw serverError(e)
    }

    private fun serverError(e: Exception): UserFacingException {
        Log.w("Updater", "Serveur central injoignable", e)
        return UserFacingException(R.string.update_server_unreachable)
    }

    private fun api(context: Context, source: UpdateSource): UpdatesApi = when (source) {
        UpdateSource.COMPUTER -> BackendClient(context).updates
        UpdateSource.SERVER -> UpdatesApi.server() ?: throw UserFacingException(R.string.update_server_unreachable)
    }

    /**
     * Télécharge, vérifie et installe. `progress` : 0 à 100 pendant le téléchargement (hors du fil principal) ;
     * `committed` : le fichier est confié à Android, on ne peut plus annuler. Annulable (coroutine) jusque-là.
     */
    suspend fun install(context: Context, update: AppUpdate, progress: (Int) -> Unit, committed: () -> Unit = {}) {
        if (!Kiosk.isDeviceOwner(context)) throw UserFacingException(R.string.update_needs_kiosk)
        if (!busy.compareAndSet(false, true)) throw UserFacingException(R.string.update_busy)
        try {
            download(context.applicationContext, update, progress, committed)
        } finally {
            busy.set(false)
        }
    }

    private suspend fun download(app: Context, update: AppUpdate, progress: (Int) -> Unit, committed: () -> Unit) {
        val file = File(app.cacheDir, "updates").apply { mkdirs() }.resolve("update.apk")
        try {
            // Le téléchargement est une boucle bloquante : l'annulation y est vérifiée à chaque pour cent.
            val job = currentCoroutineContext()[Job]
            val sha256 = try {
                api(app, update.source).download(update, file) { percent ->
                    if (job?.isActive == false) throw CancellationException("Download cancelled")
                    progress(percent)
                }
            } catch (e: IOException) {
                // Le serveur central répond en HTML (page d'erreur GitLab) : jamais affiché tel quel.
                if (update.source != UpdateSource.SERVER) throw e
                Log.w("Updater", "Téléchargement depuis le serveur central impossible", e)
                throw UserFacingException(R.string.update_server_download_failed)
            }
            if (!sha256.equals(update.sha256, ignoreCase = true)) throw UserFacingException(R.string.update_file_corrupt)
            // Le blocage des installations s'applique aussi au device owner : levé le temps de l'installation.
            Kiosk.lockApps(app, false)
            try {
                installApk(app, file, update, committed)
            } finally {
                Kiosk.lockApps(app, !Prefs.allowInstalls(app))
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun installApk(context: Context, file: File, update: AppUpdate, committed: () -> Unit) {
        val pkg = update.pkg
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        val action = "${context.packageName}.INSTALL_RESULT_$id"
        val result = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { result.complete(intent) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            try {
                withContext(Dispatchers.IO) {
                    installer.openSession(id).use { session ->
                        session.openWrite("update.apk", 0, file.length()).use { out ->
                            file.inputStream().use { it.copyTo(out) }
                            session.fsync(out)
                        }
                        // Mutable : l'installateur y ajoute le résultat.
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), flags)
                        currentCoroutineContext()[Job]?.let { if (!it.isActive) throw CancellationException("Install cancelled") }
                        session.commit(pending.intentSender)
                    }
                }
            } catch (e: Exception) {
                // Annulé ou échoué avant d'être confié à Android : la session est jetée.
                runCatching { installer.abandonSession(id) }
                throw e
            }
            committed()
            // Android installe une appli à la fois (le Play Store peut passer avant) et prépare le code : long
            // sur cette tablette. Sans réponse au bout de 15 min, on regarde la version réellement installée.
            val intent = withTimeoutOrNull(15 * 60_000L) { result.await() }
            if (intent == null) {
                val info = installed(context, pkg)
                if (info != null && versionCode(info) >= update.versionCode) return
                throw UserFacingException(R.string.update_unfinished)
            }
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_SUCCESS -> Unit
                PackageInstaller.STATUS_PENDING_USER_ACTION -> throw UserFacingException(R.string.update_needs_confirmation)
                else -> {
                    Log.w("Updater", "Installation refusée ($status) : ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    throw UserFacingException(R.string.update_install_failed, status)
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
