package app.mate.ui.home

import android.content.Context
import app.mate.net.BackendClient
import app.mate.net.TileResult
import app.mate.net.runCatchingCancellable
import app.mate.ui.ActionSpeech

/**
 * Lance la touche [id] sur l'ordinateur ; point commun des touches des widgets (Touches, Apps, Discord, Réunion, sites).
 * Annonce le résultat à voix haute si le réglage est allumé ([ActionSpeech]). Une appli à plusieurs fenêtres ne se lance
 * pas encore (l'ordinateur rend ses fenêtres à choisir) : rien n'est annoncé.
 */
suspend fun runTileSpoken(context: Context, id: String, label: String): Result<TileResult> {
    val result = runCatchingCancellable { BackendClient(context).deck.runTile(id) }
    if (result.isFailure || result.getOrNull()?.windows == null) ActionSpeech.announce(context, label, ok = result.isSuccess)
    return result
}
