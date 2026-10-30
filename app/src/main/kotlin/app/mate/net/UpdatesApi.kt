package app.mate.net

import app.mate.BuildConfig
import app.mate.installers.BackendPlan
import app.mate.installers.BackendRelease
import app.mate.installers.Installer
import app.mate.installers.InstallerPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection

/** D'où vient une mise à jour : l'ordinateur relié, GitHub (release `latest`) ou les essais de PR (release `essais`). */
enum class UpdateSource { COMPUTER, SERVER, TRIALS }

/** Mise à jour annoncée par une source : `sha256` vérifiée après téléchargement. */
data class AppUpdate(
    val pkg: String, val label: String, val versionCode: Long, val versionName: String,
    val file: String, val size: Long, val sha256: String,
    val source: UpdateSource = UpdateSource.COMPUTER,
)

/** Fichier d'une archive du backend d'un essai : `sha256` vérifiée après téléchargement. */
data class BackendArchive(val file: String, val size: Long, val sha256: String)

/** Essai d'une PR publié dans la release `essais` : son APK, et son backend par système s'il change (`mr` : numéro de la PR). */
data class Essai(val mr: Int, val title: String, val update: AppUpdate, val backends: Map<String, BackendArchive>) {
    val hasBackend: Boolean get() = backends.isNotEmpty()
}

/**
 * Backend de l'ordinateur : `mode` = « origin » ou « essai » (de la PR `mr`, archive d'empreinte `sha256`, null pour un
 * ordinateur qui ne la donne pas) ; `packaged` faux = lancé depuis le dépôt.
 */
data class BackendState(val packaged: Boolean, val mode: String, val mr: Int?, val sha256: String?)

/** Où une source lit son manifeste et ses fichiers. */
internal interface UpdateStore {
    fun manifest(): String

    /** Réponse prête à lire d'un fichier de la source ; l'appelant la ferme. */
    fun open(file: String, timeoutMs: Int): HttpURLConnection
}

/** Ordinateur (`GET /updates`, `/updates/file/<f>`, avec le jeton) ou serveur HTTP simple (`<base>/manifest.json`, `<base>/<f>`). */
internal class HttpStore(private val http: Http, private val source: UpdateSource) : UpdateStore {
    private val computer = source == UpdateSource.COMPUTER

    override fun manifest(): String = http.get(if (computer) "/updates" else "/manifest.json")

    override fun open(file: String, timeoutMs: Int): HttpURLConnection =
        http.request("GET", (if (computer) "/updates/file/" else "/") + http.encode(file), timeoutMs = timeoutMs)
}

/**
 * Mises à jour de la tablette, même format de manifeste partout : l'ordinateur (`GET /updates`, src/updates.ts, avec le
 * jeton d'appairage), une release GitHub (`GitHubStore`, jeton de lecture injecté au build) ou un serveur HTTP simple
 * (essai local, `-PupdateUrl=`). `http` : l'ordinateur seulement.
 */
