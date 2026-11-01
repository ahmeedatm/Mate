package app.mate.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
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
import app.mate.R
import app.mate.net.BackendClient
import app.mate.ui.IconCache
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Grille de touches d'un widget (Touches, Apps, Discord, Réunion, sites de Média) : colonnes de 96 dp qui se
 * répartissent la largeur, défilement vertical s'il y a plus de touches que de place. Le balayage reste à la grille
 * des profils. L'appui long reste à la grille des profils (mode Modifier) tant que [ownsLongPress] est faux : un widget
 * dont les touches ont un [onHold] le met à vrai.
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

    /** Appui long sur une touche ; null : rien à faire, l'appui long n'est ni consommé ni signalé. */
    var onHold: ((Cell, View) -> Unit)? = null

    override var ownsLongPress = false
    override val ownsSwipe = false

    // Cases enfoncées, seules rendues au relâchement.
    private val pressed = mutableListOf<View>()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = cells.size
        override fun getItem(position: Int) = cells[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView ?: newCell(context, metrics(context))).also { bind(it, cells[position]) }
    }

    init {
        val metrics = metrics(context)
        numColumns = AUTO_FIT
        setColumnWidth(dp(metrics.columnDp))
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
            val hold = onHold ?: return@setOnItemLongClickListener false
            val cell = cells.getOrNull(position)?.takeUnless { it.add } ?: return@setOnItemLongClickListener false
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hold(cell, view)
            true
        }
        // Le défilement reprend la main sur le doigt : plus d'appui.
        setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) releaseAll()
            }

            override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = Unit
        })
    }

    /**
     * Ressort d'appui posé ici et non sur les cases : elles ne sont pas cliquables (le clic passe par la grille), une
     * case qui garde un `OnTouchListener` ne reçoit que le DOWN et resterait enfoncée.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val position = pointToPosition(ev.x.toInt(), ev.y.toInt())
                if (position != INVALID_POSITION) {
                    getChildAt(position - firstVisiblePosition)?.let {
                        pressed += it
                        Look.pressTo(it, true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun releaseAll() {
        pressed.forEach { Look.pressTo(it, false) }
        pressed.clear()
    }

    private var stopListening: (() -> Unit)? = null

    /** Une touche s'allume ou s'éteint côté ordinateur : les cases visibles se redessinent. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopListening = TileStates.listen { post { if (isAttachedToWindow) adapter.notifyDataSetChanged() } }
    }

    override fun onDetachedFromWindow() {
        stopListening?.invoke()
        stopListening = null
        super.onDetachedFromWindow()
    }

    /** Dessine [cell] dans une vue de [newCell] (neuve ou recyclée). */
    fun bind(view: View, cell: Cell) = bind(view, cell, scope)

    companion object {
        const val EMPTY = "empty"

        /** Cotes du thème : grandes en Contraste élevé (`c_big_keys`). */
        fun metrics(context: Context) = TileMetrics.of(themeBool(context, R.attr.c_big_keys))

        /**
         * Vue d'une case, au style Console de `KeysWidget.tile` : sans cadre, icône de 52 dp à coins arrondis, libellé
         * de 13 sp, appui sur ressort. `item_tile` fait 136 dp : ici 100 dp, assez pour deux lignes de libellé. [metrics]
         * donne les grandes cotes aux grilles du thème Contraste élevé ; les rangées du widget gardent les normales.
         * Plaque en haut de la case, libellé dessous : toutes les plaques d'une rangée sont alignées, quel que soit le nombre
         * de lignes du libellé. La case grandit si la taille du texte (Réglages > Accessibilité) lui prend plus de place.
         */
        fun newCell(context: Context, metrics: TileMetrics = TileMetrics.NORMAL): View {
            val view = LayoutInflater.from(context).inflate(R.layout.item_tile, null)
            view.background = null
            view.stateListAnimator = null
            view.setPadding(0, 0, 0, 0)
            val plate = view.findViewById<ImageView>(R.id.tile_icon).parent as View
            plate.layoutParams = LinearLayout.LayoutParams(context.dp(metrics.plateDp), context.dp(metrics.plateDp))
            val label = view.findViewById<TextView>(R.id.tile_label).apply {
                textSize = metrics.labelSp
                (layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(TileMetrics.LABEL_GAP_DP)
            }
            val twoLines = context.dp(metrics.plateDp + TileMetrics.LABEL_GAP_DP) + 2 * label.lineHeight
            view.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(context.dp(metrics.cellDp), twoLines))
            view.findViewById<TextView>(R.id.tile_initial).textSize = metrics.initialSp
            val radius = context.dp(TileMetrics.radiusFor(metrics.plateDp)).toFloat()
            view.findViewById<ImageView>(R.id.tile_icon).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
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
            Look.pressReset(view)
            icon.setImageBitmap(null)
            icon.setPadding(0, 0, 0, 0)
            icon.imageTintList = null
            icon.background = null
            initial.visibility = View.VISIBLE

            val empty = cell.add || cell.type == EMPTY
            // Case sans touche : pas de tag, sinon une icône en retard d'une autre case se poserait dessus.
            view.tag = if (empty) null else key.id
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
            val lit = cell.lit || TileStates.isLit(key.id)
            val custom = key.color?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            val tint = if (lit) themeColor(context, R.attr.c_ko) else custom
            val plain = tint == null
            plate.background = tint?.let {
                GradientDrawable().apply { setColor(it); cornerRadius = TileMetrics.radiusFor(plate.layoutParams.width).toFloat() }
            }
            val inset = if (plain) 0 else context.dp(8)
            icon.setPadding(inset, inset, inset, inset)
            if (plain) {
                Look.tintInitial(initial, key.label)
            } else {
                initial.background = null
                initial.setTextColor(if (lit) themeColor(context, R.attr.c_on_accent) else Look.readableOn(context, tint!!))
            }

            // Symbole de la tablette (Deck V2), ou icône d'un emoji connu : icône monochrome, prioritaire sur l'emoji.
            TileIcons.of(key.symbol, key.glyph)?.let { symbol ->
                icon.setImageResource(symbol)
                val ink = when {
                    lit -> themeColor(context, R.attr.c_on_accent)
                    plain -> themeColor(context, R.attr.c_text)
                    else -> Look.readableOn(context, tint!!)
                }
                icon.imageTintList = android.content.res.ColorStateList.valueOf(ink)
                if (plain) icon.setBackgroundResource(themeRes(context, R.attr.c_key_plate))
                // Même marge sur plaque couleur que sans : l'icône garde plus de la moitié de la plaque.
                val pad = TileMetrics.symbolPadFor(plate.layoutParams.width)
                icon.setPadding(pad, pad, pad, pad)
                initial.visibility = View.GONE
                return
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
