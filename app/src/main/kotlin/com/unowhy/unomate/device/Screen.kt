package com.unowhy.unomate.device

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.frame.FrameLogic
import java.util.Calendar

/**
 * Luminosité de l'écran, réglée par la fenêtre de l'appli (toujours au premier plan en kiosque) : pas de permission,
 * pas de réglage système à modifier. La nuit (22 h – 7 h), l'écran toujours allumé est atténué ; un toucher le
 * rallume pour [WAKE_MS]. Pendant le cadre photo, la luminosité est plafonnée (voir FrameLogic.level).
 */
object Screen {

    private const val NIGHT_LEVEL = 0.02f
    private const val WAKE_MS = 2 * 60_000L
    const val MIN_LEVEL = 0.05f

    private var lastTouch = 0L

    /** Cadre photo affiché : null sinon ; sa valeur dit si la tablette est branchée (plafond plus bas sur batterie). */
    var frameCharging: Boolean? = null

    fun isNight(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 22 || it < 7 }

    private fun dimmed(activity: Activity) =
        Prefs.nightDim(activity) && isNight() && SystemClock.elapsedRealtime() - lastTouch > WAKE_MS

    /** À appeler au démarrage, chaque minute et après un changement de réglage. */
    fun apply(activity: Activity) {
        val window = activity.window
        val params = window.attributes
        val chosen = Prefs.brightness(activity)
        val charging = frameCharging
        val target = when {
            dimmed(activity) -> NIGHT_LEVEL
            charging != null -> FrameLogic.level(if (chosen < 0) systemLevel(activity) else chosen, charging)
            chosen < 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> chosen.coerceIn(MIN_LEVEL, 1f)
        }
        if (params.screenBrightness != target) {
            params.screenBrightness = target
            window.attributes = params
        }
    }

    /** Sens de l'écran : paysage fixe, ou les deux sens de paysage selon le capteur (le choix du manifeste). */
    fun orientation(locked: Boolean): Int =
        if (locked) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    /** À appeler au démarrage et après un changement de réglage : la configuration ne recrée pas l'activité. */
    fun applyOrientation(activity: Activity) {
        val target = orientation(Prefs.landscapeLocked(activity))
        if (activity.requestedOrientation != target) activity.requestedOrientation = target
    }

    /** Luminosité du système, de 0 à 1 (réglage de 0 à 255). */
    private fun systemLevel(activity: Activity) =
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    /** Toucher l'écran : rallume s'il était atténué. */
    fun touched(activity: Activity) {
        val wasDimmed = dimmed(activity)
        lastTouch = SystemClock.elapsedRealtime()
        if (wasDimmed) apply(activity)
    }
}
