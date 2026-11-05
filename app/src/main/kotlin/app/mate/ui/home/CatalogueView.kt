package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeColorList
import app.mate.ui.themeFont

/**
 * Catalogue d'ajout de widgets (#179), plein écran par-dessus l'Accueil : à gauche, la liste des widgets proposés rangés
 * par rayon ([catalogueShelves]), suggestions en tête ; à droite, la fiche du widget touché ([CatalogueSheet]), mise à
 * jour sans écran de plus. Fermé par « Fermer » ou Retour.
 */
@SuppressLint("ViewConstructor")
class CatalogueView(context: Context, host: HomeHost) : FrameLayout(context) {
    /** Où l'ajout a été demandé : `col` < 0 = bouton « Ajouter », pas de case touchée. Cases en px, pour l'échelle des aperçus. */
    class Request(val layout: HomeLayout, val page: Int, val col: Int, val row: Int, val cellW: Int, val cellH: Int)

    private val sheet = CatalogueSheet(context, host)
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(24), dp(16), dp(24))
    }
    private var rows = emptyMap<WidgetType, View>()
    private var request: Request? = null
    private var onAdd: ((WidgetType, Fit) -> Unit)? = null

    val isShowing get() = visibility == VISIBLE

    init {
        visibility = GONE
        setBackgroundColor(themeColor(context, R.attr.c_bg))
        // Rien ne traverse vers l'Accueil.
        isClickable = true
        addView(LinearLayout(context).apply {
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                setBackgroundColor(themeColor(context, R.attr.c_surface))
                addView(list)
            }, LinearLayout.LayoutParams(dp(LIST_DP), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(32), dp(16), dp(32), dp(32))
                addView(closeButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { gravity = Gravity.END })
                addView(sheet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Ouvre le catalogue des widgets que l'ordinateur actif offre (`offered`) ; `onAdd` reçoit le widget et sa place. */
    fun show(request: Request, offered: (WidgetType) -> Boolean, onAdd: (WidgetType, Fit) -> Unit) {
        this.request = request
        this.onAdd = onAdd
        val shelves = catalogueShelves(offered)
        buildList(shelves)
        shelves.firstOrNull()?.second?.firstOrNull()?.let(::select)
        visibility = VISIBLE
        alpha = 0f
        Look.navSpring(this, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        announceForAccessibility(context.getString(R.string.home_gallery_title))
    }

    fun hide() {
        if (!isShowing) return
        visibility = GONE
        sheet.clear()
        list.removeAllViews()
        rows = emptyMap()
        request = null
        onAdd = null
    }

    private fun select(type: WidgetType) {
        val request = request ?: return
        rows.forEach { (t, row) -> row.isSelected = t == type }
        val choices = request.layout.shapeChoices(type, request.page, request.col, request.row)
        sheet.show(type, choices, openingShape(type, choices), request.cellW, request.cellH) { chosen, fit ->
            val add = onAdd
            hide()
            add?.invoke(chosen, fit)
        }
    }

    private fun buildList(shelves: List<Pair<CatalogueShelf, List<WidgetType>>>) {
        list.removeAllViews()
        list.addView(TextView(context).apply {
            setText(R.string.home_gallery_title)
            textSize = 22f
            typeface = themeFont(context, R.attr.c_font_title)
            setTextColor(themeColor(context, R.attr.c_text))
            setPadding(dp(8), 0, dp(8), 0)
        })
        val made = mutableMapOf<WidgetType, View>()
        for ((shelf, types) in shelves) {
            list.addView(TitleView(context).apply {
                setText(shelf.title)
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20); bottomMargin = dp(4) })
            for (type in types) {
                val row = row(type)
                made[type] = row
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)).apply { topMargin = dp(2) })
            }
        }
        rows = made
    }

    /** Une ligne : icône, nom, et la barre d'accent quand elle est choisie (le choix ne repose pas sur la seule teinte). */
    private fun row(type: WidgetType) = object : LinearLayout(context) {
        private val bar = View(context).apply {
            setBackgroundResource(R.drawable.dot)
            backgroundTintList = themeColorList(context, R.attr.c_accent)
            visibility = INVISIBLE
        }

        init {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.catalogue_row_bg)
            setPadding(dp(12), 0, dp(10), 0)
            addView(ImageView(context).apply {
                setImageResource(type.icon)
                imageTintList = themeColorList(context, R.attr.c_text_dim)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(24), dp(24)))
            addView(TextView(context).apply {
                setText(type.title)
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(themeColor(context, R.attr.c_text))
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            addView(bar, LayoutParams(dp(4), dp(20)))
            setOnClickListener { select(type) }
        }

        override fun setSelected(selected: Boolean) {
            super.setSelected(selected)
            bar.visibility = if (selected) VISIBLE else INVISIBLE
        }
    }

    private fun closeButton() = Button(context, null, 0, R.style.Chip).apply {
        setText(R.string.close)
        setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()?.apply {
            setTint(themeColor(context, R.attr.c_text))
        }, null, null, null)
        compoundDrawablePadding = dp(8)
        setPadding(dp(16), 0, dp(20), 0)
        setOnClickListener { hide() }
    }

    private companion object {
        const val LIST_DP = 280
        const val ROW_DP = 52
    }
}
