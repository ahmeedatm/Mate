package com.unowhy.companion.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.UserFacingException
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.net.InsecureComputerException
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Phrase à afficher pour une erreur : jamais de texte technique (anglais, nom d'exception) à l'écran,
 * le détail va dans le journal. Les messages de l'ordinateur sont déjà rédigés pour la tablette (server.ts).
 *
 * À appeler dans un `catch` de coroutine : l'annulation (vue ou fragment retiré) est relancée, pas affichée.
 */
fun Context.errorText(e: Throwable?): String {
    if (e is CancellationException) throw e
    Log.w("Companion", "Erreur affichée", e)
    val computer = Computers.active(this)
    return when (e) {
        is HttpException -> when {
            e.code == 401 -> getString(R.string.error_reauth, computer?.name ?: getString(R.string.computer_generic))
            // Route inconnue : le compagnon de l'ordinateur est plus ancien que l'appli.
            e.code == 404 && e.reason.startsWith("Route ") -> getString(R.string.error_bad_reply)
            e.reason.isNotBlank() -> e.reason
            else -> getString(R.string.error_computer)
        }
        // Appairé avant le HTTPS, ou certificat qui n'est plus celui de l'appairage : réappairer (net/Pinning.kt).
        is InsecureComputerException, is SSLException -> getString(R.string.error_insecure, computer?.name ?: getString(R.string.computer_generic))
        // Connexion refusée, délai dépassé, réseau absent…
        is IOException ->
            if (computer == null) getString(R.string.error_no_computer)
            else getString(R.string.error_unreachable, computer.name)
        is JSONException -> getString(R.string.error_bad_reply)
        // Nos propres refus (mises à jour, WiFi) : phrase de strings.xml.
        is UserFacingException -> getString(e.res, *e.args)
        else -> getString(R.string.error_generic)
    }
}

/** Comme [Context.errorText] pour un fragment : l'annulation est relancée avant de toucher au contexte, qui n'existe plus si le fragment est retiré. */
fun Fragment.errorText(e: Throwable?): String {
    if (e is CancellationException) throw e
    return requireContext().errorText(e)
}

/** Toast d'erreur d'un fragment. Le texte est calculé d'abord : l'annulation est relancée avant tout accès au contexte. */
fun Fragment.toastError(e: Throwable?) {
    val text = errorText(e)
    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
}
