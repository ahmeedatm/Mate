package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.gitlab.markOf
import app.mate.net.Pipeline
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.pipelineColorAttr
import app.mate.ui.pipelineMarkIcon
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/** Une ligne d'une petite forme : titre, détail (dépôt, colonne, durée), état ; `open` : toucher la ligne. */
data class GlanceLine(val title: String, val detail: String, val state: Pipeline, val open: (() -> Unit)? = null)

/** Ce qu'une petite forme montre : le compteur et ce qu'il compte, puis les lignes du plus urgent au moins urgent. */
data class GlanceContent(val count: Int, val countLabel: String, val lines: List<GlanceLine>)

/**
 * Petites formes des widgets Dev : un grand nombre et ce qu'il compte (« 2 à relire »), puis les éléments, chacun avec
 * l'icône de son état. Pastille (1 × 1) : le nombre seul. Ligne (2 × 1 à 4 × 1) : le nombre à gauche, les éléments à
 * droite. Colonne (1 × 2 à 1 × 4) : le nombre en haut, les éléments dessous. Seules les lignes entières sont montrées.
 * Pas de réseau ici : le widget, ou l'aperçu du catalogue, donne le contenu.
 */
class DevGlanceView(context: Context, private val title: Int) : LinearLayout(context) {

    /** `w` × `h` : la forme en cases ; `note` : phrase à la place du contenu (vide, erreur, à connecter). */
    fun show(content: GlanceContent?, w: Int, h: Int, note: String? = null) {
        removeAllViews()
        orientation = if (h == 1 && w > 1 && content != null && note == null) HORIZONTAL else VERTICAL
        if (content == null || note != null) {
            addView(titleView())
            addView(text(note.orEmpty(), 14f, R.attr.c_text_dim, lines = NOTE_LINES), fill())
            return
        }
        when {
            w == 1 && h == 1 -> {
                addView(titleView())
                addView(View(context), fill())
                addView(count(content, PILL_SIZE))
                addView(text(content.countLabel, 13f, R.attr.c_text_dim))
            }
            h == 1 -> {
                addView(
                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(titleView())
                        addView(LinearLayout(context).apply {
                            gravity = Gravity.BOTTOM
                            addView(count(content, ROW_SIZE))
                            addView(text(content.countLabel, 13f, R.attr.c_text_dim), LayoutParams(-2, -2).apply { marginStart = dp(6); bottomMargin = dp(4) })
                        })
                    },
                    LayoutParams(dp(ROW_LEFT_DP), ViewGroup.LayoutParams.MATCH_PARENT),
                )
                addView(lines(content.lines, Gravity.CENTER_VERTICAL), LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
            }
            else -> {
                addView(titleView())
                addView(count(content, COLUMN_SIZE))
                addView(text(content.countLabel, 13f, R.attr.c_text_dim))
                addView(lines(content.lines, Gravity.TOP), fill().apply { topMargin = dp(8) })
            }
        }
    }

    private fun titleView() = TitleView(context).apply {
        setText(title)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun count(c: GlanceContent, size: Float) = text(c.count.toString(), size, R.attr.c_text, font = R.attr.c_font_light)

    private fun lines(list: List<GlanceLine>, gravity: Int) = WholeLines(context, dp(LINE_DP)).apply {
        this.gravity = gravity
        // Pas plus de lignes que n'en tient la plus haute Colonne (1 × 4) : une longue liste ne crée pas de vues pour rien.
        list.take(MAX_LINES).forEach { addView(line(it), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(LINE_DP))) }
    }

    /** Icône de l'état puis titre sur une ligne : l'icône dit l'état, la couleur seule ne suffit pas. */
    // shortcut: ligne touchable de 26 dp (moins que les 48 dp conseillés) et sans retour d'appui, pour que la Ligne en tienne deux ; à revoir après l'essai sur la tablette.
    private fun line(l: GlanceLine) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply {
                setImageResource(pipelineMarkIcon(markOf(l.state)))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, pipelineColorAttr(l.state))))
            },
            LayoutParams(dp(16), dp(16)),
        )
        addView(text(l.title, 14f, R.attr.c_text), LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        contentDescription = "${l.title}, ${l.detail}"
        l.open?.let { open -> setOnClickListener { open() } }
    }

    private fun text(value: String, size: Float, colorAttr: Int, lines: Int = 1, font: Int? = null) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(themeColor(context, colorAttr))
        font?.let { typeface = themeFont(context, it) }
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(value: Int) = context.dp(value)

    private fun fill() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private companion object {
        const val PILL_SIZE = 44f
        const val ROW_SIZE = 30f
        const val COLUMN_SIZE = 34f
        const val NOTE_LINES = 4
        const val LINE_DP = 26
        const val MAX_LINES = 20

        /** Largeur du nombre et de son libellé dans une Ligne. */
        const val ROW_LEFT_DP = 110
    }
}

/**
 * Colonne de lignes de même hauteur ([lineHeight]) qui ne garde que celles qui tiennent en entier : la hauteur d'une
 * rangée change avec le profil, et une ligne coupée ne se lit pas.
 */
private class WholeLines(context: Context, private val lineHeight: Int) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val fits = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) childCount else room / lineHeight
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // Décidé avant de mesurer : une fois stable, plus de changement, donc pas de boucle de mise en page.
            val wanted = if (i < fits) VISIBLE else GONE
            if (child.visibility != wanted) child.visibility = wanted
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
