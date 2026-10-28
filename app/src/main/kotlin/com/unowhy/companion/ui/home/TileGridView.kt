package com.unowhy.companion.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.ui.IconCache
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Grille de touches d'un widget (Touches, Apps, Discord, Réunion, sites de Média) : colonnes de 96 dp qui se
 * répartissent la largeur, défilement vertical s'il y a plus de touches que de place. Elle garde l'appui long (appui
 * long sur une touche = [onHold], pas le mode Modifier). Le balayage reste à la grille des profils.
 */
class TileGridView(context: Context, private val scope: CoroutineScope) : GridView(context), GestureOwner {
    /** Une case : la touche, allumée (micro coupé…), « + » d'ajout, ou emplacement libre (`type == EMPTY`). */
    class Cell(val key: KeyRef, val lit: Boolean = false, val add: Boolean = false, val type: String = "")

    var cells: List<Cell> = emptyList()
        set(value) {
            field = value
            adapter.notifyDataSetChanged()
        }

    var onTap: (Cell, View) -> Unit = { _, _ -> }
    var onHold: (Cell, View) -> Unit = { _, _ -> }

    override val ownsLongPress = true
    override val ownsSwipe = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = cells.size
        override fun getItem(position: Int) = cells[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView ?: newCell(context)).also { bind(it, cells[position]) }
    }

    init {
        numColumns = AUTO_FIT
        setColumnWidth(dp(COLUMN_DP))
        stretchMode = STRETCH_COLUMN_WIDTH
        horizontalSpacing = dp(8)
        verticalSpacing = dp(8)
        selector = ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setAdapter(adapter)
        setOnItemClickListener { _, view, position, _ ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            cells.getOrNull(position)?.let { onTap(it, view) }
        }
        setOnItemLongClickListener { _, view, position, _ ->
            val cell = cells.getOrNull(position) ?: return@setOnItemLongClickListener false
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onHold(cell, view)
            true
        }
    }

    /** Dessine [cell] dans une vue de [newCell] (neuve ou recyclée). */
    fun bind(view: View, cell: Cell) = bind(view, cell, scope)

    companion object {
        const val EMPTY = "empty"
        private const val COLUMN_DP = 96
        private const val CELL_DP = 100
        private const val PLATE_DP = 52
        private const val RADIUS_DP = 22

        /**
         * Vue d'une case, au style Cadran de `KeysWidget.tile` : sans cadre, icône de 52 dp à coins arrondis, libellé
         * de 13 sp, appui sur ressort. `item_tile` fait 136 dp : ici 100 dp, assez pour deux lignes de libellé.
         */
        fun newCell(context: Context): View {
            val view = LayoutInflater.from(context).inflate(R.layout.item_tile, null)
            view.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(CELL_DP))
            view.background = null
            view.stateListAnimator = null
            view.setPadding(0, 0, 0, 0)
            val plate = view.findViewById<ImageView>(R.id.tile_icon).parent as View
            plate.layoutParams = LinearLayout.LayoutParams(context.dp(PLATE_DP), context.dp(PLATE_DP))
            view.findViewById<TextView>(R.id.tile_label).apply {
                textSize = 13f
                (layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(6)
            }
            view.findViewById<ImageView>(R.id.tile_icon).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, context.dp(RADIUS_DP).toFloat())
                }
            }
            Look.pressSpring(view)
            return view
        }

        /**
         * Remplit une vue de [newCell]. Tout est remis à zéro à chaque appel : la vue a pu servir à une autre case
         * (pointillés, couleur, icône). L'icône chargée plus tard n'est posée que si la vue montre encore la même touche.
         */
        fun bind(view: View, cell: Cell, scope: CoroutineScope) {
            val context = view.context
            val key = cell.key
            val plate = view.findViewById<ImageView>(R.id.tile_icon).parent as FrameLayout
            val icon = view.findViewById<ImageView>(R.id.tile_icon)
            val initial = view.findViewById<TextView>(R.id.tile_initial)
            val label = view.findViewById<TextView>(R.id.tile_label)
            view.tag = key.id
            icon.setImageBitmap(null)
            icon.setPadding(0, 0, 0, 0)
            initial.visibility = View.VISIBLE

            val empty = cell.add || cell.type == EMPTY
            if (empty) {
                plate.setBackgroundResource(R.drawable.tile_empty_bg)
                label.text = if (cell.type == EMPTY) context.getString(R.string.key_empty_label) else key.label
                label.setTextColor(themeColor(context, R.attr.c_text))
                initial.text = "+"
                Look.plainInitial(initial, R.attr.c_accent)
                return
            }

            label.text = key.label
            label.setTextColor(themeColor(context, R.attr.c_text))
            // Plaque pleine derrière la touche : rouge de Muet quand elle est allumée, sinon la couleur de la touche.
            val custom = key.color?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            val tint = if (cell.lit) themeColor(context, R.attr.c_ko) else custom
            val plain = tint == null
            plate.background = tint?.let {
                GradientDrawable().apply { setColor(it); cornerRadius = context.dp(RADIUS_DP).toFloat() }
            }
            val inset = if (plain) 0 else context.dp(8)
            icon.setPadding(inset, inset, inset, inset)
            if (plain) {
                Look.tintInitial(initial, key.label)
            } else {
                initial.background = null
                initial.setTextColor(if (cell.lit) themeColor(context, R.attr.c_on_accent) else Look.readableOn(context, tint!!))
            }

            if (key.glyph != null) {
                initial.text = key.glyph
                return
            }
            initial.text = key.label.take(1).uppercase()
            IconCache.peek(key.id)?.let {
                icon.setImageBitmap(it)
                initial.visibility = View.GONE
                return
            }
            scope.launch {
                val bitmap = IconCache.load(BackendClient(context), key.id)
                if (bitmap != null && view.tag == key.id) {
                    icon.setImageBitmap(bitmap)
                    initial.visibility = View.GONE
                }
            }
        }
    }
}
