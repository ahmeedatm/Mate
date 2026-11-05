package app.mate.ui.home

import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.MrColumn
import app.mate.net.Pipeline

/** Données d'exemple des widgets Dev (aperçu du catalogue) : sans réseau, sans ordinateur, crédibles. */
fun sampleMrs(): GitLabMrs {
    fun mr(iid: Int, project: String, title: String, column: MrColumn, pipeline: Pipeline, author: String = "moi") =
        GitLabMr(project, iid, title, column == MrColumn.DRAFT, author, pipeline, 1, 2, emptyList(), "", false, "", column)
    return GitLabMrs(
        mine = listOf(
            mr(412, "equipe/facturation", "Arrondi des remises au centime", MrColumn.MERGE, Pipeline.SUCCESS),
            mr(408, "equipe/api", "Pagination des factures par curseur", MrColumn.FIX, Pipeline.FAILED),
            mr(415, "equipe/app-mobile", "Écran de paiement hors ligne", MrColumn.DRAFT, Pipeline.RUNNING),
        ),
        review = listOf(
            mr(97, "equipe/api", "Limiter les exports CSV à 10 000 lignes", MrColumn.REVIEW, Pipeline.SUCCESS, "Eliot"),
            mr(221, "equipe/facturation", "Relances par mail à J+7", MrColumn.REVIEW, Pipeline.RUNNING, "Bruno"),
        ),
    )
}
