package com.unowhy.companion.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.MediaStore
import com.unowhy.companion.Prefs

/**
 * Mode kiosque. Ne fait rien tant que l'appli n'est pas device owner (voir docs/kiosque.md) :
 * aucun risque de bloquer la tablette pendant le développement.
 */
object Kiosk {

    /** Aucune appli ne s'installe ni ne se désinstalle, adb compris : le MDM SQOOL ne peut pas revenir par le réseau. */
    private val APP_RESTRICTIONS = listOf(
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_UNINSTALL_APPS,
    )

    /** Agent de provisionnement SQOOL (dans l'image : réinstalle le MDM) et MDM : masqués tant que l'appli est device owner. */
    private val HIDDEN_PACKAGES = listOf("com.unowhy.sqoolota", "com.hisqool.devicemanager")

    /**
     * Accordées d'office : en kiosque, Paramètres est inaccessible, un refus (« Ne plus demander ») serait sans retour.
     * La localisation est accordée par Wifi (exigée pour lister les réseaux).
     */
    private val GRANTED_PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    /** "Quitter le kiosque" : suspendu jusqu'au prochain démarrage de l'appli. */
    var suspended = false
        private set

    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(context: Context) = ComponentName(context, KioskAdminReceiver::class.java)
    private fun homeAlias(context: Context) = ComponentName(context.packageName, context.packageName + ".HomeAlias")

    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun isLocked(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /** À appeler dans onResume : verrouille l'appli à l'écran et en fait l'écran d'accueil. */
    fun enter(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        // Le blocage des installations reste actif même kiosque suspendu.
        lockApps(activity, !Prefs.allowInstalls(activity))
        hideSqool(activity, true)
        grantPermissions(activity)
        // Pas d'écran de verrouillage à faire glisser après une mise en veille (aucun code n'est défini).
        runCatching { dpm(activity).setKeyguardDisabled(admin(activity), true) }
        if (suspended) return
        val dpm = dpm(activity)
        // L'appli caméra du système doit être autorisée, sinon "Photo → Mac" est bloqué en lock task.
        val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(activity.packageManager)?.packageName
        dpm.setLockTaskPackages(admin(activity), listOfNotNull(activity.packageName, camera).toTypedArray())
        setHome(activity, true)
        if (!isLocked(activity)) activity.startLockTask()
    }

    fun exit(activity: Activity) {
        suspended = true
        if (isLocked(activity)) activity.stopLockTask()
    }

    /** Rend la main au système : plus de lock task, plus d'écran d'accueil, plus device owner. */
    fun remove(activity: Activity) {
        exit(activity)
        setHome(activity, false)
        lockApps(activity, false)
        hideSqool(activity, false)
        runCatching { dpm(activity).setKeyguardDisabled(admin(activity), false) }
        dpm(activity).clearDeviceOwnerApp(activity.packageName)
    }

    /** Bloque (ou permet) l'installation et la désinstallation d'applis. Sans effet si l'appli n'est pas device owner. */
    fun lockApps(context: Context, locked: Boolean) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        for (restriction in APP_RESTRICTIONS) {
            if (locked) dpm.addUserRestriction(admin(context), restriction)
            else dpm.clearUserRestriction(admin(context), restriction)
        }
    }

    private fun grantPermissions(context: Context) {
        val dpm = dpm(context)
        for (permission in GRANTED_PERMISSIONS) {
            runCatching {
                dpm.setPermissionGrantState(admin(context), context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
    }

    private fun hideSqool(context: Context, hidden: Boolean) {
        val dpm = dpm(context)
        for (pkg in HIDDEN_PACKAGES) {
            // Paquet absent : rien à masquer.
            runCatching { dpm.setApplicationHidden(admin(context), pkg, hidden) }
        }
    }

    private fun setHome(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(homeAlias(context), state, PackageManager.DONT_KILL_APP)
        val dpm = dpm(context)
        if (enabled) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin(context), filter, homeAlias(context))
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin(context), context.packageName)
        }
    }
}
