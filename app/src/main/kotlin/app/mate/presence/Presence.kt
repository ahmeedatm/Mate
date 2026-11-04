package app.mate.presence

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.device.Screen
import app.mate.presence.PresenceLogic.Change

/**
 * Veille à la présence (Réglages > Tablette, coupée par défaut) : la caméra avant ([PresenceCamera]) repère les
 * mouvements ; sans présence pendant [PresenceLogic.SLEEP_DELAY_MS], l'écran s'endort (voile noir par-dessus tout,
 * luminosité au minimum, voir Screen.presenceAsleep), puis se réveille à l'arrivée. La veille d'Android ne sert pas :
 * l'appli y perdrait la caméra, et l'écran de Mate reste allumé en permanence (FLAG_KEEP_SCREEN_ON).
 *
 * La caméra tourne seulement quand Mate est à l'écran ([resume] / [pause]), le réglage activé, la permission accordée
 * (jamais demandée d'ici : MainActivity.onPresenceChanged le fait) et le mode Caméra pas ouvert ([blocked] : une seule
 * caméra à la fois sur la 4.2). Indicateur obligatoire, la 4.2 n'a pas de voyant : [eyes] (barre du haut et voile)
 * visibles tant que la caméra tourne. [busy] : l'écran sert (appel, Focus…), pas de veille.
 */
class Presence(
    private val activity: MainActivity,
    private val veil: View,
    private val eyes: List<ImageView>,
    private val busy: () -> Boolean,
    private val blocked: () -> Boolean,
) {
    private val logic = PresenceLogic()
    private var camera: PresenceCamera? = null
    private var resumed = false

    fun resume() {
        resumed = true
        sync()
    }

    fun pause() {
        resumed = false
        sync()
    }

    /** À rappeler après un changement de réglage, de permission ou de mode. */
    fun sync() {
        val wanted = resumed && Prefs.presenceSleep(activity) && !blocked() &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (wanted && camera == null) start() else if (!wanted && camera != null) stop()
    }

    /** Toucher ou touche : repousse la veille. Vrai si l'écran dormait : le toucher le réveille et ne clique rien. */
    fun touched(): Boolean = camera != null && handle(logic.onTouch(SystemClock.elapsedRealtime()))

    private fun start() {
        logic.start(SystemClock.elapsedRealtime())
        camera = PresenceCamera(activity, { moved -> veil.post { onFrame(moved) } }, { veil.post { onLost() } }).also { it.open() }
        render()
    }

    private fun stop() {
        camera?.close()
        camera = null
        handle(logic.onLost(SystemClock.elapsedRealtime()))
        render()
    }

    private fun onFrame(moved: Boolean) {
        if (camera != null) handle(logic.onFrame(moved, busy(), SystemClock.elapsedRealtime()))
    }

    private fun onLost() {
        if (camera == null) return
        stop()
        activity.showBanner(activity.getString(R.string.presence_camera_lost), 6)
    }

    /** Applique le changement ; vrai si l'écran vient de se réveiller. */
    private fun handle(change: Change): Boolean {
        if (change == Change.NONE) return false
        val asleep = change == Change.SLEEP
        veil.visibility = if (asleep) View.VISIBLE else View.GONE
        Screen.presenceAsleep = asleep
        Screen.apply(activity)
        return !asleep
    }

    private fun render() = eyes.forEach { it.visibility = if (camera != null) View.VISIBLE else View.GONE }
}
