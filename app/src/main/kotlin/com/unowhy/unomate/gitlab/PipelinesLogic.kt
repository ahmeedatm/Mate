package com.unowhy.unomate.gitlab

import com.unowhy.unomate.net.GitLabPipeline
import com.unowhy.unomate.net.Pipeline

/** Pastille d'état d'un pipeline : une icône par état, la couleur seule ne dit rien (daltonisme, thème Contraste élevé). */
enum class PipelineMark { CHECK, CROSS, SYNC, CANCELED, NONE }

fun markOf(status: Pipeline) = when (status) {
    Pipeline.SUCCESS -> PipelineMark.CHECK
    Pipeline.FAILED -> PipelineMark.CROSS
    Pipeline.RUNNING -> PipelineMark.SYNC
    Pipeline.CANCELED -> PipelineMark.CANCELED
    Pipeline.NONE -> PipelineMark.NONE
}

/** Durée ou âge en unités entières : le texte (« 6 min 12 s », « il y a 2 h ») se compose à l'écran, avec les ressources. */
enum class SpanUnit { SECONDS, MINUTES, HOURS, DAYS }

data class Span(val unit: SpanUnit, val value: Int, val rest: Int = 0)

/** Durée d'un pipeline : « 45 s », « 6 min 12 s » (`rest` = secondes), « 1 h 05 » (`rest` = minutes). */
fun durationSpan(sec: Int): Span {
    val s = sec.coerceAtLeast(0)
    return when {
        s < 60 -> Span(SpanUnit.SECONDS, s)
        s < 3600 -> Span(SpanUnit.MINUTES, s / 60, s % 60)
        else -> Span(SpanUnit.HOURS, s / 3600, s % 3600 / 60)
    }
}

/** Âge arrondi vers le bas à l'unité la plus grande : « il y a 4 min », « il y a 2 h », « il y a 3 j ». */
fun ageSpan(sec: Int): Span {
    val s = sec.coerceAtLeast(0)
    return when {
        s < 60 -> Span(SpanUnit.SECONDS, s)
        s < 3600 -> Span(SpanUnit.MINUTES, s / 60)
        s < 86_400 -> Span(SpanUnit.HOURS, s / 3600)
        else -> Span(SpanUnit.DAYS, s / 86_400)
    }
}

/** Ce que la ligne dit du temps : l'âge d'un pipeline en cours (« depuis 4 min »), la durée et l'âge d'un terminé, l'âge seul sans durée. */
sealed interface PipelineTime {
    data class Since(val age: Span) : PipelineTime
    data class Took(val duration: Span, val age: Span) : PipelineTime
    data class Ago(val age: Span) : PipelineTime
}

fun timeOf(p: GitLabPipeline): PipelineTime = when {
    p.status == Pipeline.RUNNING -> PipelineTime.Since(ageSpan(p.ageSec))
    p.durationSec != null -> PipelineTime.Took(durationSpan(p.durationSec), ageSpan(p.ageSec))
    else -> PipelineTime.Ago(ageSpan(p.ageSec))
}

/** « projet · ref » ; une ref longue (`fix/FACT-236-arrondi-tva`) est coupée par l'affichage. */
fun pipelineTitle(p: GitLabPipeline) = "${shortProject(p.project)} · ${p.ref}"

/** Jobs en échec sur une ligne : « tests-unitaires, tests-e2e » ; null hors échec ou sans nom. */
fun failedJobsText(p: GitLabPipeline): String? =
    if (p.status == Pipeline.FAILED && p.failedJobs.isNotEmpty()) p.failedJobs.joinToString(", ") else null

/** Lignes montrées : le compagnon trie déjà (en cours, échecs, le reste) ; la tablette n'en garde que 8. */
fun visiblePipelines(list: List<GitLabPipeline>) = list.take(MAX_PIPELINES)

const val MAX_PIPELINES = 8
