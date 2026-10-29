package com.unowhy.unomate.trial

import com.unowhy.unomate.net.BackendState

/** Choix à faire pour l'essai d'une MR, sans Android (testé dans app/src/test). */
object EssaiPlan {

    enum class Install { NONE, INSTALL, UNINSTALL_FIRST }

    /**
     * Android refuse une versionCode plus basse que celle installée : l'essai d'une MR au pipeline plus ancien
     * est désinstallé d'abord (device owner, sans confirmation). `installedCode` null = pas d'essai installé.
     * NONE seulement pour la MR installée (`installedMr`, 0 = inconnue) : un même code pour une autre MR se réinstalle.
     */
    fun install(entryCode: Long, installedCode: Long?, mr: Int = 0, installedMr: Int = 0): Install = when {
        installedCode == null || entryCode > installedCode -> Install.INSTALL
        entryCode == installedCode -> if (mr == installedMr) Install.NONE else Install.INSTALL
        else -> Install.UNINSTALL_FIRST
    }

    enum class Backend { NOTHING, SEND, RESTORE }

    /**
     * Backend de l'ordinateur à obtenir pour la MR `mr` : le sien s'il le touche (archive d'empreinte `sha256`), sinon
     * l'origine. Comparé à l'archive posée, pas seulement à la MR : une MR republiée renvoie son nouveau backend.
     */
    fun backend(hasBackend: Boolean, mr: Int, sha256: String?, state: BackendState): Backend = when {
        hasBackend -> if (state.mode == "essai" && state.mr == mr && sha256 != null && sha256.equals(state.sha256, ignoreCase = true)) {
            Backend.NOTHING
        } else {
            Backend.SEND
        }
        else -> if (state.mode == "essai") Backend.RESTORE else Backend.NOTHING
    }
}
