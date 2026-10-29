package com.unowhy.unomate.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.unowhy.unomate.R
import com.unowhy.unomate.net.AudioState
import com.unowhy.unomate.net.PresentAction

/**
 * Chrono du widget Présentation : part au premier appui sur Début ou Suivante, repart de zéro sur Fin. Il garde l'heure
 * de départ, pas un compteur : l'affichage reste juste même si le widget a été hors écran.
 */
class PresentTimer {
    private var startedAt: Long? = null

    val running get() = startedAt != null

    /** Début et Suivante le lancent s'il ne tourne pas, Fin l'arrête et le remet à zéro, Précédente n'y touche pas. */
    fun onAction(action: PresentAction, now: Long) {
        when (action) {
            PresentAction.START, PresentAction.NEXT -> if (startedAt == null) startedAt = now
            PresentAction.END -> startedAt = null
            PresentAction.PREVIOUS -> Unit
        }
    }

    fun elapsed(now: Long): Long = startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0
}

object PresentLogic {

    /** « mm:ss », « h:mm:ss » à partir d'une heure. */
    fun clock(elapsedMs: Long): String {
        val total = (elapsedMs / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Micro de l'ordinateur : état connu (ouvert, coupé) ou inconnu tant qu'on ne l'a pas lu. */
    enum class Mic(@StringRes val label: Int, @DrawableRes val icon: Int) {
        UNKNOWN(R.string.present_mic, R.drawable.ic_mic),
        LIVE(R.string.mic_on, R.drawable.ic_mic),
        MUTED(R.string.mic_off, R.drawable.ic_mic_off),
    }

    fun mic(audio: AudioState?): Mic = when {
        audio == null -> Mic.UNKNOWN
        audio.micMuted -> Mic.MUTED
        else -> Mic.LIVE
    }
}
