package app.mate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.github.Forge
import app.mate.gitlab.PipelineMark
import app.mate.gitlab.PipelineTime
import app.mate.gitlab.Span
import app.mate.gitlab.SpanUnit
import app.mate.gitlab.failedJobsText
import app.mate.gitlab.markOf
import app.mate.gitlab.pipelineTitle
import app.mate.gitlab.timeOf
import app.mate.gitlab.visiblePipelines
import app.mate.net.BackendClient
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Console de déploiement (widget Pipelines) : une ligne par projet des MR de l'utilisateur, avec le dernier pipeline de sa
 * branche par défaut (en cours, échecs, le reste), état en icône et en couleur. Toucher une ligne ouvre le pipeline sur
 * l'ordinateur. Lecture seule. Relue à l'ouverture puis toutes les 30 s tant que la vue est affichée. Ordinateur sans
 * compte GitLab : la carte « Connecter GitLab » (relue toutes les 3 s en attendant). Autonome, comme [GitLabView]. Sert aussi
 * au widget Actions (`forge` GitHub) : dernier run GitHub Actions de la branche par défaut des dépôts de mes PR.
 */
class PipelinesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val forge: Forge = Forge.GITLAB,
) : LinearLayout(context, attrs) {

    private val texts = forgeTexts(forge)

    private val title = TitleView(context)
    private val status = TextView(context)
    /** Les lignes, rangées dans [columns] colonnes côte à côte quand la place le permet (widget de 3 cases ou plus). */
    private val list = LinearLayout(context)
    private var columns = 1
    private val scroll = ScrollView(context)
    private val empty = TextView(context)
    private val connectCard = GitLabConnectCard(context, { scope }, ::showError, compact = true, forge = forge)

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var active = false
    private var connected = true

    /** Dernière lecture affichée : les lignes ne sont reconstruites que si elle change (tablette de 2 Go). */
    private var shown: GitLabPipelines? = null

    /** Numéro de la dernière lecture lancée : la réponse d'une lecture dépassée est ignorée. */
    private var lastRead = 0

    init {
        orientation = VERTICAL
        visibility = GONE
        title.apply {
            setText(texts.boardTitle)
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.apply {
            textSize = 14f
            setTextColor(themeColor(context, R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        list.orientation = HORIZONTAL
        scroll.apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        empty.apply {
            setText(texts.boardEmpty)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(themeColor(context, R.attr.c_text_faint))
            visibility = GONE
        }
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(connectCard, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Affichée et relue en boucle, ou cachée (plus aucun appel). Sans changement d'état, la boucle en cours continue. */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        if (this.active == active) return
        this.active = active
        poll?.cancel()
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(if (connected) POLL_MS else CONNECT_POLL_MS)
            }
        }
    }

    private suspend fun refresh() {
        val id = ++lastRead
        try {
            val answer = BackendClient(context).forge(forge).pipelines()
            if (id != lastRead) return
            status.visibility = GONE
            showConnected(answer.connected)
            if (answer.connected) render(answer) else connectCard.show(answer.expired)
        } catch (e: Exception) {
            if (id != lastRead) return
            // Les dernières lignes restent affichées : la lecture suivante rattrape.
            showError(e)
        }
    }

    private fun showError(e: Exception) {
        status.text = context.errorText(e)
        status.visibility = VISIBLE
    }

    private fun showConnected(connected: Boolean) {
        this.connected = connected
        connectCard.visibility = if (connected) GONE else VISIBLE
        if (connected) {
            connectCard.reset()
            return
        }
        scroll.visibility = GONE
        empty.visibility = GONE
        shown = null
    }

    private fun render(answer: GitLabPipelines) {
        val rows = visiblePipelines(answer.pipelines)
        empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        scroll.visibility = if (rows.isEmpty()) GONE else VISIBLE
        if (answer == shown) return
        shown = answer
        list.removeAllViews()
        val stacks = List(columns) { LinearLayout(context).apply { orientation = VERTICAL } }
        for ((i, stack) in stacks.withIndex()) list.addView(stack, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(COLUMN_GAP_DP) })
        // Les plus récentes en haut de chaque colonne : une ligne sur deux dans la seconde.
        for ((i, p) in rows.withIndex()) {
            val stack = stacks[i % columns]
            val row = LayoutInflater.from(context).inflate(R.layout.item_pipeline, stack, false)
            bind(row, p)
            row.setOnClickListener { open(p) }
            stack.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (stack.childCount > 0) topMargin = dp(6) })
        }
        Look.revealOnce(list)
    }

    // Une colonne de plus tous les COLUMN_DP : une ligne seule dans 1 000 dp de large serait vide à droite.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wanted = (w / dp(COLUMN_DP)).coerceIn(1, MAX_COLUMNS)
        if (wanted == columns) return
        columns = wanted
        // Même lecture, autre rangement : la dernière est redessinée.
        shown?.let { answer ->
            shown = null
            render(answer)
        }
    }

    private fun bind(row: View, p: GitLabPipeline) {
        val color = ColorStateList.valueOf(themeColor(context, colorOf(p.status)))
        row.findViewById<ImageView>(R.id.pipeline_mark).apply {
            setImageResource(iconOf(markOf(p.status)))
            ImageViewCompat.setImageTintList(this, color)
        }
        row.findViewById<TextView>(R.id.pipeline_title).text = pipelineTitle(p)
        row.findViewById<TextView>(R.id.pipeline_jobs).apply {
            text = failedJobsText(p)
            visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        }
        val time = timeText(timeOf(p))
        row.findViewById<TextView>(R.id.pipeline_time).text = time
        // TalkBack lit l'état (l'icône n'est pas lue) puis la ligne.
        row.contentDescription = context.getString(R.string.pipelines_row_cd, context.getString(texts.pipeline(p.status)), pipelineTitle(p), time)
    }

    private fun open(p: GitLabPipeline) {
        scope.launch {
            try {
                BackendClient(context).forge(forge).openPipeline(p.project, p.id)
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun timeText(t: PipelineTime): String = when (t) {
        is PipelineTime.Since -> context.getString(R.string.pipelines_since, spanText(t.age))
        is PipelineTime.Took -> context.getString(R.string.pipelines_took, spanText(t.duration), spanText(t.age))
        is PipelineTime.Ago -> context.getString(R.string.pipelines_ago, spanText(t.age))
    }

    /** « 45 s », « 6 min 12 s », « 4 min », « 1 h 05 », « 2 h », « 3 j » : `rest` (secondes ou minutes) seulement s'il n'est pas nul. */
    private fun spanText(s: Span) = when (s.unit) {
        SpanUnit.SECONDS -> context.getString(R.string.pipelines_sec, s.value)
        SpanUnit.MINUTES -> if (s.rest == 0) context.getString(R.string.pipelines_min, s.value) else context.getString(R.string.pipelines_min_sec, s.value, s.rest)
        SpanUnit.HOURS -> if (s.rest == 0) context.getString(R.string.pipelines_h, s.value) else context.getString(R.string.pipelines_h_min, s.value, s.rest)
        SpanUnit.DAYS -> context.getString(R.string.pipelines_d, s.value)
    }

    private fun colorOf(p: Pipeline) = when (p) {
        Pipeline.SUCCESS -> R.attr.c_ok
        Pipeline.RUNNING -> R.attr.c_warn
        Pipeline.FAILED -> R.attr.c_ko
        Pipeline.CANCELED, Pipeline.NONE -> R.attr.c_text_faint
    }

    // En cours : icône fixe, pas d'animation (tablette lente, le kiosque coupe les animateurs).
    private fun iconOf(m: PipelineMark) = when (m) {
        PipelineMark.CHECK -> R.drawable.ic_tile_check_circle
        PipelineMark.CROSS -> R.drawable.ic_close
        PipelineMark.SYNC -> R.drawable.ic_refresh
        PipelineMark.CANCELED -> R.drawable.ic_tile_block
        PipelineMark.NONE -> R.drawable.ic_remove
    }

    private companion object {
        const val POLL_MS = 30_000L
        const val CONNECT_POLL_MS = 3_000L
        const val COLUMN_DP = 440
        const val COLUMN_GAP_DP = 12
        const val MAX_COLUMNS = 2
    }
}
