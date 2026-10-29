package com.unowhy.companion.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.unowhy.companion.R
import com.unowhy.companion.gitlab.approvalsText
import com.unowhy.companion.gitlab.canMerge
import com.unowhy.companion.gitlab.mrKey
import com.unowhy.companion.gitlab.shortProject
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.GitLabMr
import com.unowhy.companion.net.GitLabMrs
import com.unowhy.companion.net.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Onglet GitLab du Deck : deux colonnes, « Mes MR » et « À relire », une carte par MR ouverte (pipeline, approbations,
 * état de fusion). Toucher une carte ouvre la MR sur l'ordinateur ; « Fusionner » (appui maintenu) n'est actif que si
 * GitLab dit la MR prête, et renvoie le commit affiché : GitLab refuse si la MR a bougé depuis. Relue toutes les 30 s
 * tant que la vue est affichée. Autonome : aucun accès au Deck, posable ailleurs.
 */
class GitLabView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val status = TextView(context)
    private val headers = listOf(TextView(context), TextView(context))
    private val lists = listOf(LinearLayout(context), LinearLayout(context))

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var active = false
    private var shown: GitLabMrs? = null
    private var signature: List<Any?> = emptyList()

    /** Fusions en cours (clé de carte). */
    private val busy = HashSet<String>()

    /** Dernier échec d'une action par carte, gardé jusqu'à la disparition de la MR ou la prochaine tentative. */
    private val messages = HashMap<String, String>()

    /** Numéro de la dernière lecture lancée : la réponse d'une lecture dépassée est ignorée. */
    private var lastRead = 0

    init {
        orientation = VERTICAL
        visibility = GONE
        status.apply {
            textSize = 14f
            setTextColor(themeColor(context, R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val board = LinearLayout(context)
        for (i in lists.indices) {
            lists[i].orientation = VERTICAL
            headers[i].apply {
                textSize = 16f
                typeface = themeFont(context, R.attr.c_font_medium)
                gravity = Gravity.CENTER_VERTICAL
            }
            board.addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(headers[i], LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
                    addView(
                        ScrollView(context).apply {
                            isVerticalScrollBarEnabled = false
                            addView(lists[i])
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) },
                    )
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(12) },
            )
        }
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        showHeaders(0, 0)
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Affichée et relue en boucle, ou cachée. Sans changement d'état, la boucle en cours continue. */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        if (this.active == active) return
        this.active = active
        poll?.cancel()
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun refresh() {
        val id = ++lastRead
        try {
            val mrs = BackendClient(context).gitlab.mrs()
            if (id != lastRead) return
            status.visibility = GONE
            render(mrs)
        } catch (e: Exception) {
            if (id != lastRead) return
            // Les dernières cartes restent affichées : la lecture suivante rattrape.
            status.text = context.errorText(e)
            status.visibility = VISIBLE
        }
    }

    private fun render(mrs: GitLabMrs) {
        shown = mrs
        val keys = (mrs.mine + mrs.review).mapTo(HashSet()) { mrKey(it) }
        messages.keys.retainAll(keys)
        showHeaders(mrs.mine.size, mrs.review.size)
        // Tablette de 2 Go : les cartes ne sont reconstruites que si quelque chose d'affiché a changé.
        val sig = listOf(mrs, busy.toSet(), messages.toMap())
        if (sig == signature) return
        signature = sig
        fill(lists[0], mrs.mine)
        fill(lists[1], mrs.review)
    }

    private fun showHeaders(mine: Int, review: Int) {
        for ((header, pair) in headers.zip(listOf(R.string.gitlab_mine to mine, R.string.gitlab_review to review))) {
            header.text = context.getString(R.string.gitlab_col_header, context.getString(pair.first), pair.second)
            header.setTextColor(themeColor(context, if (pair.second == 0) R.attr.c_text_faint else R.attr.c_text))
        }
    }

    private fun fill(list: LinearLayout, mrs: List<GitLabMr>) {
        list.removeAllViews()
        if (mrs.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    setText(R.string.gitlab_empty)
                    textSize = 14f
                    setTextColor(themeColor(context, R.attr.c_text_faint))
                    setPadding(dp(4), dp(8), 0, 0)
                },
            )
            return
        }
        for (mr in mrs) {
            val card = LayoutInflater.from(context).inflate(R.layout.item_gitlab_mr, list, false)
            bind(card, mr)
            card.setOnClickListener { open(mr) }
            list.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (list.childCount > 0) topMargin = dp(8) })
        }
    }

    private fun bind(card: View, mr: GitLabMr) {
        val key = mrKey(mr)
        card.findViewById<TextView>(R.id.gitlab_ref).text = context.getString(R.string.gitlab_ref, shortProject(mr.project), mr.iid)
        card.findViewById<View>(R.id.gitlab_draft).visibility = if (mr.draft) VISIBLE else GONE
        card.findViewById<TextView>(R.id.gitlab_title).text = mr.title
        card.findViewById<TextView>(R.id.gitlab_author).text = context.getString(R.string.gitlab_author, mr.author)
        card.findViewById<View>(R.id.gitlab_pipeline_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColor(context, pipelineColor(mr.pipeline)))
        }
        card.findViewById<TextView>(R.id.gitlab_pipeline).setText(pipelineLabel(mr.pipeline))
        card.findViewById<TextView>(R.id.gitlab_approvals).apply {
            val approvals = approvalsText(mr.approvalsGiven, mr.approvalsRequired)
            text = when {
                approvals == null -> null
                mr.approvedBy.isEmpty() -> context.getString(R.string.gitlab_approvals, approvals)
                else -> context.getString(R.string.gitlab_approvals_by, approvals, mr.approvedBy.joinToString(", "))
            }
            visibility = if (approvals == null) GONE else VISIBLE
        }
        card.findViewById<TextView>(R.id.gitlab_status).text = mr.statusLabel
        card.findViewById<TextView>(R.id.gitlab_message).apply {
            text = messages[key]
            visibility = if (messages[key] == null) GONE else VISIBLE
        }
        bindMerge(card.findViewById(R.id.gitlab_actions), mr, key)
    }

    /** Bouton seulement sur une MR fusionnable (l'état dit pourquoi les autres ne le sont pas), grisé pendant la fusion. */
    private fun bindMerge(box: LinearLayout, mr: GitLabMr, key: String) {
        box.visibility = if (canMerge(mr, busy = false)) VISIBLE else GONE
        if (box.visibility == GONE) return
        val enabled = canMerge(mr, key in busy)
        box.addView(
            HoldButton(context).apply {
                setText(R.string.gitlab_merge)
                isEnabled = enabled
                alpha = if (enabled) 1f else DISABLED_ALPHA
                setOnClickListener { merge(mr) }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)),
        )
        if (enabled) {
            box.addView(
                TextView(context).apply {
                    setText(R.string.gitlab_merge_hint)
                    textSize = 13f
                    setTextColor(themeColor(context, R.attr.c_text_faint))
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) },
            )
        }
    }

    private fun pipelineColor(p: Pipeline) = when (p) {
        Pipeline.SUCCESS -> R.attr.c_ok
        Pipeline.RUNNING -> R.attr.c_warn
        Pipeline.FAILED -> R.attr.c_ko
        Pipeline.CANCELED, Pipeline.NONE -> R.attr.c_text_faint
    }

    private fun pipelineLabel(p: Pipeline) = when (p) {
        Pipeline.SUCCESS -> R.string.gitlab_pipeline_success
        Pipeline.RUNNING -> R.string.gitlab_pipeline_running
        Pipeline.FAILED -> R.string.gitlab_pipeline_failed
        Pipeline.CANCELED -> R.string.gitlab_pipeline_canceled
        Pipeline.NONE -> R.string.gitlab_pipeline_none
    }

    /** Redessine les cartes avec l'état courant (fusion en cours, message) sans attendre la lecture suivante. */
    private fun redraw() {
        shown?.let(::render)
    }

    private fun open(mr: GitLabMr) {
        val key = mrKey(mr)
        scope.launch {
            try {
                BackendClient(context).gitlab.open(mr.project, mr.iid)
            } catch (e: Exception) {
                messages[key] = context.errorText(e)
                redraw()
            }
        }
    }

    private fun merge(mr: GitLabMr) {
        val key = mrKey(mr)
        if (!busy.add(key)) return
        messages.remove(key)
        redraw()
        scope.launch {
            try {
                BackendClient(context).gitlab.merge(mr.project, mr.iid, mr.sha)
            } catch (e: Exception) {
                messages[key] = context.errorText(e)
            } finally {
                busy.remove(key)
            }
            // Réussie ou refusée (la MR a changé…) : la liste se relit tout de suite.
            refresh()
            redraw()
        }
    }

    private companion object {
        const val POLL_MS = 30_000L
        const val DISABLED_ALPHA = 0.4f
    }
}
