package app.mate.installers

import android.content.Context
import android.util.Log
import app.mate.BuildConfig
import app.mate.Computers
import app.mate.R
import app.mate.UserFacingException
import app.mate.net.BackendClient
import app.mate.net.Identity
import app.mate.net.UpdateSource
import app.mate.net.UpdatesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mise à jour du compagnon de l'ordinateur actif depuis la tablette : archive du backend de la dernière release (serveur
 * central, ou « serveur Unowhy » de l'ordinateur), vérifiée puis envoyée par le jeton d'appairage
 * (POST /companion/backend/update) ; l'ordinateur la pose et redémarre. Aucun secret ne passe à l'ordinateur.
 */
object CompanionUpdater {

    /** `identity` : null si l'ordinateur ne répond pas. */
    class Status(val identity: Identity?, val decision: BackendPlan.Decision)

    private val busy = AtomicBoolean(false)

    /** Sans ordinateur appairé : null. Une source en erreur n'empêche pas l'autre. */
    suspend fun check(context: Context): Status? = coroutineScope {
        if (Computers.active(context) == null) return@coroutineScope null
        val client = BackendClient(context)
        val identity = async { quiet { client.pairing.identity() } }
        val state = async { quiet { client.updates.backendState() } }
        val fromComputer = async { quiet { client.updates.backends() }.orEmpty() }
        val fromServer = async { UpdatesApi.server()?.let { quiet { it.backends() } }.orEmpty() }
        Status(identity.await(), BackendPlan.decide(identity.await(), state.await(), fromServer.await() + fromComputer.await(), BuildConfig.TRIAL))
    }

    /**
     * Télécharge `release`, vérifie son empreinte, l'envoie à l'ordinateur actif puis attend qu'il revienne dans la
     * nouvelle version (2 min au plus). `progress` : 0 à 100 pendant le téléchargement, appelé hors du fil principal.
     */
    suspend fun update(context: Context, release: BackendRelease, progress: (Int) -> Unit) {
        if (!busy.compareAndSet(false, true)) throw UserFacingException(R.string.companion_update_busy)
        val file = File(context.cacheDir, "updates").apply { mkdirs() }.resolve("companion-backend.tar.gz")
        try {
            val client = BackendClient(context)
            val api = if (release.source == UpdateSource.COMPUTER) client.updates
            else UpdatesApi.server() ?: throw UserFacingException(R.string.update_server_unreachable)
            val sha256 = api.download(release.file, release.size, file, progress)
            if (!sha256.equals(release.sha256, ignoreCase = true)) throw UserFacingException(R.string.update_file_corrupt)
            client.updates.sendBackendUpdate(release.version, sha256, file.readBytes())
            waitFor(context, release.version)
        } finally {
            file.delete()
            busy.set(false)
        }
    }

    /** Le compagnon redémarre en quelques secondes ; relu toutes les 3 s jusqu'à la version attendue. */
    private suspend fun waitFor(context: Context, version: String) {
        delay(3_000)
        repeat(40) {
            if (quiet { BackendClient(context).pairing.identity() }?.version == version) return
            delay(3_000)
        }
        throw UserFacingException(R.string.companion_update_no_restart)
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i("CompanionUpdater", "Lecture impossible", e)
        null
    }
}
