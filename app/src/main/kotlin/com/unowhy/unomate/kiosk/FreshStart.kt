package com.unowhy.unomate.kiosk

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import com.unowhy.unomate.BuildConfig
import com.unowhy.unomate.MainActivity
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.voice.WakeModel
import java.io.File

/**
 * « Remettre à neuf » (carte Kiosque, code technicien) : efface ce que l'appli a gardé, comme pour un nouvel utilisateur
 * (ordinateurs appairés, thème, écrans, réglages, eau, photos du cadre, cache, cookies de la page web), puis la relance
 * sur l'écran d'accueil d'une tablette neuve. Restent le kiosque (device owner, tenu par Android, et les réglages de la
 * carte Kiosque), le WiFi d'Android et ce qui n'est pas à l'utilisateur : installeurs gardés pour les ordinateurs, modèle
 * du mot de réveil. `pm clear` ne convient pas : Android protège les données du device owner.
 */
object FreshStart {

    /** Dossiers de `filesDir` gardés : téléchargés pour la tablette, pas des données de l'utilisateur. */
    val KEPT_FILES = setOf("installers", WakeModel.NAME)

    /** Dossiers du dossier de l'appli vidés en entier (cache, bases, données du WebView). */
    private val WIPED_DIRS = listOf("cache", "code_cache", "databases", "app_webview")

    /** Ce qui reste du fichier de préférences `file` : les seules clés du technicien, dans « companion ». */
    fun kept(file: String, values: Map<String, *>): Map<String, *> =
        if (file == Prefs.FILE) values.filterKeys { it in Prefs.KIOSK_KEYS } else emptyMap<String, Any?>()

    /** Entrées de `filesDir` à effacer. */
    fun filesToWipe(names: List<String>): List<String> = names.filter { it !in KEPT_FILES }

    /** Efface tout, puis relance l'appli dans un nouveau processus (aucun état en mémoire ne survit). */
    fun run(activity: Activity) {
        wipe(activity)
        // Essai : sans ça, il reprendrait au redémarrage les ordinateurs de la stable et sauterait le premier lancement.
        if (BuildConfig.TRIAL) Prefs.markTrialFresh(activity)
        val intent = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(activity, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        activity.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_MS, pending)
        // L'appli est aussi l'accueil du kiosque : Android la relancerait de toute façon.
        Process.killProcess(Process.myPid())
    }

    private fun wipe(context: Context) {
        val data = File(context.applicationInfo.dataDir)
        File(data, "shared_prefs").listFiles()?.forEach { xml ->
            val name = xml.name.removeSuffix(".xml")
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val keep = kept(name, prefs.all)
            // commit : écrit avant l'arrêt du processus, sans attendre.
            prefs.edit().clear().apply {
                for ((key, value) in keep) if (value is Boolean) putBoolean(key, value)
            }.commit()
        }
        val files = context.filesDir
        filesToWipe(files.list()?.toList().orEmpty()).forEach { File(files, it).deleteRecursively() }
        for (dir in WIPED_DIRS) File(data, dir).listFiles()?.forEach { it.deleteRecursively() }
    }

    private const val RESTART_MS = 800L
}
