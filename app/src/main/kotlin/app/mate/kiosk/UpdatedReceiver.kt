package app.mate.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mate.MainActivity

/**
 * Appli mise à jour (adb, ou Réglages > Mises à jour) : Android l'a arrêtée, et le verrouillage du kiosque avec elle.
 * On la relance aussitôt pour que la tablette ne reste pas déverrouillée. Le device owner a le droit de démarrer
 * une activité en arrière-plan (Android 10) ; sans kiosque, on ne fait rien.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Kiosk.isDeviceOwner(context)) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
