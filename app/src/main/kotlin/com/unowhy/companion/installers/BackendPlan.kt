package com.unowhy.companion.installers

import com.unowhy.companion.net.BackendState
import com.unowhy.companion.net.Identity
import com.unowhy.companion.net.UpdateSource
import org.json.JSONObject

/** Archive du backend d'une release (clé `backends` du manifest), lue sur le serveur central ou sur l'ordinateur. */
data class BackendRelease(val os: String, val file: String, val size: Long, val sha256: String, val version: String, val source: UpdateSource)

/** Ce que la tablette peut faire pour le compagnon de l'ordinateur actif, sans Android (testé dans app/src/test). */
object BackendPlan {

    sealed class Decision {
        /** Pas de réponse de l'ordinateur actif. */
        object Unreachable : Decision()

        /** Lancé depuis le dépôt : il se met à jour par git. */
        object FromRepo : Decision()

        /** Aucune archive publiée pour son système. */
        object NothingPublished : Decision()

        /** Compagnon d'avant la mise à jour depuis la tablette : l'installeur une dernière fois. */
        data class NeedsInstaller(val release: BackendRelease) : Decision()

        /** Essai de MR posé (tablette stable) : « Revenir à la stable » d'abord. */
        object OnEssai : Decision()
        object UpToDate : Decision()

        /** Ordinateur plus récent que la dernière release publiée. */
        data class ComputerNewer(val release: BackendRelease) : Decision()
        data class Available(val release: BackendRelease) : Decision()
    }

    /** `{"backends":[{os, file, size, sha256, version}]}` ; clé absente, nom de fichier ou version suspects : ignorés. */
    fun parse(json: String, source: UpdateSource): List<BackendRelease> {
        val a = JSONObject(json).optJSONArray("backends") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("file")
            val version = o.optString("version")
            if (file.isEmpty() || file.any { it == '/' || it == '\\' } || file.startsWith(".")) return@mapNotNull null
            if (InstallerPlan.numbers(version) == null || o.optLong("size") <= 0 || !o.optString("sha256").matches(SHA256)) return@mapNotNull null
            BackendRelease(o.optString("os"), file, o.getLong("size"), o.getString("sha256"), version, source)
        }
    }

    /** La plus récente pour `os`, toutes sources confondues ; à version égale, la première donnée. */
    fun latest(os: String, releases: List<BackendRelease>): BackendRelease? = releases
        .filter { it.os == os }
        .fold(null as BackendRelease?) { best, r -> if (best == null || compare(r.version, best.version) > 0) r else best }

    /**
     * `state` : null si l'ordinateur ne la donne pas. `trial` : l'appli d'essai, qui sert à essayer la mise à jour et
     * propose donc aussi par-dessus un essai posé.
     */
    fun decide(identity: Identity?, state: BackendState?, releases: List<BackendRelease>, trial: Boolean): Decision {
        if (identity == null) return Decision.Unreachable
        if (identity.version == "dev" || state?.packaged == false) return Decision.FromRepo
        val release = latest(identity.os, releases) ?: return Decision.NothingPublished
        if (!identity.backendUpdate) return Decision.NeedsInstaller(release)
        if (state?.mode == "essai" && !trial) return Decision.OnEssai
        val current = identity.version?.let { InstallerPlan.numbers(it) } ?: return Decision.Available(release)
        val diff = InstallerPlan.compare(InstallerPlan.numbers(release.version)!!, current)
        return when {
            diff > 0 -> Decision.Available(release)
            diff == 0 -> Decision.UpToDate
            else -> Decision.ComputerNewer(release)
        }
    }

    private fun compare(a: String, b: String) = InstallerPlan.compare(InstallerPlan.numbers(a)!!, InstallerPlan.numbers(b)!!)

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
}
