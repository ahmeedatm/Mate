package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.gitlab.markOf
import app.mate.net.Pipeline
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.pipelineColorAttr
import app.mate.ui.pipelineMarkIcon
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/** Une ligne d'une petite forme : titre, détail (état, durée, colonne), pastille d'état ; `open` : toucher la ligne. */
data class GlanceLine(val title: String, val detail: String, val state: Pipeline, val open: (() -> Unit)? = null)

/** Ce qu'une petite forme montre : le compteur et ce qu'il compte, l'état qui mène et son nom, les lignes du plus urgent au moins urgent. */
data class GlanceContent(
    val count: Int,
    val countLabel: String,
    val lead: Pipeline,
    val leadLabel: String,
    val lines: List<GlanceLine>,
)

/** Variantes à départager (#185) : A un grand nombre, B l'état en grand, C l'élément le plus urgent en clair. */
enum class GlanceStyle { COUNTER, STATUS, NEXT }

/**
 * Petites formes (Pastille, Ligne, Colonne) des widgets Dev : titre du widget, puis le compteur, l'état ou l'élément le
 * plus urgent selon [GlanceStyle]. Les grandes formes gardent le tableau complet. Pas de réseau ici : le widget, ou
 * l'aperçu du catalogue, donne le contenu.
 */
class DevGlanceView(context: Context, private val title: Int) : LinearLayout(context) {
    var style = GlanceStyle.COUNTER

    init {
        orientation = VERTICAL
    }

    /** `w` × `h` : la forme en cases ; `note` : phrase à la place du contenu (vide, erreur, à connecter). */
    fun show(content: GlanceContent?, w: Int, h: Int, note: String? = null) {
        removeAllViews()
        addView(TitleView(context).apply { setText(title); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (content == null || note != null) {
            addView(text(note.orEmpty(), 14f, R.attr.c_text_dim, lines = 4), fill())
            return
        }
        val rows = (if (w == 1) h * 2 else w - 1).coerceAtLeast(0)
        when (style) {
            GlanceStyle.COUNTER -> counter(content, if (w == 1 && h == 1) 0 else rows)
            GlanceStyle.STATUS -> status(content, w == 1 && h == 1, rows)
            GlanceStyle.NEXT -> next(content, w == 1 && h == 1, rows)
        }
    }

    private fun counter(c: GlanceContent, rows: Int) {
        addView(text(c.count.toString(), if (rows == 0) 44f else 34f, R.attr.c_text, font = R.attr.c_font_light).apply { gravity = Gravity.START or Gravity.BOTTOM }, if (rows == 0) fill() else wrap())
        addView(text(c.countLabel, 13f, R.attr.c_text_dim))
        if (rows > 0) c.lines.take(rows).forEach { addView(line(it, twoLines = false), wrap(top = 8)) }
        if (rows > 0) addView(View(context), fill())
    }

    private fun status(c: GlanceContent, pill: Boolean, rows: Int) {
        if (pill) {
            val color = themeColor(context, pipelineColorAttr(c.lead))
            addView(ImageView(context).apply { setImageDrawable(Look.badge(context, pipelineMarkIcon(markOf(c.lead)), color, sizeDp = BADGE_DP, iconDp = BADGE_ICON_DP)) }, LayoutParams(context.dp(BADGE_DP), context.dp(BADGE_DP)).apply { topMargin = context.dp(8) })
            addView(View(context), fill())
            addView(text(c.leadLabel, 15f, pipelineColorAttr(c.lead), font = R.attr.c_font_medium))
            addView(text("${c.count} ${c.countLabel}", 13f, R.attr.c_text_dim))
            return
        }
        c.lines.take(rows.coerceAtMost(4)).forEach { addView(bar(it), wrap(top = 8)) }
        addView(View(context), fill())
    }

    private fun next(c: GlanceContent, pill: Boolean, rows: Int) {
        val first = c.lines.firstOrNull()
        if (first == null) {
            addView(text(c.countLabel, 14f, R.attr.c_text_dim), fill())
            return
        }
        val more = c.lines.size - 1
        addView(
            FrameLayout(context).apply {
                addView(text(first.title, 16f, R.attr.c_text, lines = if (pill) 3 else 4, font = R.attr.c_font_medium), FrameLayout.LayoutParams(-1, -2).apply { marginEnd = context.dp(if (more > 0 && pill) 28 else 0) })
                if (more > 0 && pill) addView(text("+$more", 13f, R.attr.c_text_dim), FrameLayout.LayoutParams(-2, -2, Gravity.END))
            },
            wrap(top = 6),
        )
        if (pill) addView(View(context), fill())
        addView(line(first.copy(title = first.detail), twoLines = false), wrap(top = 6))
        if (pill) return
        c.lines.drop(1).take(rows - 1).forEach { addView(line(it, twoLines = false), wrap(top = 8)) }
        addView(View(context), fill())
    }

    /** Pastille d'état puis titre sur une ligne : l'icône dit l'état, la couleur seule ne suffit pas. */
    private fun line(l: GlanceLine, twoLines: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(mark(l), LayoutParams(context.dp(16), context.dp(16)))
        addView(text(l.title, 14f, R.attr.c_text, lines = if (twoLines) 2 else 1), LayoutParams(0, -2, 1f).apply { marginStart = context.dp(8) })
        contentDescription = "${l.title}, ${l.detail}"
        l.open?.let { open -> setOnClickListener { open() } }
    }

    /** Barre d'état : un trait de la couleur de l'état, le titre et le détail dessous. */
    private fun bar(l: GlanceLine) = LinearLayout(context).apply {
        addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = context.dp(2).toFloat(); setColor(themeColor(context, pipelineColorAttr(l.state))) } }, LayoutParams(context.dp(4), -1))
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(text(l.title, 14f, R.attr.c_text))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(mark(l), LayoutParams(context.dp(12), context.dp(12)))
                    addView(text(l.detail, 12f, R.attr.c_text_dim), LayoutParams(-1, -2).apply { marginStart = context.dp(4) })
                })
            },
            LayoutParams(0, -2, 1f).apply { marginStart = context.dp(8) },
        )
        contentDescription = "${l.title}, ${l.detail}"
        l.open?.let { open -> setOnClickListener { open() } }
    }

    private fun mark(l: GlanceLine) = ImageView(context).apply {
        setImageResource(pipelineMarkIcon(markOf(l.state)))
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, pipelineColorAttr(l.state))))
    }

    private fun text(value: String, size: Float, colorAttr: Int, lines: Int = 1, font: Int? = null) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(themeColor(context, colorAttr))
        font?.let { typeface = themeFont(context, it) }
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun fill() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private fun wrap(top: Int = 0) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(top) }

    private companion object {
        /** Rond de l'état en Pastille (variante B) et son icône. */
        const val BADGE_DP = 56
        const val BADGE_ICON_DP = 30
    }
}
