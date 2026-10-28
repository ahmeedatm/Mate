package com.unowhy.companion.ui.pointer

import com.unowhy.companion.net.PointerButton
import com.unowhy.companion.net.PointerChannel
import com.unowhy.companion.net.PointerGesture
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Ce que le pavé demande à l'ordinateur. */
sealed class PadCommand {
    data class Move(val dx: Int, val dy: Int) : PadCommand()
    data class Scroll(val sx: Int, val sy: Int) : PadCommand()
    data class Click(val button: PointerButton) : PadCommand()
    data class Button(val button: PointerButton, val down: Boolean) : PadCommand()
    data class Gesture(val gesture: PointerGesture) : PadCommand()
}

/** Branche une commande du pavé sur le canal d'envoi. */
fun PointerChannel.send(command: PadCommand) = when (command) {
    is PadCommand.Move -> move(command.dx, command.dy)
    is PadCommand.Scroll -> scroll(command.sx, command.sy)
    is PadCommand.Click -> click(command.button)
    is PadCommand.Button -> button(command.button, command.down)
    is PadCommand.Gesture -> gesture(command.gesture)
}

enum class TouchAction { DOWN, MOVE, UP, CANCEL }

/** Tape : durée maximale (ms) et déplacement maximal (px) pour qu'un contact reste un clic. */
const val TAP_MAX_MS = 300L
const val TAP_SLOP_PX = 20f

/** Double tape puis glisser : délai maximal (ms) entre la fin de la première tape et le retour du doigt. */
const val DOUBLE_TAP_MS = 300L

/** Accélération : vitesse (px/ms) sous laquelle le déplacement est 1:1, et vitesse où le gain maximal est atteint. */
const val SLOW_SPEED = 0.3f
const val FAST_SPEED = 3f
const val MIN_GAIN = 1f
const val MAX_GAIN = 4f

/** Défilement à deux doigts : pixels parcourus pour un cran de molette. */
const val PX_PER_NOTCH = 60f

/** Pincement : écart entre les deux doigts qui verrouille le zoom (10 %) puis pas d'un cran de zoom (12 %). */
const val PINCH_LOCK_RATIO = 0.10f
const val PINCH_STEP_RATIO = 0.12f

/** Balayage rapide à deux doigts : durée max (ms), distance min (part de la largeur du pavé), rapport horizontal/vertical. */
const val FLICK_MAX_MS = 350L
const val FLICK_MIN_FRAC = 0.25f
const val FLICK_AXIS_RATIO = 2f

/** 3 et 4 doigts : déplacement du centre (part de la largeur du pavé) qui déclenche le geste. */
const val MULTI_SWIPE_FRAC = 0.10f

/** Gain d'un déplacement selon la vitesse du doigt : constant quand il est lent, croissant puis plafonné. */
fun padGain(speedPxPerMs: Float): Float {
    val t = ((speedPxPerMs - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED)).coerceIn(0f, 1f)
    return MIN_GAIN + (MAX_GAIN - MIN_GAIN) * t
}

/**
 * Reconnaissance des gestes du pavé, sans dépendance à Android : on lui passe les événements tactiles, il émet des
 * [PadCommand]. `onTouch(action, pointerCount, x, y, timeMs)` : `pointerCount` = doigts posés APRÈS l'événement (0 quand
 * le dernier se lève), (x, y) = centre des doigts posés, `timeMs` = horloge en ms.
 *
 * 1 doigt : déplacement accéléré ; tape courte = clic gauche. 2 doigts : défilement « naturel » (le contenu suit les
 * doigts : doigts vers le haut = sy < 0, vers la gauche = sx > 0) ; tape courte = clic droit. Double tape puis doigt
 * maintenu et glissé : bouton gauche enfoncé pendant le glissement, relâché au retrait du doigt.
 *
 * `spread` = distance entre les deux doigts (utile à 2 doigts seulement). Gestes de pavé moderne (un par contact pour 3
 * et 4 doigts) : pincement (zoom-in/out, verrouillé jusqu'à la levée), balayage rapide à 2 doigts (doigts vers la
 * DROITE = back, vers la GAUCHE = forward, comme on tourne une page), 3 doigts haut/bas/gauche/droite =
 * task-view/desktop/app-previous/app-next, 4 doigts gauche/droite = desktop-previous/desktop-next, tape à 3 doigts =
 * search, à 4 = notifications. `padWidthPx` donne la largeur du pavé, base des seuils en proportion.
 */
