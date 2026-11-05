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
import app.mate.gitlab.PipelineTime
import app.mate.gitlab.Span
import app.mate.gitlab.SpanUnit
import app.mate.gitlab.failedJobsText
import app.mate.gitlab.markOf
import app.mate.gitlab.pipelineTitle
import app.mate.gitlab.timeOf
import app.mate.gitlab.visiblePipelines
import app.mate.gitlab.withoutTimes
import app.mate.net.BackendClient
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Console de déploiement (widget Pipelines), deux sections : « Planifiées » (dernier run des planifications que l'utilisateur
 * a choisies par le ⚙ du widget) et « Mes MR » (pipelines des MR ouvertes qui lui sont assignées). Chaque ligne : état en
 * icône et en couleur (vert réussi, jaune en cours, rouge échec), titre, durée ou âge, et les stages en petites bulles.
 * Toucher une ligne ouvre le pipeline sur l'ordinateur. Lecture seule. Côte à côte quand la place le permet, l'une sous
 * l'autre sinon. Relue à l'ouverture puis toutes les 30 s tant que la vue est affichée ([reload] la relit tout de suite).
 * Ordinateur sans compte GitLab : la carte « Connecter GitLab » (relue toutes les 3 s en attendant). Autonome, comme [GitLabView].
 * Sert aussi au widget Actions (`forge` GitHub) : une seule liste, le dernier run GitHub Actions de la branche par défaut des
 * dépôts de mes PR.
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
    private var sideBySide = false
    private val scroll = ScrollView(context)
    private val empty = TextView(context)
    private val connectCard = ConnectCard(context, forgeConnect(forge), { scope }, ::showError, compact = true)

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var active = false
    private var connected = true

    /** Dernière lecture affichée : les lignes ne sont reconstruites que si elles changent (tablette de 2 Go). */
    private var shown: GitLabPipelines? = null

    /** Lignes affichées, dans l'ordre de la lecture : seul leur texte de temps est mis à jour quand rien d'autre ne change. */
    private val rowViews = ArrayList<View>()

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

    /** Relit tout de suite (le choix des planifications vient de changer). */
    fun reload() {
        if (!active) return
        scope.launch { refresh() }
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
        if (forge == Forge.GITHUB) renderFlat(answer) else renderSections(answer)
    }

    /**
     * Même lecture que l'affichée, aux temps près : les lignes restent, seuls leur âge et leur durée sont réécrits. Sinon
     * la lecture devient celle affichée et les lignes sont à refaire.
     */
    private fun keepRows(answer: GitLabPipelines): Boolean {
        val before = shown
        shown = answer
        if (before == null || withoutTimes(before) != withoutTimes(answer)) {
            rowViews.clear()
            return false
        }
        val pipelines = if (forge == Forge.GITHUB) visiblePipelines(answer.pipelines) else answer.schedules + answer.mrs
        for ((row, p) in rowViews.zip(pipelines)) showTime(row, p)
        return true
    }

    /** GitHub : une liste de runs, en [columns] colonnes quand la place le permet. */
    private fun renderFlat(answer: GitLabPipelines) {
        val rows = visiblePipelines(answer.pipelines)
        empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        scroll.visibility = if (rows.isEmpty()) GONE else VISIBLE
        if (keepRows(answer)) return
        list.removeAllViews()
        list.orientation = HORIZONTAL
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

    /** GitLab : « Planifiées » et « Mes MR », côte à côte ([sideBySide]) ou l'une sous l'autre. */
    private fun renderSections(answer: GitLabPipelines) {
        scroll.visibility = VISIBLE
        if (keepRows(answer)) return
        list.removeAllViews()
        list.orientation = if (sideBySide) HORIZONTAL else VERTICAL
        val sections = listOf(
            Triple(R.string.pipelines_section_schedules, answer.schedules, R.string.pipelines_schedules_empty),
            Triple(R.string.pipelines_section_mrs, answer.mrs, R.string.pipelines_mrs_empty),
        )
        for ((i, entry) in sections.withIndex()) {
            val (name, rows, emptyText) = entry
            val view = sectionView(name, rows, emptyText)
            val params = if (sideBySide) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (i > 0) {
                if (sideBySide) params.marginStart = dp(SECTION_GAP_DP) else params.topMargin = dp(SECTION_GAP_DP)
            }
            list.addView(view, params)
        }
        Look.revealOnce(list)
    }

    /** Un titre avec le nombre de lignes, puis les lignes ou, sans ligne, la phrase qui dit quoi faire. */
    private fun sectionView(name: Int, rows: List<GitLabPipeline>, emptyText: Int): View {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        box.addView(
            TextView(context).apply {
                text = context.getString(R.string.gitlab_col_header, context.getString(name), rows.size)
                textSize = 16f
                typeface = themeFont(context, R.attr.c_font_medium)
                setTextColor(themeColor(context, if (rows.isEmpty()) R.attr.c_text_faint else R.attr.c_text))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        if (rows.isEmpty()) {
            box.addView(
                TextView(context).apply {
                    text = context.getString(emptyText, context.getString(R.string.home_edit_open))
                    textSize = 14f
                    setTextColor(themeColor(context, R.attr.c_text_dim))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
            )
        }
        for (p in rows) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_pipeline, box, false)
            bind(row, p)
            row.setOnClickListener { open(p) }
            box.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        return box
    }

    // GitHub : une colonne de plus tous les COLUMN_DP. GitLab : les deux sections côte à côte dès que chacune a de quoi respirer.
    // Une ligne ou une section seule dans 1 000 dp de large serait vide à droite.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wantedColumns = (w / dp(COLUMN_DP)).coerceIn(1, MAX_COLUMNS)
        val wide = w >= dp(SIDE_BY_SIDE_DP)
        if (wantedColumns == columns && wide == sideBySide) return
        columns = wantedColumns
        sideBySide = wide
        // Même lecture, autre rangement : la dernière est redessinée.
        shown?.let { answer ->
            shown = null
            render(answer)
        }
    }

    private fun bind(row: View, p: GitLabPipeline) {
        val color = ColorStateList.valueOf(themeColor(context, pipelineColorAttr(p.status)))
        row.findViewById<ImageView>(R.id.pipeline_mark).apply {
            setImageResource(pipelineMarkIcon(markOf(p.status)))
            ImageViewCompat.setImageTintList(this, color)
        }
        row.findViewById<TextView>(R.id.pipeline_title).text = pipelineTitle(p)
        row.findViewById<StageBubblesView>(R.id.pipeline_stages).show(p.stages)
        row.findViewById<TextView>(R.id.pipeline_jobs).apply {
            text = failedJobsText(p)
            visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        }
        showTime(row, p)
        rowViews += row
    }

    private fun showTime(row: View, p: GitLabPipeline) {
        val time = pipelineTimeText(context, timeOf(p))
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

    private companion object {
        const val POLL_MS = 30_000L
        const val CONNECT_POLL_MS = 3_000L
        const val COLUMN_DP = 440
        const val COLUMN_GAP_DP = 12
        const val MAX_COLUMNS = 2
        const val SIDE_BY_SIDE_DP = 880
        const val SECTION_GAP_DP = 16
    }
}

/** « depuis 4 min », « 6 min 12 s · il y a 2 h », « il y a 3 j » : le temps d'une ligne de pipeline, au tableau comme en petite forme. */
internal fun pipelineTimeText(context: Context, t: PipelineTime): String = when (t) {
    is PipelineTime.Since -> context.getString(R.string.pipelines_since, spanText(context, t.age))
    is PipelineTime.Took -> context.getString(R.string.pipelines_took, spanText(context, t.duration), spanText(context, t.age))
    is PipelineTime.Ago -> context.getString(R.string.pipelines_ago, spanText(context, t.age))
}

/** « 45 s », « 6 min 12 s », « 4 min », « 1 h 05 », « 2 h », « 3 j » : `rest` (secondes ou minutes) seulement s'il n'est pas nul. */
private fun spanText(context: Context, s: Span) = when (s.unit) {
    SpanUnit.SECONDS -> context.getString(R.string.pipelines_sec, s.value)
    SpanUnit.MINUTES -> if (s.rest == 0) context.getString(R.string.pipelines_min, s.value) else context.getString(R.string.pipelines_min_sec, s.value, s.rest)
    SpanUnit.HOURS -> if (s.rest == 0) context.getString(R.string.pipelines_h, s.value) else context.getString(R.string.pipelines_h_min, s.value, s.rest)
    SpanUnit.DAYS -> context.getString(R.string.pipelines_d, s.value)
}
