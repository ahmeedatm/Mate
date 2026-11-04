package app.mate.presence

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.device.Screen
import app.mate.presence.PresenceLogic.Change

/**
 * Veille à la présence (Réglages > Tablette, coupée par défaut) : la caméra avant ([PresenceCamera]) repère les
 * mouvements ; sans présence pendant [PresenceLogic.SLEEP_DELAY_MS], l'écran s'endort (voile noir par-dessus tout, grain
 * et motif du thème retirés dessous, luminosité au minimum, voir Screen.presenceAsleep), puis se réveille à l'arrivée. La
 * veille d'Android ne sert pas : l'appli y perdrait la caméra, et l'écran de Mate reste allumé en permanence
 * (FLAG_KEEP_SCREEN_ON).
 *
 * La caméra tourne seulement quand Mate est à l'écran ([resume] / [pause]), le réglage activé, la permission accordée
 * (demandée par MainActivity.onPresenceChanged ; retirée, le réglage se décoche) et rien ne cache les voyants
 * ([blocked] : mode Caméra, une seule caméra à la fois, et verrou par code, une fenêtre à part). Indicateur obligatoire,
 * la 4.2 n'a pas de voyant : [topEye] dans la barre du haut, et un voyant par-dessus tout ([overEye]) pendant la veille
 * et le cadre photo. [busy] : l'écran sert (appel, Focus…), pas de veille. [frameShowing] : le cadre photo est affiché.
 *
 * La fermeture de la caméra est asynchrone : une nouvelle ne s'ouvre, et le mode Caméra n'ouvre la sienne
 * ([whenCameraFree]), qu'une fois la précédente vraiment fermée ; les voyants restent jusque-là. Une caméra tombée n'est
 * pas relancée tout de suite (une minute, [PresenceLogic.RETRY_MS]), sinon elle bouclerait.
 */
class Presence(
    private val activity: MainActivity,
    private val topEye: ImageView,
    private val busy: () -> Boolean,
    private val blocked: () -> Boolean,
    private val frameShowing: () -> Boolean,
) {
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private val veil: View
    private val overEye: ImageView
    private val logic = PresenceLogic()
    private var camera: PresenceCamera? = null

    /** Numéro de la caméra en cours : les rappels d'une caméra arrêtée ou remplacée sont ignorés. */
    private var generation = 0
    private var resumed = false
    private var closing = false
    private val waiting = mutableListOf<() -> Unit>()

    /** Grain ou motif du thème (avant-plan de l'écran), mis de côté pendant la veille. */
    private var themeForeground: Drawable? = null

    init {
        activity.layoutInflater.inflate(R.layout.view_presence, content, true)
        veil = content.findViewById(R.id.presence_veil)
        overEye = content.findViewById(R.id.presence_eye)
        // Activation d'accessibilité (TalkBack) : ne passe pas par dispatchTouchEvent.
        veil.setOnClickListener { touched() }
    }

    fun resume() {
        resumed = true
        sync()
    }

    fun pause() {
        resumed = false
        sync()
    }

    /** À rappeler après un changement de réglage, de permission ou de mode, et chaque minute. */
    fun sync() {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        // Permission retirée dans les réglages d'Android : la veille se coupe, l'interrupteur aussi.
        if (resumed && Prefs.presenceSleep(activity) && !granted) activity.dropPresence(message = false)
        val wanted = resumed && Prefs.presenceSleep(activity) && granted && !blocked()
        if (wanted && camera == null && !closing && logic.canStart(SystemClock.elapsedRealtime())) {
            start()
        } else if (!wanted && camera != null) {
            stop(restart = true)
        }
    }

    /** Cadre photo ouvert ou fermé : le voyant du dessus suit. */
    fun render() {
        // Les voyants restent tant que la caméra n'est pas vraiment fermée.
        val on = camera != null || closing
        topEye.visibility = if (on) View.VISIBLE else View.GONE
        overEye.visibility = if (on && (logic.asleep || frameShowing())) View.VISIBLE else View.GONE
    }

    /** Toucher ou touche : repousse la veille. Vrai si l'écran dormait : le toucher le réveille et ne clique rien. */
    fun touched(): Boolean = camera != null && handle(logic.onTouch(SystemClock.elapsedRealtime()))

    /** Lance [block] quand plus aucune caméra de veille n'est en train de se fermer (tout de suite si aucune ne l'est). */
    fun whenCameraFree(block: () -> Unit) {
        if (closing) waiting.add(block) else block()
    }

    val isClosing get() = closing

    /** Le mode Caméra finit de fermer la sienne : pas de caméra de veille avant [ms], puis on regarde si elle doit repartir. */
    fun holdOff(ms: Long) {
        logic.holdOff(SystemClock.elapsedRealtime(), ms)
        veil.postDelayed({ sync() }, ms)
    }

    private fun start() {
        logic.start(SystemClock.elapsedRealtime())
        val mine = ++generation
        camera = PresenceCamera(
            activity.applicationContext,
            { moved -> veil.post { if (mine == generation) onFrame(moved) } },
            { veil.post { if (mine == generation) onLost() } },
        ).also { it.open() }
        render()
    }

    /** [restart] : arrêt voulu, la caméra repart à la fermeture si elle est de nouveau demandée ; faux après une panne. */
    private fun stop(restart: Boolean) {
        val old = camera ?: return
        camera = null
        generation++
        closing = true
        old.close {
            veil.post {
                closing = false
                val blocks = waiting.toList()
                waiting.clear()
                blocks.forEach { it() }
                render()
                if (restart) sync()
            }
        }
        handle(logic.onLost(SystemClock.elapsedRealtime()))
    }

    private fun onFrame(moved: Boolean) {
        if (camera == null) return
        // Un voyant caché (verrou, mode Caméra) : la caméra ne tourne pas sans lui.
        if (blocked()) return stop(restart = true)
        handle(logic.onFrame(moved, busy(), SystemClock.elapsedRealtime()))
    }

    private fun onLost() {
        stop(restart = false)
        if (logic.onCameraFailed(SystemClock.elapsedRealtime())) activity.showBanner(activity.getString(R.string.presence_camera_lost), 6)
    }

    /** Applique le changement ; vrai si l'écran vient de se réveiller. */
    private fun handle(change: Change): Boolean {
        if (change == Change.NONE) return false
        val asleep = change == Change.SLEEP
        veil.visibility = if (asleep) View.VISIBLE else View.GONE
        if (asleep) {
            themeForeground = content.foreground
            content.foreground = null
        } else {
            content.foreground = themeForeground
            themeForeground = null
        }
        Screen.presenceAsleep = asleep
        Screen.apply(activity)
        render()
        return !asleep
    }
}
