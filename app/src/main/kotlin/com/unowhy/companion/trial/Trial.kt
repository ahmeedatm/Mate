package com.unowhy.companion.trial

import android.app.Activity
import android.content.Context
import android.util.Log
import com.unowhy.companion.BuildConfig
import com.unowhy.companion.Computers
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.kiosk.Updater

/**
 * Appli d'essai d'une MR (`com.unowhy.companion.essai`, version X.Y.Z-essai.N), installée à côté de la stable.
 * La stable reste device owner : elle installe l'essai (Réglages > Mises à jour), l'autorise en kiosque
 * ([Kiosk.enter]) et l'ouvre. L'essai reprend les ordinateurs appairés de la stable et lui rend la main.
 */
object Trial {
    const val STABLE = "com.unowhy.companion"
    const val TRIAL = "$STABLE.essai"

    /** L'autre appli : l'essai vu de la stable, la stable vue de l'essai. */
    val other: String get() = if (BuildConfig.TRIAL) STABLE else TRIAL

    fun isInstalled(context: Context, pkg: String) = Updater.installed(context, pkg) != null

    /**
     * Passe à l'autre appli. L'essai se ferme en rendant la main : 2 Go de RAM, pas deux Companion ouverts pour rien.
     * La stable reste derrière (accueil du kiosque).
     */
    fun switch(activity: Activity): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(other) ?: return false
        // Essai installé après le dernier passage en kiosque : micro et caméra accordés avant qu'il les demande.
        if (!BuildConfig.TRIAL) Kiosk.grantPermissions(activity, TRIAL)
        activity.startActivity(intent)
        if (BuildConfig.TRIAL) activity.finishAndRemoveTask()
        return true
    }

    /**
     * Essai : ordinateurs de la stable repris tant qu'il n'en a aucun, sans réappairer. Lus par son ContentProvider,
     * protégé par une permission signature : refusé (SecurityException) si les deux applis n'ont pas la même clé.
     */
    fun importComputers(context: Context): Boolean {
        if (!BuildConfig.TRIAL || Computers.all(context).isNotEmpty()) return false
        return try {
            val raw = context.contentResolver.query(ComputersProvider.uri(STABLE), null, null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            raw != null && Computers.importIfEmpty(context, raw)
        } catch (e: Exception) {
            // Stable absente, autre clé, réponse illisible : l'essai s'appaire comme une tablette neuve.
            Log.w(TAG, "Ordinateurs de la stable illisibles", e)
            false
        }
    }

    private const val TAG = "Trial"
}
