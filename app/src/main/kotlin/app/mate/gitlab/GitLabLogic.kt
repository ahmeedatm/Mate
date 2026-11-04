package app.mate.gitlab

import app.mate.github.Forge
import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.MrColumn

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

/** Colonnes du tableau : toutes celles de GitLab ; GitHub n'en a que deux, ses PR (`READY`) et celles à relire. */
fun boardColumns(forge: Forge): List<MrColumn> =
    if (forge == Forge.GITHUB) listOf(MrColumn.READY, MrColumn.REVIEW) else MrColumn.entries

/**
 * Les colonnes du tableau, toujours toutes (vides comprises), dans l'ordre de priorité : une MR à moi est dans la colonne
 * que le compagnon lui a donnée, « à review » reprend la liste des MR à relire. GitHub : mes PR dans une seule colonne.
 */
fun columnsOf(mrs: GitLabMrs, forge: Forge = Forge.GITLAB): List<Pair<MrColumn, List<GitLabMr>>> =
    boardColumns(forge).map { column ->
        column to when {
            column == MrColumn.REVIEW -> mrs.review
            forge == Forge.GITHUB -> mrs.mine
            else -> mrs.mine.filter { it.column == column }
        }
    }
