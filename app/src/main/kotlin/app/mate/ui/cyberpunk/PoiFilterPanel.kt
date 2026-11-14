package app.mate.ui.cyberpunk

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.mate.R
import app.mate.net.PoiCategory
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP

/**
 * Panneau du bouton « Filtres » : une ligne par catégorie (pastille de sa couleur, libellé, nombre de points, interrupteur), puis,
 * si les icônes du jeu ne sont pas extraites, une ligne pour les demander à l'ordinateur.
 */
class PoiFilterPanel(
    private val kit: CyberpunkKit,
    private val onToggle: (PoiCategory, Boolean) -> Unit,
    private val onPrepareIcons: () -> Unit,
) : LinearLayout(kit.context) {
    private class Row(val view: View, val count: TextView, val toggle: Switch)

    private val rows = PoiCategory.entries.associateWith { row(it) }
    private var refreshing = false
    private val iconsText = kit.text(context.getString(R.string.cp_poi_icons), 16f, R.color.cp_texte, kit.medium)
    private val iconsButton = kit.button(
        context.getString(R.string.cp_poi_icons_prepare), context.getString(R.string.cp_poi_icons_prepare_description), minWidthDp = 0,
    ).apply { setOnClickListener { onPrepareIcons() } }
    private val iconsRow = kit.row().apply {
        addView(iconsText, kit.lp(0, WRAP).apply { weight = 1f })
        addView(iconsButton, kit.lp(WRAP, kit.dp(MIN_ROW_DP)))
    }

    init {
        orientation = VERTICAL
        background = kit.panel()
        setPadding(kit.dp(20), kit.dp(14), kit.dp(20), kit.dp(14))
        visibility = GONE
        addView(kit.label(context.getString(R.string.cp_poi_filters_title)), kit.lp(MATCH, WRAP).apply { bottomMargin = kit.dp(6) })
        rows.values.forEach { addView(it.view, kit.lp(MATCH, WRAP)) }
        addView(iconsRow, kit.lp(MATCH, WRAP))
    }

    private fun row(cat: PoiCategory): Row {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(kit.color(cat.color))
            }
        }
        val label = kit.text(context.getString(cat.label), 16f, R.color.cp_texte, kit.medium)
        val count = kit.text("0", 15f, R.color.cp_gris, kit.mono).apply { gravity = Gravity.END }
        val toggle = Switch(context).apply {
            isChecked = true
            contentDescription = context.getString(R.string.cp_poi_switch_description, context.getString(cat.label))
            thumbTintList = tint(R.color.cp_jaune, R.color.cp_gris)
            trackTintList = tint(R.color.cp_rouge_doux, R.color.cp_barre_vide)
            setOnCheckedChangeListener { _, on -> if (!refreshing) onToggle(cat, on) }
        }
        val line = kit.row().apply {
            minimumHeight = kit.dp(MIN_ROW_DP)
            addView(dot, kit.lp(kit.dp(DOT_DP), kit.dp(DOT_DP)).apply { marginEnd = kit.dp(12) })
            addView(label, kit.lp(0, WRAP).apply { weight = 1f })
            addView(count, kit.lp(kit.dp(COUNT_WIDTH_DP), WRAP).apply { marginEnd = kit.dp(12) })
            addView(toggle, kit.lp(WRAP, WRAP))
            setOnClickListener { toggle.toggle() }
        }
        return Row(line, count, toggle)
    }

    private fun tint(on: Int, off: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(kit.color(on), kit.color(off)))

    fun refresh(counts: Map<PoiCategory, Int>, hidden: Set<PoiCategory>, icons: PoiIconsState) {
        refreshing = true
        rows.forEach { (cat, row) ->
            row.count.text = (counts[cat] ?: 0).toString()
            row.toggle.isChecked = cat !in hidden
        }
        refreshing = false
        iconsRow.visibility = if (icons == PoiIconsState.MISSING || icons == PoiIconsState.LOADING) VISIBLE else GONE
        iconsButton.visibility = if (icons == PoiIconsState.MISSING) VISIBLE else GONE
        iconsText.text = context.getString(if (icons == PoiIconsState.LOADING) R.string.cp_poi_icons_loading else R.string.cp_poi_icons)
    }

    private companion object {
        const val MIN_ROW_DP = 52
        const val DOT_DP = 14
        const val COUNT_WIDTH_DP = 44
    }
}
