package com.unowhy.companion.trial

import android.content.Context
import android.util.Log
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.UserFacingException
import com.unowhy.companion.kiosk.Updater
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Essai
import com.unowhy.companion.net.UpdatesApi
import java.io.File
import java.io.IOException

/**
 * Backend de l'ordinateur actif pour l'essai d'une MR. Les archives viennent du registre (jeton de lecture de la
 * tablette) et sont envoyées à l'ordinateur par son jeton d'appairage : aucun secret ne passe à l'ordinateur.
 * L'ordinateur garde seul la copie de son backend d'origine (backend/src/essai-backend.ts).
 */
object EssaiBackend {

    /**
     * Met l'ordinateur actif sur le backend de la MR `mr` (`hasBackend`), ou sur son origine sinon. `essai` : l'entrée
     * du registre si on l'a déjà, sinon relue. Sans ordinateur appairé, rien à faire. Sans backend à poser,
     * un ordinateur injoignable n'arrête rien : il n'y a rien à lui reprendre.
     */
    suspend fun sync(context: Context, mr: Int, hasBackend: Boolean, essai: Essai? = null) {
        val computer = Computers.active(context) ?: return
        val updates = BackendClient(context).updates
        if (!hasBackend) {
            restoreOrigin(context)
            return
        }
        val state = updates.backendState()
        val entry = essai ?: Updater.essais(context).firstOrNull { it.mr == mr }
        if (entry == null) {
            // MR retirée du registre : le backend déjà posé reste, il n'y a rien d'autre à envoyer.
            if (state.mode == "essai" && state.mr == mr) return
            throw UserFacingException(R.string.trial_backend_gone)
        }
        val archive = entry.backends[computer.os] ?: throw UserFacingException(R.string.trial_backend_os, computer.os)
        if (EssaiPlan.backend(true, mr, archive.sha256, state) == EssaiPlan.Backend.NOTHING) return
        val file = File(context.cacheDir, "updates").apply { mkdirs() }.resolve("backend.tar.gz")
        try {
            val api = UpdatesApi.essais() ?: throw UserFacingException(R.string.update_server_unreachable)
            val sha256 = api.download(archive.file, archive.size, file) {}
            if (!sha256.equals(archive.sha256, ignoreCase = true)) throw UserFacingException(R.string.update_file_corrupt)
            updates.sendBackend(mr, sha256, file.readBytes())
        } finally {
            file.delete()
        }
    }

    /** Remet le backend d'origine de l'ordinateur actif, sans erreur si on ne le joint pas (la stable le refera à son retour). */
    suspend fun restoreOrigin(context: Context) {
        if (Computers.active(context) == null) return
        try {
            BackendClient(context).updates.restoreBackend()
        } catch (e: IOException) {
            Log.w("EssaiBackend", "Backend d'origine non restauré", e)
        }
    }
}
