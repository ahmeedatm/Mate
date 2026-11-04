package app.mate.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.mate.BuildConfig
import app.mate.Computer
import app.mate.Computers
import app.mate.R
import app.mate.installers.InstallerHint
import app.mate.installers.InstallerService
import app.mate.net.BackendClient
import app.mate.net.Discovery
import app.mate.net.FoundComputer
import app.mate.net.HttpException
import app.mate.net.InsecureComputerException
import app.mate.net.OutdatedCompanionException
import app.mate.net.Pairing
import app.mate.net.PairingCode
import app.mate.net.PinnedTrust
import app.mate.net.isLocalUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.net.ssl.SSLException

/**
 * Un appairage (protocole : backend/src/pairing.ts) : demande, nombre dévoilé, code affiché par la tablette, puis
 * attente de la saisie sur l'ordinateur (confirmation chaque seconde) et enregistrement. Le code mêle l'empreinte du
 * certificat vu par la tablette : s'il passe par un intercepteur, l'ordinateur refuse le code et le jeton ne part jamais.
 * `trust` : certificat déjà vu (adresse saisie), sinon retenu à la première requête.
 * Rappels : `onCode` (code à 6 chiffres, espacé), `onMessage` (étape en cours), `onFailed` (texte, compagnon à mettre à jour),
 * `onAdded` (ordinateur enregistré et devenu l'actif). `cancel()` l'arrête.
 */
class PairingSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val url: String,
    private val name: String,
    private val trust: PinnedTrust = PinnedTrust(null),
    private val onCode: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onFailed: (text: String, outdated: Boolean) -> Unit,
    private val onAdded: (Computer) -> Unit,
) {
    private val client = BackendClient(url, connectTimeoutMs = 4_000, trust = trust)
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /** Nouvelle demande (et nouveau code) ; remplace celle en cours. */
    fun start() {
        job?.cancel()
        onMessage(context.getString(R.string.computer_requesting, name))
        job = scope.launch {
            try {
                val secret = PairingCode.newSecret()
                val (request, computerNonce) = client.pairing.request(context.getString(R.string.tablet_name, Build.MODEL), secret.commit)
                client.pairing.reveal(request, secret.nonce)
                // En HTTP local (USB), pas de certificat : "" comme sur l'ordinateur.
                val channel = if (url.startsWith("https://")) trust.seen.orEmpty() else ""
                onCode(PairingCode.spaced(PairingCode.code(channel, secret.nonce, computerNonce)))
                var pairing: Pairing? = null
                while (pairing == null) {
                    delay(1_000)
                    pairing = client.pairing.confirm(request)
                }
                // En WiFi, l'ordinateur annonce le certificat que la tablette a vu ; en USB, elle le découvre ici.
                if (channel.isNotEmpty() && pairing.fingerprint != channel) throw InsecureComputerException()
                val known = Computers.all(context).firstOrNull { it.id == pairing.id }
                val computer = Computer(
                    pairing.id, known?.name ?: pairing.name, url, pairing.token, pairing.os,
                    pin = channel.ifEmpty { pairing.fingerprint.ifEmpty { null } },
                )
                Computers.save(context, computer, makeActive = true)
                onAdded(Computers.active(context) ?: computer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutdatedCompanionException) {
                onFailed(outdatedText(context, name), true)
            } catch (e: Exception) {
                onFailed(reason(context, e), false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        /** HTTP en clair sur le WiFi : compagnon d'avant le HTTPS, aucune demande (elle échouerait et s'afficherait sur l'ordinateur). */
        fun isOutdated(url: String) = !url.startsWith("https://") && !isLocalUrl(url)

        fun outdatedText(context: Context, name: String) = when (val hint = InstallerHint.of(BuildConfig.TRIAL, InstallerService.link())) {
            is InstallerHint.Trial -> context.getString(R.string.computer_outdated_detail_trial, name)
            is InstallerHint.NoWifi -> context.getString(R.string.computer_outdated_detail, name, context.getString(R.string.install_address_no_wifi))
            is InstallerHint.Address -> context.getString(R.string.computer_outdated_detail, name, hint.link)
        }

        private fun reason(context: Context, e: Exception): String = when (e) {
            is HttpException -> e.reason
            is InsecureComputerException, is SSLException -> context.getString(R.string.computer_insecure)
            else -> context.getString(R.string.computer_lost)
        }
    }
}

/**
 * Appairage sans rien choisir : la tablette cherche sur le WiFi et prend l'ordinateur qui attend une tablette (sa page
 * de Mate est affichée, `Discovery.pickWaiting`), puis lance l'appairage ; le code s'affiche, à saisir sur l'ordinateur.
 * L'appelant répète `round()` (toutes les 4 s). Une session en cours n'est jamais doublée ; après un échec, le même
 * ordinateur n'est pas redemandé avant `RETRY_MS` (chaque demande prévient l'utilisateur et le backend les limite).
 * `onFound` : au moins un ordinateur a répondu (même sans attendre).
 */
class AutoPairing(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAdded: (Computer) -> Unit,
    private val onFound: (Boolean) -> Unit,
    private val onCode: (code: String, name: String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private var session: PairingSession? = null
    private val failedAt = HashMap<String, Long>()
    private var pausedUntil = 0L

    val busy: Boolean get() = session?.running == true

    suspend fun round(timeoutMs: Long = 2_000) {
        if (busy) return
        val found = runCatching { Discovery.find(timeoutMs) }.getOrDefault(emptyList())
        onFound(found.isNotEmpty())
        val now = SystemClock.elapsedRealtime()
        if (busy || now < pausedUntil) return
        val ready = found.filter { c -> failedAt[c.id]?.let { now - it < RETRY_MS } != true }
        Discovery.pickWaiting(ready)?.let { start(it) }
    }

    /** Aucune nouvelle demande pendant `ms` (une saisie d'adresse à la main a pris la main). */
    fun pause(ms: Long) {
        pausedUntil = SystemClock.elapsedRealtime() + ms
    }

    fun cancel() {
        session?.cancel()
        session = null
    }

    private fun start(computer: FoundComputer) {
        session = PairingSession(
            context, scope, computer.url, computer.name,
            onCode = { onCode(it, computer.name) },
            onMessage = onMessage,
            onFailed = { text, _ ->
                failedAt[computer.id] = SystemClock.elapsedRealtime()
                onFailed(text)
            },
            onAdded = onAdded,
        ).also { it.start() }
        failedAt.remove(computer.id)
    }

    private companion object {
        const val RETRY_MS = 15_000L
    }
}
