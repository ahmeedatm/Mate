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
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.UserFacingException
import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Mises à jour installées par le Companion lui-même, sans personne (démo du « serveur Unowhy ») : moteur web
 * (WebView, figé en 81 par le kiosque) et le Companion. Device owner : Android installe sans demander de
 * confirmation. Sécurité : empreinte SHA-256 du fichier comparée à celle annoncée, puis Android refuse toute
 * mise à jour qui n'est pas signée par le même éditeur (Google pour le WebView).
 */
object Updater {

    /** Version installée d'un paquet, null s'il est absent. */
    fun installed(context: Context, pkg: String): PackageInfo? =
        try { context.packageManager.getPackageInfo(pkg, 0) } catch (e: PackageManager.NameNotFoundException) { null }

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Mises à jour annoncées, avec la version installée de chaque paquet. */
    suspend fun check(context: Context): List<Pair<AppUpdate, PackageInfo?>> =
        BackendClient(context).updates().map { it to installed(context, it.pkg) }

    fun isNewer(update: AppUpdate, info: PackageInfo?) = info == null || update.versionCode > versionCode(info)

    /**
     * Télécharge, vérifie et installe. `progress` : 0 à 100 pendant le téléchargement (hors du fil principal) ;
     * `committed` : le fichier est confié à Android, on ne peut plus annuler. Annulable (coroutine) jusque-là.
     */
    suspend fun install(context: Context, update: AppUpdate, progress: (Int) -> Unit, committed: () -> Unit = {}) {
        if (!Kiosk.isDeviceOwner(context)) throw UserFacingException(R.string.update_needs_kiosk)
        val app = context.applicationContext
        val file = File(app.cacheDir, "updates").apply { mkdirs() }.resolve("update.apk")
        try {
            // Le téléchargement est une boucle bloquante : l'annulation y est vérifiée à chaque pour cent.
            val job = currentCoroutineContext()[Job]
            val sha256 = BackendClient(app).downloadUpdate(update, file) { percent ->
                if (job?.isActive == false) throw CancellationException("Download cancelled")
                progress(percent)
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