class PadGestures(
    private val emit: (PadCommand) -> Unit,
    private val slopPx: Float = TAP_SLOP_PX,
    private val pxPerNotch: Float = PX_PER_NOTCH,
    private val padWidthPx: () -> Float = { DEFAULT_PAD_WIDTH },
) {
    private var count = 0
    private var maxCount = 0
    private var startTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var moved = false
    private var lastTapUp = NONE
    private var dragArmed = false
    private var dragging = false
    private var remMoveX = 0f
    private var remMoveY = 0f
    private var remScrollX = 0f
    private var remScrollY = 0f

    // Deux doigts : départ du geste (centre, écart, heure), balayage encore possible, pincement verrouillé.
    private var twoX = 0f
    private var twoY = 0f
    private var twoTime = 0L
    private var spread0 = 0f
    private var zoomRef = 0f
    private var flickDead = false
    private var pinchLocked = false

    // 3 et 4 doigts : centre de départ, geste déjà émis pour ce contact.
    private var multiX = 0f
    private var multiY = 0f
    private var fired = false

    fun onTouch(action: TouchAction, pointerCount: Int, x: Float, y: Float, timeMs: Long, spread: Float = 0f) {
        when (action) {
            TouchAction.DOWN -> down(pointerCount, x, y, timeMs, spread)
            TouchAction.MOVE -> move(pointerCount, x, y, timeMs, spread)
            TouchAction.UP -> up(pointerCount, x, y, timeMs)
            TouchAction.CANCEL -> cancel()
        }
    }

    private fun down(n: Int, x: Float, y: Float, t: Long, spread: Float) {
        if (count == 0) {
            maxCount = n
            startTime = t
            moved = false
            dragging = false
            dragArmed = lastTapUp != NONE && t - lastTapUp <= DOUBLE_TAP_MS
            remMoveX = 0f
            remMoveY = 0f
            remScrollX = 0f
            remScrollY = 0f
            fired = false
        } else {
            maxCount = max(maxCount, n)
            dragArmed = false
        }
        count = n
        resetReference(x, y, t)
        if (n == 2 && maxCount < 3) {
            twoX = x
            twoY = y
            twoTime = t
            spread0 = spread
            zoomRef = spread
            flickDead = false
            pinchLocked = false
        }
        if (n >= 3) {
            multiX = x
            multiY = y
        }
    }

    /** Le centre saute quand le nombre de doigts change : repartir de là sans compter le saut comme un déplacement. */
    private fun resetReference(x: Float, y: Float, t: Long) {
        lastX = x
        lastY = y
        lastTime = t
        if (!moved) {
            startX = x
            startY = y
        }
    }

    private fun move(n: Int, x: Float, y: Float, t: Long, spread: Float) {
        if (count == 0) return
        count = n
        val dx = x - lastX
        val dy = y - lastY
        val dt = max(1L, t - lastTime)
        lastX = x
        lastY = y
        lastTime = t
        if (!moved && hypot(x - startX, y - startY) > slopPx) {
            moved = true
            if (dragArmed && maxCount == 1) {
                dragging = true
                emit(PadCommand.Button(PointerButton.LEFT, true))
            }
        }
        if (maxCount >= 3) {
            multiSwipe(n, x, y)
        } else if (maxCount == 2) {
            if (n == 2) twoFingers(dx, dy, x, y, t, spread)
        } else {
            pointerMove(dx, dy, hypot(dx, dy) / dt)
        }
    }

    private fun pointerMove(dx: Float, dy: Float, speed: Float) {
        val g = padGain(speed)
        val fx = dx * g + remMoveX
        val fy = dy * g + remMoveY
        val ix = fx.toInt()
        val iy = fy.toInt()
        remMoveX = fx - ix
        remMoveY = fy - iy
        if (ix != 0 || iy != 0) emit(PadCommand.Move(ix, iy))
    }

    /** 2 doigts : pincement (zoom), balayage rapide (back/forward) ou défilement. */
    private fun twoFingers(dx: Float, dy: Float, x: Float, y: Float, t: Long, spread: Float) {
        if (fired) return
        if (spread0 > 0f && spread > 0f) {
            if (!pinchLocked && abs(spread / spread0 - 1f) > PINCH_LOCK_RATIO) pinchLocked = true
            if (pinchLocked) {
                zoom(spread)
                return
            }
        }
        val totalX = x - twoX
        val totalY = y - twoY
        if (!flickDead) {
            val tooLate = t - twoTime > FLICK_MAX_MS
            val notHorizontal = hypot(totalX, totalY) > slopPx && abs(totalX) < FLICK_AXIS_RATIO * abs(totalY)
            if (tooLate || notHorizontal) flickDead = true
        }
        if (!flickDead) {
            // Le défilement reste en attente tant que ce peut être un balayage : il sort d'un bloc s'il n'en est pas un.
            remScrollX += -dx / pxPerNotch
            remScrollY += dy / pxPerNotch
            if (abs(totalX) >= FLICK_MIN_FRAC * padWidthPx()) {
                fired = true
                remScrollX = 0f
                remScrollY = 0f
                emit(PadCommand.Gesture(if (totalX > 0) PointerGesture.BACK else PointerGesture.FORWARD))
            }
            return
        }
        scroll(dx, dy)
    }

    /** Un cran de zoom par [PINCH_STEP_RATIO] de variation de l'écart, depuis le dernier cran. */
    private fun zoom(spread: Float) {
        if (zoomRef <= 0f) return
        while (spread / zoomRef >= 1f + PINCH_STEP_RATIO) {
            zoomRef *= 1f + PINCH_STEP_RATIO
            emit(PadCommand.Gesture(PointerGesture.ZOOM_IN))
        }
        while (spread / zoomRef <= 1f - PINCH_STEP_RATIO) {
            zoomRef *= 1f - PINCH_STEP_RATIO
            emit(PadCommand.Gesture(PointerGesture.ZOOM_OUT))
        }
    }

    /** 3 ou 4 doigts : un geste dès que le centre a franchi le seuil, selon la direction dominante. */
    private fun multiSwipe(n: Int, x: Float, y: Float) {
        if (fired || n < 3) return
        val dx = x - multiX
        val dy = y - multiY
        if (max(abs(dx), abs(dy)) < MULTI_SWIPE_FRAC * padWidthPx()) return
        val horizontal = abs(dx) >= abs(dy)
        val gesture = when {
            n == 3 && horizontal -> if (dx < 0) PointerGesture.APP_PREVIOUS else PointerGesture.APP_NEXT
            n == 3 -> if (dy < 0) PointerGesture.TASK_VIEW else PointerGesture.DESKTOP
            horizontal -> if (dx < 0) PointerGesture.DESKTOP_PREVIOUS else PointerGesture.DESKTOP_NEXT
            else -> return
        }
        fired = true
        emit(PadCommand.Gesture(gesture))
    }

    private fun scroll(dx: Float, dy: Float) {
        remScrollX += -dx / pxPerNotch
        remScrollY += dy / pxPerNotch
        val sx = remScrollX.toInt()
        val sy = remScrollY.toInt()
        remScrollX -= sx
        remScrollY -= sy
        if (sx != 0 || sy != 0) emit(PadCommand.Scroll(sx, sy))
    }

    private fun up(n: Int, x: Float, y: Float, t: Long) {
        if (count == 0) return
        count = n
        if (n > 0) {
            resetReference(x, y, t)
            multiX = x
            multiY = y
            return
        }
        if (dragging) {
            emit(PadCommand.Button(PointerButton.LEFT, false))
            lastTapUp = NONE
        } else if (!moved && !fired && t - startTime <= TAP_MAX_MS && maxCount <= 4) {
            when (maxCount) {
                1 -> {
                    emit(PadCommand.Click(PointerButton.LEFT))
                    lastTapUp = t
                    return finish()
                }
                2 -> emit(PadCommand.Click(PointerButton.RIGHT))
                3 -> emit(PadCommand.Gesture(PointerGesture.SEARCH))
                else -> emit(PadCommand.Gesture(PointerGesture.NOTIFICATIONS))
            }
            lastTapUp = NONE
        } else {
            lastTapUp = NONE
        }
        finish()
    }

    private fun finish() {
        dragging = false
        dragArmed = false
        maxCount = 0
    }

    /** Geste interrompu (le système reprend la main) : relâche le bouton s'il était enfoncé, sans clic. */
    private fun cancel() {
        if (dragging) emit(PadCommand.Button(PointerButton.LEFT, false))
        count = 0
        maxCount = 0
        dragging = false
        dragArmed = false
        moved = false
        fired = false
        pinchLocked = false
        lastTapUp = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val DEFAULT_PAD_WIDTH = 1000f
    }
}
