package app.mate.ui.home

import android.content.Context
import app.mate.net.AudioState
import app.mate.net.BackendClient
import app.mate.net.SystemApi
import app.mate.net.runCatchingCancellable
import app.mate.ui.LatestSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Son de l'ordinateur pour les formes du widget Son qui ne passent pas par [app.mate.ui.ComputerAudioView] (anneau,
 * remplissage) : relu chaque seconde tant que le widget est à l'écran, le volume part pendant le glisser.
 * [holding] : doigt posé, l'état relu ne fait pas sauter l'affichage. [shown] : faux, la vue est cachée, rien à relire.
 */
class AudioLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shown: () -> Boolean,
    private val onState: (AudioState?) -> Unit,
) {
    var state: AudioState? = null
        private set
    var holding = false
    private var poll: Job? = null
    private val sender = LatestSender<Int>(scope) { show(api().setAudio(output = it)) }

    fun setActive(active: Boolean) {
        poll?.cancel()
        poll = null
        // Un glisser coupé net (changement de taille, écran quitté) ne doit pas figer l'affichage.
        holding = false
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                if (!holding && shown()) runCatchingCancellable { api().audio() }.getOrNull()?.let(::show)
                delay(POLL_MS)
            }
        }
    }

    fun setVolume(volume: Int) = sender.offer(volume.coerceIn(0, MAX))

    fun toggleMute() = change { setAudio(muted = !(state?.muted ?: false)) }

    private fun change(call: suspend SystemApi.() -> AudioState) {
        scope.launch { show(runCatchingCancellable { api().call() }.getOrNull()) }
    }

    private fun show(audio: AudioState?) {
        state = audio
        if (!holding) onState(audio)
    }

    private fun api() = BackendClient(context).system

    companion object {
        const val MAX = 100

        /** Pas des actions d'accessibilité de [VolumeFill], comme les boutons de volume de la tablette. */
        const val STEP = 6
        private const val POLL_MS = 1_000L
    }
}
