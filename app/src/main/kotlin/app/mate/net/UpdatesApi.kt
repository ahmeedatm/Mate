package app.mate.net

import app.mate.BuildConfig
import app.mate.installers.BackendPlan
import app.mate.installers.BackendRelease
import app.mate.installers.Installer
import app.mate.installers.InstallerPlan
import org.json.JSONObject
import java.io.File

/** D'où vient une mise à jour : l'ordinateur relié (« serveur Unowhy » simulé), le serveur central ou ses essais de MR. */
enum class UpdateSource { COMPUTER, SERVER, TRIALS }

/** Mise à jour annoncée par une source : `sha256` vérifiée après téléchargement. */
data class AppUpdate(
    val pkg: String, val label: String, val versionCode: Long, val versionName: String,
    val file: String, val size: Long, val sha256: String,
    val source: UpdateSource = UpdateSource.COMPUTER,
)

/** Fichier d'une archive du backend d'un essai : `sha256` vérifiée après téléchargement. */
data class BackendArchive(val file: String, val size: Long, val sha256: String)

/** Essai d'une MR publié dans le registre (paquet companion-updates/essais) : son APK, et son backend par système s'il change. */
data class Essai(val mr: Int, val title: String, val update: AppUpdate, val backends: Map<String, BackendArchive>) {
    val hasBackend: Boolean get() = backends.isNotEmpty()
}

/**
 * Backend de l'ordinateur : `mode` = « origin » ou « essai » (de la MR `mr`, archive d'empreinte `sha256`, null pour un
 * ordinateur qui ne la donne pas) ; `packaged` faux = lancé depuis le dépôt.
 */
data class BackendState(val packaged: Boolean, val mode: String, val mr: Int?, val sha256: String?)

/**
 * Mises à jour de la tablette. Même format des deux côtés : ordinateur (`GET /updates`, src/updates.ts, avec le jeton)
 * et serveur central (`GET <base>/manifest.json`, APK à côté, sans jeton : la tablette ne garde aucun secret).
 */
class UpdatesApi(private val http: Http, private val source: UpdateSource = UpdateSource.COMPUTER) {

    suspend fun list(): List<AppUpdate> = http.io {
        parseUpdates(http.get(if (source == UpdateSource.SERVER) "/manifest.json" else "/updates"), source)
    }

    /** Essais de MR (`GET <base>/manifest.json` du paquet essais). */
    suspend fun essais(): List<Essai> = http.io { parseEssais(http.get("/manifest.json")) }

    /** Backend de l'ordinateur : d'origine, ou celui d'un essai. */
    suspend fun backendState(): BackendState = http.io {
        JSONObject(http.get("/companion/backend")).let {
            BackendState(
                it.getBoolean("packaged"), it.getString("mode"), if (it.isNull("mr")) null else it.getInt("mr"),
                if (it.isNull("sha256")) null else it.optString("sha256").ifEmpty { null },
            )
        }
    }

    /** Envoie l'archive du backend de la MR `mr` à l'ordinateur, qui la pose puis redémarre. */
    suspend fun sendBackend(mr: Int, sha256: String, archive: ByteArray) {
        http.io { http.text(http.request("POST", "/companion/backend/essai?mr=$mr&sha256=$sha256", archive, "application/gzip", 120_000)) }
    }

    /** Archives du backend d'une release (clé `backends`) : manifest du serveur central, ou `GET /updates` de l'ordinateur. */
    suspend fun backends(): List<BackendRelease> = http.io {
        BackendPlan.parse(http.get(if (source == UpdateSource.SERVER) "/manifest.json" else "/updates"), source)
    }

    /** Envoie l'archive du backend de la release `version` à l'ordinateur, qui remplace le sien puis redémarre. */
    suspend fun sendBackendUpdate(version: String, sha256: String, archive: ByteArray) {
        http.io { http.text(http.request("POST", "/companion/backend/update?version=$version&sha256=$sha256", archive, "application/gzip", 120_000)) }
    }

    /** Remet le backend d'origine de l'ordinateur (sans effet s'il y est déjà). */
    suspend fun restoreBackend() {
        http.io { http.postJson("/companion/backend/origin", JSONObject()) }
    }

    /** Télécharge une mise à jour dans `dest` ; `progress` (0 à 100, appelé hors du fil principal). Renvoie l'empreinte SHA-256 reçue. */
    suspend fun download(update: AppUpdate, dest: File, progress: (Int) -> Unit): String = download(update.file, update.size, dest, progress)

    /** Installeurs de l'ordinateur annoncés par le serveur central (clé `installers` du manifeste). */
    suspend fun installers(): List<Installer> = http.io { InstallerPlan.parse(http.get("/manifest.json")) }

    /** Télécharge un installeur de l'ordinateur dans `dest`. Renvoie l'empreinte SHA-256 reçue. */
    suspend fun downloadInstaller(installer: Installer, dest: File): String = download(installer.file, installer.size, dest) {}

    /** Idem pour un fichier de la source (APK, archive du backend d'un essai) ; `size` sert au pourcentage. */
    suspend fun download(file: String, size: Long, dest: File, progress: (Int) -> Unit): String = http.io {
        val path = (if (source == UpdateSource.COMPUTER) "/updates/file/" else "/") + http.encode(file)
        val conn = http.request("GET", path, timeoutMs = 120_000)
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
        /**
         * Essais de MR : paquet `essais` à côté de `latest` (même registre, même jeton de lecture). Autre adresse
         * (essai local) : `/essais` ajouté. Null si le serveur central est coupé au build.
         */
        fun essais(baseUrl: String = BuildConfig.UPDATE_URL, header: String = BuildConfig.UPDATE_HEADER): UpdatesApi? =
            server(essaisUrl(baseUrl), header)?.let { UpdatesApi(it.http, UpdateSource.TRIALS) }

        internal fun essaisUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.isEmpty()) "" else if (base.endsWith("/latest")) base.removeSuffix("/latest") + "/essais" else "$base/essais"
        }

        /** Serveur central, null s'il est coupé au build (`-PupdateUrl=`). En-tête facultatif `-PupdateHeader="Nom: valeur"`. */
        fun server(baseUrl: String = BuildConfig.UPDATE_URL, header: String = BuildConfig.UPDATE_HEADER): UpdatesApi? =
            baseUrl.trimEnd('/').ifEmpty { null }?.let { UpdatesApi(Http(it, "", 10_000, parseHeader(header)), UpdateSource.SERVER) }

        /** « Nom: valeur » → une entrée ; vide ou sans « : » → aucun en-tête. */
        internal fun parseHeader(header: String): Map<String, String> {
            val i = header.indexOf(':')
            return if (i <= 0) emptyMap() else mapOf(header.substring(0, i).trim() to header.substring(i + 1).trim())
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

/** `{"essais":[{mr, title, package, label, versionCode, versionName, file, size, sha256, backend?: {mac?, windows?: {file, size, sha256}}}]}`, la plus récente MR d'abord. */
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
