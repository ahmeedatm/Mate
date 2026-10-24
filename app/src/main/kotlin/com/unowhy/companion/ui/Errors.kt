package com.unowhy.companion.ui

import android.content.Context
import android.util.Log
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Phrase à afficher pour une erreur : jamais de texte technique (anglais, nom d'exception) à l'écran,
 * le détail va dans le journal. Les messages de l'ordinateur sont déjà rédigés pour la tablette (server.ts).
 */
fun Context.errorText(e: Throwable?): String {
    Log.w("Companion", "Erreur affichée", e)
    val computer = Computers.active(this)
    return when (e) {
        is BackendClient.HttpException -> when {
            e.code == 401 -> getString(R.string.error_reauth, computer?.name ?: getString(R.string.computer_generic))
            // Route inconnue : le compagnon de l'ordinateur est plus ancien que l'appli.
            e.code == 404 && e.reason.startsWith("Route ") -> getString(R.string.error_bad_reply)
            e.reason.isNotBlank() -> e.reason
            else -> getString(R.string.error_computer)
        }
        // Connexion refusée, délai dépassé, réseau absent…
        is IOException ->
            if (computer == null) getString(R.string.error_no_computer)
            else getString(R.string.error_unreachable, computer.name)
        is JSONException -> getString(R.string.error_bad_reply)
        // Annulation d'une coroutine (« Job was cancelled ») ou la nôtre (« Téléchargement annulé »).
        is CancellationException -> e.message?.takeUnless { "cancel" in it } ?: getString(R.string.error_cancelled)
        // Nos propres refus (mises à jour, WiFi) sont écrits en français.
        is IllegalStateException, is SecurityException -> e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.error_generic)
        else -> getString(R.string.error_generic)
    }
}
