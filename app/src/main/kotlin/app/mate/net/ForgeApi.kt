package app.mate.net

/**
 * Ce que les widgets de forge (GitLab, GitHub) demandent au compagnon : demandes de fusion, pipelines (ou runs GitHub
 * Actions), ouverture sur l'ordinateur, fusion. Réponses de même forme pour les deux forges.
 */
interface ForgeApi {
    suspend fun mrs(): GitLabMrs
    suspend fun pipelines(): GitLabPipelines
    suspend fun open(project: String, iid: Int)
    suspend fun openPipeline(project: String, id: Long)
    suspend fun merge(project: String, iid: Int, sha: String)
}
