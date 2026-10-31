package app.mate.gitlab

import app.mate.net.GitLabMr

/** Clé d'une carte : le chemin complet du projet, pas son nom court (deux projets peuvent avoir le même). */
fun mrKey(mr: GitLabMr) = "${mr.project}!${mr.iid}"

/** Fusion proposée : GitLab dit prête, pas un brouillon, un commit connu (garde-fou de la fusion), aucune fusion en cours. */
fun canMerge(mr: GitLabMr, busy: Boolean) = mr.mergeable && !mr.draft && mr.sha.isNotEmpty() && !busy

/** Bouton d'une carte : fusion par appui maintenu, ouverture sur l'ordinateur (connexion en lecture seule), ou rien. */
enum class MergeAction { NONE, HOLD, OPEN_ON_COMPUTER }

/** MR prête (`canMerge(mr)`) : `canMerge` (accès en écriture côté ordinateur) choisit entre fusionner ici et fusionner sur l'ordi. */
fun mergeAction(mr: GitLabMr, canMerge: Boolean) = when {
    !canMerge(mr, busy = false) -> MergeAction.NONE
    canMerge -> MergeAction.HOLD
    else -> MergeAction.OPEN_ON_COMPUTER
}

/** Seule la fusion peut être « en cours » : ouvrir la MR sur l'ordi reste toujours possible. */
fun mergeEnabled(action: MergeAction, busy: Boolean) = action != MergeAction.HOLD || !busy

/** « 1/2 » ; null quand aucune approbation n'est exigée. */
fun approvalsText(given: Int, required: Int): String? = if (required == 0) null else "$given/$required"

/** Dernier segment du chemin (`softwares/products/uem` → `uem`) : lisible dans un titre de carte. */
fun shortProject(fullPath: String) = fullPath.substringAfterLast('/')
