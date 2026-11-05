package app.mate.ui.mixer

import android.content.Context
import androidx.appcompat.app.AlertDialog
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.currentOutputIndex
import app.mate.ui.errorText
import app.mate.ui.showImmersive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Liste les sorties audio de l'ordinateur (enceinte, casque, carte son) et passe sur celle qu'on touche. Partagé par la
 * table de mixage (titre de la voie Sortie) et le widget Son. [onError] : texte à montrer ; [onSwitched] : sortie changée,
 * le volume et le Muet sont ceux de la nouvelle sortie, à relire.
 */
class AudioOutputChooser(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
    private val onSwitched: suspend () -> Unit,
) {
    private var choosing: Job? = null

    fun show() {
        // Un seul choix à la fois : un second toucher pendant la lecture de la liste ne doit pas empiler une 2e boîte.
        if (choosing?.isActive == true) return
        choosing = scope.launch {
            val outputs = try {
                BackendClient(context).system.audioOutputs()
            } catch (e: Exception) {
                return@launch onError(context.errorText(e))
            }
            if (outputs.isEmpty()) return@launch onError(context.getString(R.string.mixer_output_none))
            AlertDialog.Builder(context)
                .setTitle(R.string.mixer_output_title)
                .setSingleChoiceItems(outputs.map { it.name }.toTypedArray(), currentOutputIndex(outputs)) { dialog, which ->
                    dialog.dismiss()
                    if (!outputs[which].current) switch(outputs[which].id)
                }
                .setNegativeButton(R.string.cancel, null)
                .showImmersive()
        }
    }

    private fun switch(id: String) {
        scope.launch {
            try {
                BackendClient(context).system.setAudioOutput(id)
                onSwitched()
            } catch (e: Exception) {
                onError(context.errorText(e))
            }
        }
    }
}
