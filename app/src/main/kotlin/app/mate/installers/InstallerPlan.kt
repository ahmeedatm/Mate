package app.mate.installers

import org.json.JSONArray
import org.json.JSONObject

enum class Os(val key: String) { MAC("mac"), WINDOWS("windows") }

/** Installeur de l'ordinateur annoncé par GitHub (clé `installers` de manifest.json) ou gardé sur la tablette. */
data class Installer(val os: Os, val file: String, val size: Long, val sha256: String, val version: String)

/** Choix des installeurs à télécharger et de l'OS du visiteur, sans Android (testé dans app/src/test). */
object InstallerPlan {

    /** `{"installers":[{os, file, size, sha256, version}]}` ; clé absente, OS inconnu ou nom de fichier suspect : ignorés. */
    fun parse(json: String): List<Installer> {
        val a = JSONObject(json).optJSONArray("installers") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val os = Os.values().firstOrNull { it.key == o.optString("os") } ?: return@mapNotNull null
            val file = o.optString("file")
            if (!isPlainName(file) || o.optLong("size") <= 0 || o.optString("sha256").isEmpty()) return@mapNotNull null
            Installer(os, file, o.getLong("size"), o.getString("sha256"), o.optString("version"))
        }
    }

    fun toJson(installers: List<Installer>): String = JSONObject().put("installers", toJsonArray(installers)).toString()

    fun toJsonArray(installers: List<Installer>): JSONArray =
        JSONArray(installers.map { JSONObject().put("os", it.os.key).put("file", it.file).put("size", it.size).put("sha256", it.sha256).put("version", it.version) })

    /** Nom de fichier sans dossier : il devient un chemin local et une adresse du serveur. */
    private fun isPlainName(name: String) = name.isNotEmpty() && !name.startsWith(".") && name.none { it == '/' || it == '\\' || it.isISOControl() }

    /** À télécharger : annoncés et absents du disque, ou différents (taille ou empreinte) d'une version plus ancienne. */
    fun toDownload(announced: List<Installer>, onDisk: List<Installer>): List<Installer> =
        announced.filter { a -> onDisk.none { it.file == a.file && it.size == a.size && it.sha256.equals(a.sha256, ignoreCase = true) } }

    /** À supprimer : gardés sur la tablette mais plus annoncés. Annonce vide : rien (manifeste sans installeurs, pas un retrait). */
    fun toDelete(announced: List<Installer>, onDisk: List<Installer>): List<Installer> =
        if (announced.isEmpty()) emptyList() else onDisk.filter { d -> announced.none { it.file == d.file } }

    /**
     * Installeur gardé pour l'OS de l'ordinateur et plus récent que son compagnon, sinon null. Version absente : compagnon
     * d'avant la 1.2, à mettre à jour ; `dev` ou illisible (lancé depuis le dépôt) : jamais.
     */
    fun newerFor(os: String, version: String?, onDisk: List<Installer>): Installer? {
        val installer = onDisk.firstOrNull { it.os.key == os } ?: return null
        val available = numbers(installer.version) ?: return null
        if (version == null) return installer
        val current = numbers(version) ?: return null
        return installer.takeIf { compare(available, current) > 0 }
    }

    internal fun compare(a: List<Int>, b: List<Int>): Int = compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] })

    /** [X, Y, Z] d'une version X.Y.Z, null sinon (« dev », vide). */
    internal fun numbers(version: String): List<Int>? =
        Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(version)?.groupValues?.drop(1)?.map { it.toInt() }

    /** OS du navigateur qui visite la page ; null pour un téléphone, Linux, ou un navigateur sans `User-Agent`. */
    fun osOf(userAgent: String?): Os? = when {
        userAgent == null -> null
        userAgent.contains("Windows", ignoreCase = true) && !userAgent.contains("Phone", ignoreCase = true) -> Os.WINDOWS
        userAgent.contains("Macintosh") -> Os.MAC
        else -> null
    }
}
