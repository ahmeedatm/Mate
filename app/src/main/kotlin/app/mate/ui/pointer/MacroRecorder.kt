package app.mate.ui.pointer

import app.mate.net.PointerGesture
import app.mate.ui.keyJson
import org.json.JSONObject

/** Plafond d'étapes d'une touche (validation du compagnon, `keySchema`) et d'une pause (`wait`). */
private const val MAX_STEPS = 20
private const val MAX_WAIT_MS = 10_000L

/** Pause arrondie à ce pas ; en dessous du minimum, deux gestes de suite sans pause (un enchaînement, pas une attente voulue). */
private const val WAIT_STEP_MS = 100L
private const val MIN_WAIT_MS = 200L

/**
 * Enregistre les gestes faits sur le Pavé avec la pause réelle entre deux, pour en faire une touche Perso : une étape
 * `gesture` par geste, une étape `wait` entre deux quand la pause compte.
 */
class MacroRecorder {
    private val steps = mutableListOf<JSONObject>()
    private var lastAt: Long? = null

    var gestures = 0
        private set

    val isEmpty get() = gestures == 0

    /** Note le geste fait à l'instant [atMs] (horloge monotone). Faux, sans rien noter, si la touche dépasserait 20 étapes. */
    fun add(gesture: PointerGesture, atMs: Long): Boolean {
        val pause = lastAt?.let { ((atMs - it).coerceAtMost(MAX_WAIT_MS) / WAIT_STEP_MS) * WAIT_STEP_MS }?.takeIf { it >= MIN_WAIT_MS }
        if (steps.size + (if (pause != null) 2 else 1) > MAX_STEPS) return false
        if (pause != null) steps += JSONObject().put("type", "wait").put("ms", pause)
        steps += JSONObject().put("type", "gesture").put("name", gesture.wire)
        lastAt = atMs
        gestures++
        return true
    }

    fun toKey(label: String): JSONObject = keyJson(label, steps.toList(), glyph = null, color = null, cycle = false)
}
