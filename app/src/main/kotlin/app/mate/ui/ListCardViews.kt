package app.mate.ui

import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.view.isVisible
import app.mate.R
import app.mate.ui.home.WidgetSize

/**
 * Vues du gabarit `view_tasks_cadran`, partagées par [TasksCard] et [ShoppingCard], rangées selon la forme posée
 * ([ListShape]) : la tête (anneau faits / total, nombre restant, légende), le filet, la liste en une ou plusieurs colonnes.
 */
class ListCardViews(private val card: LinearLayout) {
    private val context = card.context
    private val head: LinearLayout = card.findViewById(R.id.tasks_head)
    val ringBox: View = card.findViewById(R.id.tasks_ring_box)
    private val ring: ProgressRing = card.findViewById(R.id.tasks_ring)
    private val ringText: TextView = card.findViewById(R.id.tasks_ring_text)
    val title: View = card.findViewById(R.id.tasks_title)
    private val number: TextView = card.findViewById(R.id.tasks_number)
    private val caption: TextView = card.findViewById(R.id.tasks_count)
    private val rule: View = card.findViewById(R.id.tasks_rule)
    private val scroll: View = card.findViewById(R.id.tasks_scroll)
    val list: LinearLayout = card.findViewById(R.id.tasks_list)
    val status: TextView = card.findViewById(R.id.tasks_status)
    val addRow: View = card.findViewById(R.id.tasks_add_row)

    /** Ce que fait un toucher sur la tête, ajouté à sa description pour TalkBack (Tâches : les récurrentes). */
    var headAction: String? = null

    /** Forme rangée ; null tant que [apply] n'est pas passé (gabarit tel que dans le XML : Bloc). */
    private var applied: ListShape? = null
    val shape get() = applied ?: ListShape.of(WidgetSize.L)

    init {
        ring.color = themeColor(context, R.attr.c_ok)
        // Piste en `c_stroke` (3:1 testé) : la part restante se voit aussi en Rétro et en Contraste élevé.
        ring.trackColor = themeColor(context, R.attr.c_stroke)
        number.typeface = themeFont(context, R.attr.c_font_light)
        // Pastille et Ligne : la tête suit la hauteur réelle de la carte (92 dp au profil de rangées le plus serré).
        card.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) card.post { fitHead() }
        }
    }

    /** false : la forme n'a pas changé, rien n'est refait. */
    fun apply(shape: ListShape): Boolean {
        if (shape == applied) return false
        applied = shape
        val side = shape.sideHead
        card.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        head.layoutParams = LinearLayout.LayoutParams(if (side) WRAP_CONTENT else MATCH_PARENT, if (shape.list) WRAP_CONTENT else MATCH_PARENT)
        title.layoutParams = LinearLayout.LayoutParams(if (side) WRAP_CONTENT else 0, WRAP_CONTENT, if (side) 0f else 1f)
            .apply { marginStart = card.dp(HEAD_GAP_DP) }
        rule.isVisible = shape.list
        rule.layoutParams = if (side) {
            LinearLayout.LayoutParams(card.dp(1), MATCH_PARENT).apply { marginStart = card.dp(HEAD_GAP_DP) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, card.dp(1)).apply { topMargin = card.dp(12) }
        }
        scroll.isVisible = shape.list
        scroll.layoutParams = if (side) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = card.dp(HEAD_GAP_DP) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = card.dp(8) }
        }
        addRow.isVisible = shape.addRow
        fitHead()
        return true
    }

    /** `number` vide : rien de lu encore (la légende dit le titre ou l'erreur). */
    fun showHead(number: String, caption: String, done: Int, total: Int) {
        this.number.text = number
        this.number.isVisible = number.isNotEmpty()
        this.caption.text = caption
        ring.progress = if (total == 0) 0f else done.toFloat() / total
        // Au-delà de 99, « faits/total » ne tient plus dans l'anneau : l'arc seul le dit.
        ringText.text = if (total == 0 || total > MAX_RING_TOTAL) "" else context.getString(R.string.list_ring_progress, done, total)
        val said = if (number.isEmpty()) caption else "$number $caption"
        title.contentDescription = headAction?.let { "$said. $it" } ?: said
    }

    /** Range les lignes, ligne par ligne, en [ListShape.columns] colonnes ; sans cases, toucher la ligne coche. */
    fun fill(rows: List<View>) {
        list.removeAllViews()
        rows.forEach(::bindCheck)
        if (shape.columns <= 1) {
            rows.forEach(list::addView)
            return
        }
        for (line in ListShape.rows(rows, shape.columns)) {
            val box = LinearLayout(context)
            line.forEachIndexed { i, row ->
                val lp = LinearLayout.LayoutParams(row.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    width = 0
                    weight = 1f
                    marginEnd = if (i < shape.columns - 1) card.dp(COLUMN_GAP_DP) else 0
                }
                box.addView(row, lp)
            }
            repeat(shape.columns - line.size) { box.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            list.addView(box)
        }
    }

    private fun bindCheck(row: View) {
        if (shape.checks) return
        val check = row.findViewById<View>(R.id.task_check)
        check.visibility = View.GONE
        // Une case sans écouteur (tâche cochée par Jira) : la ligne garde son propre toucher, rien de muet.
        if (check.hasOnClickListeners()) row.setOnClickListener { check.performClick() }
    }

    /** Tête compacte quand la carte est basse (Pastille, Ligne) : anneau, chiffre et légende tiennent dans la hauteur. */
    private fun fitHead() {
        val inner = card.height - card.paddingTop - card.paddingBottom
        val compact = shape.compact((inner / context.resources.displayMetrics.density).toInt())
        val ringSize = if (compact) inner.coerceAtMost(card.dp(RING_DP)) else card.dp(RING_DP)
        ringBox.layoutParams.let {
            if (it.width != ringSize) {
                it.width = ringSize
                it.height = ringSize
                ringBox.layoutParams = it
            }
        }
        number.textSize = if (compact) 24f else 34f
        ringText.textSize = if (compact) 10f else 13f
        widenRingTarget(compact)
        caption.maxLines = if (compact) 1 else 2
        // Compacte : « 5 tâches restantes » sur une ligne, la légende alignée sur le pied du chiffre.
        (title as LinearLayout).orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        caption.layoutParams = if (compact) {
            // Ligne : la tête est à sa largeur, la légende aussi ; Pastille : la légende prend la place qui reste.
            LinearLayout.LayoutParams(if (shape.sideHead) WRAP_CONTENT else 0, WRAP_CONTENT, if (shape.sideHead) 0f else 1f)
                .apply { marginStart = card.dp(8) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = card.dp(4) }
        }
    }

    /** Anneau resserré (moins de 48 dp) : sa zone de toucher s'étend à 48 dp autour de lui. */
    private fun widenRingTarget(compact: Boolean) {
        if (!compact) {
            head.touchDelegate = null
            return
        }
        head.post {
            val hit = Rect()
            ringBox.getHitRect(hit)
            val extra = ((card.dp(MIN_TARGET_DP) - hit.height()) / 2).coerceAtLeast(0)
            hit.inset(-extra, -extra)
            head.touchDelegate = TouchDelegate(hit, ringBox)
        }
    }

    private companion object {
        const val RING_DP = 56
        const val MIN_TARGET_DP = 48
        const val MAX_RING_TOTAL = 99
        const val HEAD_GAP_DP = 16
        const val COLUMN_GAP_DP = 16
    }
}
