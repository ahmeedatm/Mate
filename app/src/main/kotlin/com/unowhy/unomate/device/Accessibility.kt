package com.unowhy.unomate.device

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Accessibilité malgré le kiosque (COMP-50) : en verrouillage d'écran, Paramètres est inaccessible, donc ni TalkBack ni
 * la taille du texte ne se règlent. TalkBack se lance par le raccourci d'Android (maintenir les deux touches de volume
 * 3 secondes, utilisable sans voir l'écran, kiosque compris) ou par Réglages > Accessibilité ; la taille du texte est
 * propre à l'appli (Prefs.textScale, appliquée par MainActivity).
 *
 * Écrire les réglages d'accessibilité demande WRITE_SECURE_SETTINGS, qu'un device owner ne peut pas s'accorder : il se
 * donne par adb à l'installation du kiosque (docs/kiosque.md). Sans lui, la carte le dit et rien n'est écrit.
 */
object Accessibility {
    private const val TALKBACK_PACKAGE = "com.google.android.marvin.talkback"
    val TALKBACK: ComponentName = ComponentName(TALKBACK_PACKAGE, "$TALKBACK_PACKAGE.TalkBackService")

    /** Réglages cachés d'Android 10 (pas de constante publique) : cible du raccourci et ses confirmations. */
    private const val SHORTCUT_TARGET = "accessibility_shortcut_target_service"
    private const val SHORTCUT_DIALOG_SHOWN = "accessibility_shortcut_dialog_shown"
    private const val SHORTCUT_ON_LOCK_SCREEN = "accessibility_shortcut_on_lock_screen"

    fun canConfigure(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Vitesse des animations d'Android réglée d'après l'appli : 1 (tout joue) ou 0 avec Réduire les animations.
     * L'installation du kiosque la met à 0 (docs/kiosque.md) ; c'est l'appli qui décide ensuite, famille par famille
     * (Réglages > Animations). Sans WRITE_SECURE_SETTINGS : rien n'est écrit.
     */
    fun applyAnimationScale(context: Context, reduce: Boolean) {
        if (!canConfigure(context)) return
        val want = if (reduce) 0f else 1f
        val resolver = context.contentResolver
        if (Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != want) {
            runCatching { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, want) }
        }
    }

    fun talkBackInstalled(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(TALKBACK_PACKAGE, 0) }.isSuccess

    fun talkBackOn(context: Context): Boolean = TALKBACK in enabledServices(context)

    /** Active ou coupe TalkBack ; false si la permission manque (rien n'est écrit). */
    fun setTalkBack(context: Context, on: Boolean): Boolean {
        if (!canConfigure(context)) return false
        val services = enabledServices(context).filter { it != TALKBACK } + listOfNotNull(TALKBACK.takeIf { on })
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":") { it.flattenToString() })
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (services.isEmpty()) 0 else 1)
        return true
    }

    /**
     * Raccourci des touches de volume réglé sur TalkBack, sans la boîte de première utilisation (elle demande de lire
     * l'écran) et utilisable écran verrouillé. Sans effet sans la permission ou sans TalkBack ; ne remplace pas un
     * raccourci déjà choisi par quelqu'un.
     */
    fun prepareShortcut(context: Context) {
        if (!canConfigure(context) || !talkBackInstalled(context)) return
        val resolver = context.contentResolver
        if (Settings.Secure.getString(resolver, SHORTCUT_TARGET).isNullOrEmpty()) {
            Settings.Secure.putString(resolver, SHORTCUT_TARGET, TALKBACK.flattenToString())
        }
        Settings.Secure.putInt(resolver, SHORTCUT_DIALOG_SHOWN, 1)
        Settings.Secure.putInt(resolver, SHORTCUT_ON_LOCK_SCREEN, 1)
    }

    /** Services de la liste d'Android, forme courte (« pkg/.Service ») comprise. */
    private fun enabledServices(context: Context): List<ComponentName> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
}
