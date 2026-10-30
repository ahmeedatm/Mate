package app.mate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.mate.MainActivity
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.JiraColumn
import app.mate.net.JiraIssue
import app.mate.net.JiraIssues
import app.mate.net.JiraTransition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Onglet Jira du Deck : les tickets assignés à l'utilisateur, rangés dans les colonnes des tableaux Jira choisis (vides
 * comprises, rangées par l'ordinateur), en défilement horizontal si elles ne tiennent pas. Les terminés récents sont
 * atténués. Toucher un ticket ouvre un tiroir en bas : ses transitions possibles et « Ouvrir sur l'ordi ». Le bouton
 * « Tableaux » choisit les tableaux (la boîte s'ouvre seule tant qu'aucun n'est choisi, une fois par affichage).
 * Relu à l'ouverture puis chaque minute ; les colonnes ne sont recréées que si la liste a changé.
 */
class JiraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val status = TextView(context)
    private val board = LinearLayout(context)
    private val boardScroll = HorizontalScrollView(context)
    private val boardsButton = Button(context, null, 0, R.style.Chip)
    private var boardsDialog: JiraBoardsDialog? = null
    private var prompted = false
    private val scrim = View(context)
    private val drawer = LinearLayout(context)
    private val drawerKey = TextView(context)
    private val drawerSummary = TextView(context)
    private val drawerMessage = TextView(context)
    private val pills = LinearLayout(context)
    private val openButton = Button(context, null, 0, R.style.Chip)
    private lateinit var owner: LifecycleOwner
    private var poll: Job? = null
    private var shown: JiraIssues? = null
    /** Ticket dont le tiroir est ouvert (la réponse tardive d'un autre ticket est ignorée). */
    private var current: JiraIssue? = null
    private var busy = false

    init {
        visibility = GONE
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        status.apply {
            textSize = 14f
            setTextColor(themeColor(context, R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        boardsButton.apply {
            setText(R.string.jira_boards_button)
            textSize = 13f
            setOnClickListener { openBoards() }
        }
        content.addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(status, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(boardsButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        board.orientation = LinearLayout.HORIZONTAL
        // Remplit la largeur quand les colonnes tiennent (poids), défile sinon (largeur fixe) : voir layoutColumns.
        boardScroll.apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(board, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        content.addView(boardScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scrim.apply {
            setBackgroundResource(R.drawable.scrim)
            visibility = GONE
            setOnClickListener { closeDrawer() }
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildDrawer()
        addView(drawer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun buildDrawer() {
        drawer.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(themeRes(context, R.attr.c_card_bg))
            themePx(context, R.attr.c_card_pad).let { setPadding(it, it, it, it) }
            visibility = GONE
            // Les touches dans le tiroir ne passent pas au fond.
            isClickable = true
        }
        drawerKey.apply {
            textSize = 13f
            setTextColor(themeColor(context, R.attr.c_text_dim))
        }
        drawerSummary.apply {
            textSize = 18f
            typeface = themeFont(context, R.attr.c_font_medium)
            setTextColor(themeColor(context, R.attr.c_text))
        }
        drawerMessage.apply {
            textSize = 14f
            setTextColor(themeColor(context, R.attr.c_text_dim))
        }
        pills.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        openButton.apply {
            setText(R.string.open_on_computer)
            setOnClickListener { current?.let(::openOnComputer) }
        }
        drawer.addView(drawerKey, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        drawer.addView(drawerSummary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        drawer.addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(pills)
                    },
                    LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(openButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(12) })
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) },
        )
        drawer.addView(drawerMessage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    fun attach(owner: LifecycleOwner) {
        this.owner = owner
    }

    /** Affiché et relu à l'ouverture puis chaque minute, ou caché (plus aucune lecture). */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        poll?.cancel()
        poll = null
        if (!active) {
            prompted = false
            boardsDialog?.dismiss()
            return closeDrawer()
        }
        if (shown == null) showStatus(context.getString(R.string.loading))
        poll = owner.lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text == null) GONE else VISIBLE
    }

    private suspend fun refresh() {
        try {
            val issues = BackendClient(context).jira.issues()
            render(issues)
            if (!issues.boardsChosen && !prompted) {
                prompted = true
                openBoards()
            }
        } catch (e: Exception) {
            // La liste affichée reste ; l'erreur s'écrit au-dessus.
            showStatus(context.errorText(e))
        }
    }

    private fun openBoards() {
        if (boardsDialog?.isShowing == true) return
        boardsDialog = JiraBoardsDialog(context, owner.lifecycleScope) { owner.lifecycleScope.launch { refresh() } }.also { it.show() }
    }

    private fun render(issues: JiraIssues) {
        val columns = issues.columns
        if (issues == shown) {
            showStatus(if (columns.isEmpty()) context.getString(R.string.jira_empty) else null)
            return
        }
        val scrolls = HashMap<String, Int>()
        for (i in 0 until board.childCount) (board.getChildAt(i).tag as? String)?.let { scrolls[it] = columnScroll(board.getChildAt(i)) }
        val x = boardScroll.scrollX
        shown = issues
        board.removeAllViews()
        showStatus(if (columns.isEmpty()) context.getString(R.string.jira_empty) else null)
        if (columns.isNotEmpty()) Look.revealOnce(board)
        for ((i, column) in columns.withIndex()) {
            val view = columnView(column)
            board.addView(view, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(COLUMN_GAP_DP) })
            scrolls[column.title]?.let { y -> view.post { (view as LinearLayout).getChildAt(1).scrollTo(0, y) } }
        }
        layoutColumns()
        boardScroll.post { boardScroll.scrollTo(x, 0) }
    }

    /** Colonnes de largeur fixe qui défilent ; si elles tiennent toutes, elles se partagent la largeur. */
    private fun layoutColumns() {
        val n = board.childCount
        if (n == 0) return
        val fits = n * dp(COLUMN_DP) + (n - 1) * dp(COLUMN_GAP_DP) <= width
        for (i in 0 until n) {
            val child = board.getChildAt(i)
            child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (fits) 0 else dp(COLUMN_DP)
                weight = if (fits) 1f else 0f
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutColumns()
    }

    private fun columnScroll(column: View): Int = (column as? LinearLayout)?.getChildAt(1)?.scrollY ?: 0

    private fun columnView(column: JiraColumn): View {
        val cards = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((i, issue) in column.issues.withIndex()) {
            cards.addView(card(issue), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(8) })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = column.title
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.jira_col_header, column.title, column.issues.size)
                    textSize = 16f
                    typeface = themeFont(context, R.attr.c_font_medium)
                    setTextColor(themeColor(context, R.attr.c_text_dim))
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)),
            )
            addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(cards)
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) },
            )
        }
    }

    private fun card(issue: JiraIssue): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(12), dp(10), dp(12), dp(12))
        addView(TextView(context).apply {
            text = issue.key
            textSize = 12f
            setTextColor(themeColor(context, R.attr.c_text_dim))
        })
        addView(
            TextView(context).apply {
                text = issue.summary
                textSize = 15f
                setTextColor(themeColor(context, R.attr.c_text))
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) },
        )
        // Terminé récemment : atténué mais touchable comme les autres.
        if (issue.done) alpha = DONE_ALPHA
        setOnClickListener { openDrawer(issue) }
    }

    private fun openDrawer(issue: JiraIssue) {
        current = issue
        busy = false
        drawerKey.text = issue.key
        drawerSummary.text = issue.summary
        drawerMessage.visibility = GONE
        pills.removeAllViews()
        showMessage(context.getString(R.string.jira_transitions_loading))
        scrim.visibility = VISIBLE
        val wasOpen = drawer.isVisible
        drawer.visibility = VISIBLE
        if (!wasOpen) {
            drawer.alpha = 0f
            drawer.post {
                drawer.translationY = drawer.height.toFloat()
                drawer.alpha = 1f
                Look.springTo(drawer, DynamicAnimation.TRANSLATION_Y, Look.SPATIAL_FAST, 0f)
            }
        }
        owner.lifecycleScope.launch {
            try {
                val transitions = BackendClient(context).jira.transitions(issue.key)
                if (current?.key == issue.key) showTransitions(issue, transitions)
            } catch (e: Exception) {
                val text = context.errorText(e)
                if (current?.key == issue.key) showMessage(text)
            }
        }
    }

    private fun closeDrawer() {
        current = null
        drawer.visibility = GONE
        scrim.visibility = GONE
    }

    private fun showMessage(text: String) {
        drawerMessage.text = text
        drawerMessage.visibility = VISIBLE
    }

    private fun showTransitions(issue: JiraIssue, transitions: List<JiraTransition>) {
        pills.removeAllViews()
        if (transitions.isEmpty()) return showMessage(context.getString(R.string.jira_transitions_none))
        drawerMessage.visibility = GONE
        for ((i, t) in transitions.withIndex()) {
            val label = if (t.needsJira) context.getString(R.string.jira_pill_finish, t.name) else t.name
            pills.addView(
                Button(context, null, 0, if (i == 0) R.style.PrimaryButton else R.style.Chip).apply {
                    text = label
                    setOnClickListener { if (t.needsJira) openOnComputer(issue) else move(issue, t) }
                },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)).apply { if (i > 0) marginStart = dp(8) },
            )
        }
    }

    /** Une transition à la fois ; réussie : on ferme le tiroir et on relit. L'échec est dit en une phrase (409 : « à finir dans Jira »). */
    private fun move(issue: JiraIssue, t: JiraTransition) {
        if (busy) return
        busy = true
        for (i in 0 until pills.childCount) pills.getChildAt(i).isEnabled = false
        owner.lifecycleScope.launch {
            try {
                BackendClient(context).jira.transition(issue.key, t.id)
                (context as? MainActivity)?.showBanner(context.getString(R.string.jira_moved, issue.key, t.to.ifEmpty { t.name }), 3)
                closeDrawer()
                refresh()
            } catch (e: Exception) {
                val text = context.errorText(e)
                busy = false
                for (i in 0 until pills.childCount) pills.getChildAt(i).isEnabled = true
                (context as? MainActivity)?.showBanner(text, 6)
            }
        }
    }

    private fun openOnComputer(issue: JiraIssue) {
        owner.lifecycleScope.launch {
            try {
                BackendClient(context).jira.openOnComputer(issue.key)
                (context as? MainActivity)?.showBanner(context.getString(R.string.opened_on_computer), 3)
            } catch (e: Exception) {
                val text = context.errorText(e)
                (context as? MainActivity)?.showBanner(text, 6)
            }
        }
    }

    private companion object {
        const val POLL_MS = 60_000L
        const val COLUMN_DP = 280
        const val COLUMN_GAP_DP = 12
        const val DONE_ALPHA = 0.5f
    }
}
