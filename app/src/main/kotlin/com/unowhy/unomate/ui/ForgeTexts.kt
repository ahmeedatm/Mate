package com.unowhy.unomate.ui

import com.unowhy.unomate.R
import com.unowhy.unomate.github.Forge
import com.unowhy.unomate.net.Pipeline

/** Textes qui changent d'une forge à l'autre (MR et pipelines GitLab, PR et contrôles GitHub) ; le reste est commun. */
class ForgeTexts(
    val mine: Int,
    val empty: Int,
    val connect: Int,
    val connectText: Int,
    val reconnectText: Int,
    val connectWaiting: Int,
    val boardTitle: Int,
    val boardEmpty: Int,
    private val success: Int,
    private val running: Int,
    private val failed: Int,
    private val canceled: Int,
    private val none: Int,
) {
    fun pipeline(p: Pipeline) = when (p) {
        Pipeline.SUCCESS -> success
        Pipeline.RUNNING -> running
        Pipeline.FAILED -> failed
        Pipeline.CANCELED -> canceled
        Pipeline.NONE -> none
    }
}

fun forgeTexts(forge: Forge) = when (forge) {
    Forge.GITLAB -> ForgeTexts(
        R.string.gitlab_mine, R.string.gitlab_empty, R.string.gitlab_connect, R.string.gitlab_connect_text,
        R.string.gitlab_reconnect_text, R.string.gitlab_connect_waiting, R.string.widget_pipelines, R.string.pipelines_empty,
        R.string.gitlab_pipeline_success, R.string.gitlab_pipeline_running, R.string.gitlab_pipeline_failed,
        R.string.gitlab_pipeline_canceled, R.string.gitlab_pipeline_none,
    )
    Forge.GITHUB -> ForgeTexts(
        R.string.github_mine, R.string.github_empty, R.string.github_connect, R.string.github_connect_text,
        R.string.github_reconnect_text, R.string.github_connect_waiting, R.string.widget_actions, R.string.actions_empty,
        R.string.github_checks_success, R.string.github_checks_running, R.string.github_checks_failed,
        R.string.github_checks_canceled, R.string.github_checks_none,
    )
}
