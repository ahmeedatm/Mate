package com.unowhy.unomate.installers

import java.io.File

/**
 * Installeurs gardés sur la tablette : un fichier par OS (`file` du manifeste) et `installed.json`, la liste de ceux qui
 * sont complets et vérifiés. Un téléchargement en cours est un `.part` : jamais servi.
 */
class InstallerStore(val dir: File) {

    private val state get() = File(dir, "installed.json")

    /** Installeurs complets : listés dans l'état et dont le fichier a la taille annoncée. */
    fun onDisk(): List<Installer> {
        val listed = runCatching { InstallerPlan.parse(state.readText()) }.getOrDefault(emptyList())
        return listed.filter { File(dir, it.file).let { f -> f.isFile && f.length() == it.size } }
    }

    fun part(installer: Installer): File = File(dir.apply { mkdirs() }, installer.file + ".part")

    /** Fichier vérifié en place, puis inscrit dans l'état (écrit à côté puis renommé : jamais à moitié). */
    fun commit(installer: Installer) {
        val target = File(dir, installer.file)
        check(part(installer).renameTo(target)) { "Renommage de ${installer.file} impossible" }
        save(onDisk().filter { it.file != installer.file } + installer)
    }

    /** Retire les fichiers de ces installeurs, et les `.part` oubliés. */
    fun remove(installers: List<Installer>) {
        installers.forEach { File(dir, it.file).delete() }
        save(onDisk().filter { d -> installers.none { it.file == d.file } })
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun save(installers: List<Installer>) {
        val tmp = File(dir, "installed.json.tmp")
        tmp.writeText(InstallerPlan.toJson(installers))
        check(tmp.renameTo(state)) { "Écriture de l'état des installeurs impossible" }
    }
}
