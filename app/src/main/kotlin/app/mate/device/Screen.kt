package app.mate.device

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import app.mate.Prefs
import app.mate.frame.FrameLogic
import java.util.Calendar

/**
 * Luminosité de l'écran, réglée par la fenêtre de l'appli (toujours au premier plan en kiosque) : pas de permission,
 * pas de réglage système à modifier. La nuit (22 h – 7 h), l'écran toujours allumé est atténué ; un toucher le
 * rallume pour [WAKE_MS]. En mode automatique, le capteur de lumière de la pièce règle le niveau (voir
 * AutoBrightness) ; la veille de nuit reste prioritaire. Pendant le cadre photo, la luminosité est plafonnée (voir FrameLogic.level).
 */
object Screen {

    private const val NIGHT_LEVEL = 0.02f
    private const val WAKE_MS = 2 * 60_000L
    const val MIN_LEVEL = 0.05f

    private var lastTouch = 0L

    /** Dernier niveau déduit du capteur de lumière ; null tant qu'il n'a rien mesuré. */
    private var ambientLevel: Float? = null
    private var ambientListener: SensorEventListener? = null

    /** Cadre photo affiché : null sinon ; sa valeur dit si la tablette est branchée (plafond plus bas sur batterie). */
    var frameCharging: Boolean? = null

    fun isNight(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 22 || it < 7 }

    private fun dimmed(activity: Activity) =
        Prefs.nightDim(activity) && isNight() && SystemClock.elapsedRealtime() - lastTouch > WAKE_MS

    /** À appeler au démarrage, chaque minute et après un changement de réglage. */
    fun apply(activity: Activity) {
        val window = activity.window
        val params = window.attributes
        val chosen = if (Prefs.autoBrightness(activity)) ambientLevel ?: Prefs.brightness(activity) else Prefs.brightness(activity)
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

    /** Écoute la lumière de la pièce tant que l'écran est au premier plan (à appeler dans onResume) ; sans effet si le mode est coupé. */
    fun startAmbient(activity: Activity) {
        stopAmbient(activity)
        if (!Prefs.autoBrightness(activity)) return
        val manager = activity.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val target = AutoBrightness.level(event.values[0])
                if (ambientLevel?.let { AutoBrightness.worthChange(it, target) } == false) return
                ambientLevel = target
                apply(activity)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        ambientListener = listener
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** À appeler dans onPause et quand le mode est coupé. */
    fun stopAmbient(activity: Activity) {
        ambientListener?.let { activity.getSystemService(SensorManager::class.java)?.unregisterListener(it) }
        ambientListener = null
        ambientLevel = null
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
