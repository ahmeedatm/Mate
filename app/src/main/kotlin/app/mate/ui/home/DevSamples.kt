package app.mate.ui.home

import app.mate.github.Forge
import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
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

/** Derniers runs d'exemple : planifications et pipelines de MR (GitLab), ou liste à plat (GitHub Actions). */
fun samplePipelines(forge: Forge): GitLabPipelines {
    fun run(id: Long, project: String, ref: String, status: Pipeline, duration: Int?, age: Int, title: String, mr: Int? = null) =
        GitLabPipeline(project, ref, id, status, duration, age, if (status == Pipeline.FAILED) listOf("tests:api") else emptyList(), title, mr)
    val runs = listOf(
        run(9081, "equipe/api", "main", Pipeline.RUNNING, null, 240, "Déploiement de recette"),
        run(9077, "equipe/facturation", "main", Pipeline.FAILED, 372, 1_800, "Tests de nuit"),
        run(9070, "equipe/api", "pagination-curseur", Pipeline.SUCCESS, 455, 5_400, "Pagination des factures par curseur", 408),
        run(9064, "equipe/app-mobile", "main", Pipeline.SUCCESS, 610, 10_800, "Build iOS et Android"),
    )
    return if (forge == Forge.GITHUB) GitLabPipelines(pipelines = runs.map { it.copy(mrIid = null) })
    else GitLabPipelines(schedules = runs.filter { it.mrIid == null }, mrs = runs.filter { it.mrIid != null })
}
