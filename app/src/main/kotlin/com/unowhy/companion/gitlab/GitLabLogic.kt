package com.unowhy.companion.gitlab

import com.unowhy.companion.net.GitLabMr

/** Clé d'une carte : le chemin complet du projet, pas son nom court (deux projets peuvent avoir le même). */
fun mrKey(mr: GitLabMr) = "${mr.project}!${mr.iid}"

/** Fusion proposée : GitLab dit prête, pas un brouillon, un commit connu (garde-fou de la fusion), aucune fusion en cours. */
fun canMerge(mr: GitLabMr, busy: Boolean) = mr.mergeable && !mr.draft && mr.sha.isNotEmpty() && !busy

/** « 1/2 » ; null quand aucune approbation n'est exigée. */
fun approvalsText(given: Int, required: Int): String? = if (required == 0) null else "$given/$required"

/** Dernier segment du chemin (`softwares/products/uem` → `uem`) : lisible dans un titre de carte. */
fun shortProject(fullPath: String) = fullPath.substringAfterLast('/')
