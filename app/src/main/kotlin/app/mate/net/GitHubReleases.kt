package app.mate.net

import androidx.annotation.StringRes
import app.mate.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Source de mises à jour dans une release GitHub : `manifest.json`, APK, installeurs et archives du backend sont les
 * assets du tag, lus par leur lien de téléchargement public (`github.com/<repo>/releases/download/<tag>/<fichier>`).
 * Dépôt public : ni API, ni jeton, ni sa limite de 60 appels par heure et par adresse IP (partagée par la tablette,
 * l'essai et l'ordinateur derrière la même box). GitHub répond 302 vers son stockage (autre hôte) : suivi à la main,
 * HTTPS seulement. `connect` : remplacé par les tests.
 */
internal class GitHubStore(
    private val repo: String,
    private val tag: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : UpdateStore {

    /** Manifeste remplacé à chaque publication : `?t=` évite une copie gardée par un cache entre la tablette et GitHub. */
    override fun manifest(): String {
        val conn = get("${downloadUrl(repo, tag, MANIFEST)}?t=${now()}", 60_000)
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    override fun open(file: String, timeoutMs: Int): HttpURLConnection = get(downloadUrl(repo, tag, file), timeoutMs)

    private fun get(url: String, timeoutMs: Int): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = connect(target)
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            val code = try { conn.responseCode } catch (e: IOException) { conn.disconnect(); throw GitHubException(GitHubException.NETWORK, e) }
            when {
                code in 200..299 -> return conn
                code in REDIRECTS -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    target = redirectTarget(target, location)
                }
                else -> {
                    conn.disconnect()
                    throw GitHubException(code)
                }
            }
        }
        throw IOException("Trop de redirections")
    }

    companion object {
        const val MANIFEST = "manifest.json"
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun downloadUrl(repo: String, tag: String, file: String) =
            "https://github.com/$repo/releases/download/$tag/${URLEncoder.encode(file, "UTF-8").replace("+", "%20")}"

        /** Cible d'une redirection, relative ou absolue ; HTTPS seulement. */
        fun redirectTarget(from: String, location: String?): String {
            if (location.isNullOrEmpty()) throw IOException("Redirection sans destination")
            val target = URI(from).resolve(location)
            if (target.scheme != "https") throw IOException("Redirection hors HTTPS refusée")
            return target.toString()
        }
    }
}

/** Refus de GitHub (code HTTP) ou réseau coupé ([NETWORK]) : affiché par errorText avec [textRes], jamais tel quel. */
class GitHubException(val code: Int, cause: Throwable? = null) : IOException("GitHub $code", cause) {
    @StringRes
    fun textRes(): Int = when (code) {
        NETWORK -> R.string.update_server_unreachable
        403, 429 -> R.string.github_rate_limited
        404 -> R.string.github_file_missing
        else -> R.string.github_down
    }

    companion object {
        const val NETWORK = 0
    }
}

/** Manifeste absent (404) : rien de publié dans la release, liste vide. Tout autre refus remonte. */
internal inline fun <T> orNothingPublished(block: () -> List<T>): List<T> =
    try { block() } catch (e: GitHubException) { if (e.code == 404) emptyList() else throw e }
