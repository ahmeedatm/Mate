package com.unowhy.unomate.github

import com.unowhy.unomate.gitlab.shortProject

/**
 * Forge de code dont un widget montre les demandes de fusion et les pipelines : GitLab (MR, pipelines) ou GitHub (pull
 * requests, runs GitHub Actions). Le compagnon rend les deux sous la même forme (backend/src/github.ts) : les vues sont
 * partagées, seuls les routes, les textes et la façon d'écrire une référence changent. `key` : préfixe des routes du
 * compagnon et section de sa page locale (`POST /local/open`).
 */
enum class Forge(val key: String) {
    GITLAB("gitlab"),
    GITHUB("github"),
}

/** Référence courte d'une demande de fusion : `app!12` pour une MR GitLab, `app#12` pour une PR GitHub. */
fun changeRef(forge: Forge, project: String, number: Int) = shortProject(project) + (if (forge == Forge.GITHUB) "#" else "!") + number

/** Adresse du compte sur la page locale de l'ordinateur, ouverte par la carte « Connecter » : la section de la forge. */
fun connectSection(forge: Forge) = forge.key