class UpdatesApi internal constructor(
    private val http: Http?, private val source: UpdateSource, private val store: UpdateStore,
) {
    constructor(http: Http) : this(http, UpdateSource.COMPUTER, HttpStore(http, UpdateSource.COMPUTER))

    private val computer: Http get() = checkNotNull(http) { "Appel réservé à l'ordinateur" }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun list(): List<AppUpdate> = io { parseUpdates(store.manifest(), source) }

    /** Essais de PR (manifeste de la release `essais`). */
    suspend fun essais(): List<Essai> = io { parseEssais(store.manifest()) }

    /** Backend de l'ordinateur : d'origine, ou celui d'un essai. */
    suspend fun backendState(): BackendState = io {
        JSONObject(computer.get("/companion/backend")).let {
            BackendState(
                it.getBoolean("packaged"), it.getString("mode"), if (it.isNull("mr")) null else it.getInt("mr"),
                if (it.isNull("sha256")) null else it.optString("sha256").ifEmpty { null },
            )
        }
    }

    /** Envoie l'archive du backend de la PR `mr` à l'ordinateur, qui la pose puis redémarre. */
    suspend fun sendBackend(mr: Int, sha256: String, archive: ByteArray) {
        io { computer.text(computer.request("POST", "/companion/backend/essai?mr=$mr&sha256=$sha256", archive, "application/gzip", 120_000)) }
    }

    /** Archives du backend d'une release (clé `backends`) : manifeste de GitHub, ou `GET /updates` de l'ordinateur. */
    suspend fun backends(): List<BackendRelease> = io { BackendPlan.parse(store.manifest(), source) }

    /** Envoie l'archive du backend de la release `version` à l'ordinateur, qui remplace le sien puis redémarre. */
    suspend fun sendBackendUpdate(version: String, sha256: String, archive: ByteArray) {
        io { computer.text(computer.request("POST", "/companion/backend/update?version=$version&sha256=$sha256", archive, "application/gzip", 120_000)) }
    }

    /** Remet le backend d'origine de l'ordinateur (sans effet s'il y est déjà). */
    suspend fun restoreBackend() {
        io { computer.postJson("/companion/backend/origin", JSONObject()) }
    }

    /** Télécharge une mise à jour dans `dest` ; `progress` (0 à 100, appelé hors du fil principal). Renvoie l'empreinte SHA-256 reçue. */
    suspend fun download(update: AppUpdate, dest: File, progress: (Int) -> Unit): String = download(update.file, update.size, dest, progress)

    /** Installeurs de l'ordinateur annoncés par GitHub (clé `installers` du manifeste). */
    suspend fun installers(): List<Installer> = io { InstallerPlan.parse(store.manifest()) }

    /** Télécharge un installeur de l'ordinateur dans `dest`. Renvoie l'empreinte SHA-256 reçue. */
    suspend fun downloadInstaller(installer: Installer, dest: File): String = download(installer.file, installer.size, dest) {}

    /** Idem pour un fichier de la source (APK, archive du backend d'un essai) ; `size` sert au pourcentage. */
    suspend fun download(file: String, size: Long, dest: File, progress: (Int) -> Unit): String = io {
        val conn = store.open(file, 120_000)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (size > 0) (done * 100 / size).toInt() else 0
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LATEST = "latest"
        private const val ESSAIS = "essais"

        /** Release `latest`, null si la source est coupée au build (`-PupdateRepo=` et `-PupdateUrl=` vides). */
        fun server(url: String = BuildConfig.UPDATE_URL, repo: String = BuildConfig.UPDATE_REPO, token: String = BuildConfig.UPDATE_TOKEN): UpdatesApi? =
            remote(url, repo, token, LATEST, UpdateSource.SERVER)

        /** Release `essais` (une entrée par PR), même dépôt et même jeton de lecture ; `<url>/essais` pour un essai local. */
        fun essais(url: String = BuildConfig.UPDATE_URL, repo: String = BuildConfig.UPDATE_REPO, token: String = BuildConfig.UPDATE_TOKEN): UpdatesApi? =
            remote(url, repo, token, ESSAIS, UpdateSource.TRIALS)

        /** Serveur HTTP simple s'il y a une `url` (essai local), sinon release GitHub du `repo`, sinon null. */
        private fun remote(url: String, repo: String, token: String, tag: String, source: UpdateSource): UpdatesApi? = when {
            url.isNotBlank() -> {
                val base = url.trimEnd('/').let { if (tag == LATEST) it else "$it/$tag" }
                Http(base, "", 10_000).let { UpdatesApi(it, source, HttpStore(it, source)) }
            }
            repo.isNotBlank() -> UpdatesApi(null, source, GitHubStore(repo.trim(), tag, token))
            else -> null
        }
    }
}

/** `{"updates":[{package, label, versionCode, versionName, file, size, sha256}]}`. */
internal fun parseUpdates(json: String, source: UpdateSource): List<AppUpdate> {
    val a = JSONObject(json).getJSONArray("updates")
    return (0 until a.length()).map { i ->
        a.getJSONObject(i).let {
            AppUpdate(it.getString("package"), it.getString("label"), it.getLong("versionCode"), it.getString("versionName"),
                it.getString("file"), it.getLong("size"), it.getString("sha256"), source)
        }
    }
}

/** `{"essais":[{mr (numéro de la PR), title, package, label, versionCode, versionName, file, size, sha256, backend?: {mac?, windows?: {file, size, sha256}}}]}`, la plus récente PR d'abord. */
internal fun parseEssais(json: String): List<Essai> {
    val a = JSONObject(json).getJSONArray("essais")
    return (0 until a.length()).map { a.getJSONObject(it) }.map { e ->
        val update = AppUpdate(e.getString("package"), e.getString("label"), e.getLong("versionCode"), e.getString("versionName"),
            e.getString("file"), e.getLong("size"), e.getString("sha256"), UpdateSource.TRIALS)
        val backend = e.optJSONObject("backend")
        val backends = backend?.keys()?.asSequence()?.associateWith { os ->
            backend.getJSONObject(os).let { BackendArchive(it.getString("file"), it.getLong("size"), it.getString("sha256")) }
        }.orEmpty()
        Essai(e.getInt("mr"), e.getString("title"), update, backends)
    }.sortedByDescending { it.mr }
}
