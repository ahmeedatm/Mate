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
 * dont les touches ont un [onHold] le met à vrai. Une touche large (`span` 2) prend sa case et la suivante : la grille
 * ne sait pas faire, donc la touche reçoit la largeur de deux colonnes et une case vide (« filler ») couvre la seconde
 * ([spanSlots] fixe où chacune tombe).
 */
class TileGridView(context: Context, private val scope: CoroutineScope) : GridView(context), GestureOwner {
    /** Une case : la touche, allumée (micro coupé…), « + » d'ajout, ou emplacement libre (`type == EMPTY`). */
    class Cell(val key: KeyRef, val lit: Boolean = false, val add: Boolean = false, val type: String = "")

    var cells: List<Cell> = emptyList()
        set(value) {
            field = value
            reslot()
        }

    /** Colonnes de la grille, comptées comme `AUTO_FIT` : la grille ne les donne qu'une fois mesurée. */
    private var columns = 1
    private var usableWidth = 0
    private var slots: List<SpanSlot> = emptyList()

    private fun reslot() {
        slots = spanSlots(cells.map { it.key.span }, columns)
        adapter.notifyDataSetChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val usable = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val next = autoFitColumns(usable, requestedColumnWidth, horizontalSpacing)
        // Même nombre de colonnes mais largeur changée : la colonne s'étire, la touche large doit reprendre sa largeur.
        if (next != columns || usable != usableWidth) {
            columns = next
            usableWidth = usable
            reslot()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Position de la vue qui porte la case [position] : la touche large, même touchée par sa seconde case. */
    private fun ownerOf(position: Int) = if (slots.getOrNull(position)?.filler == true) position - 1 else position

    private fun cellAt(position: Int): Cell? = slots.getOrNull(ownerOf(position))?.cell?.let { cells.getOrNull(it) }

    private fun viewAt(position: Int): View? = getChildAt(ownerOf(position) - firstVisiblePosition)

    var onTap: (Cell, View) -> Unit = { _, _ -> }

    /** Appui long sur une touche ; null : rien à faire, l'appui long n'est ni consommé ni signalé. */
    var onHold: ((Cell, View) -> Unit)? = null

    /**
     * Touche à maintenir (parler en maintenant) : [isPush] la reconnaît, [onPush] reçoit l'appui (vrai) puis le
     * relâchement (faux). Sa touche ne répond ni au clic ni à l'appui long, le doigt tient la touche.
     */
    var onPush: ((Cell, Boolean) -> Unit)? = null
    var isPush: (Cell) -> Boolean = { false }
    private var pushed: Cell? = null

    override var ownsLongPress = false
    override val ownsSwipe = false

    // Cases enfoncées, seules rendues au relâchement.
    private val pressed = mutableListOf<View>()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = slots.size
        override fun getItem(position: Int) = cellAt(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int): Int {
            val slot = slots[position]
            return when {
                slot.cell == PAD || slot.filler -> TYPE_BLANK
                slot.cell < cells.size && cells[slot.cell].key.span > 1 && columns > 1 -> TYPE_WIDE
                else -> TYPE_TILE
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val type = getItemViewType(position)
            if (type == TYPE_BLANK) return convertView ?: View(context).apply { layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight(context, metrics(context))) }
            val view = convertView ?: newCell(context, metrics(context), wide = type == TYPE_WIDE)
            // Deux colonnes et l'espace entre elles : la largeur change avec les colonnes, la vue recyclée la reprend.
            if (type == TYPE_WIDE) {
                val width = 2 * columnWidth + horizontalSpacing
                if (view.layoutParams.width != width) {
                    view.layoutParams.width = width
                    view.requestLayout()
                }
            }
            return view.also { bind(it, cellAt(position)!!) }
        }
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
            val cell = cellAt(position) ?: return@setOnItemClickListener
            val target = viewAt(position) ?: view
            target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onTap(cell, target)
        }
        setOnItemLongClickListener { _, view, position, _ ->
            val hold = onHold ?: return@setOnItemLongClickListener false
            val cell = cellAt(position)?.takeUnless { it.add } ?: return@setOnItemLongClickListener false
            val target = viewAt(position) ?: view
            target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hold(cell, target)
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
        pushed?.let {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) releasePush()
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val position = pointToPosition(ev.x.toInt(), ev.y.toInt())
            val cell = cellAt(position)?.takeUnless { it.add }
            if (cell != null && isPush(cell)) {
                pushed = cell
                viewAt(position)?.let {
                    pressed += it
                    Look.pressTo(it, true)
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onPush?.invoke(cell, true)
                return true
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val position = pointToPosition(ev.x.toInt(), ev.y.toInt())
                if (cellAt(position) != null) {
                    viewAt(position)?.let {
                        pressed += it
                        Look.pressTo(it, true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun releasePush() {
        val cell = pushed ?: return
        pushed = null
        releaseAll()
        onPush?.invoke(cell, false)
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
        releasePush()
        stopListening?.invoke()
        stopListening = null
        super.onDetachedFromWindow()
    }

    /** Dessine [cell] dans une vue de [newCell] (neuve ou recyclée). */
    fun bind(view: View, cell: Cell) = bind(view, cell, scope)

    companion object {
        const val EMPTY = "empty"

        // Types de vue de la grille : touche, touche large, case vide (pad ou seconde case d'une touche large).
        private const val TYPE_TILE = 0
        private const val TYPE_WIDE = 1
        private const val TYPE_BLANK = 2

        /** Cotes du thème : grandes en Contraste élevé (`c_big_keys`). */
        fun metrics(context: Context) = TileMetrics.of(themeBool(context, R.attr.c_big_keys))

        /** Hauteur d'une case : la plaque, [TileMetrics.LABEL_GAP_DP] et deux lignes de libellé, au moins `cellDp`. Les cases vides la prennent aussi. */
        private fun cellHeight(context: Context, metrics: TileMetrics, lineHeight: Int = TextView(context).apply { textSize = metrics.labelSp }.lineHeight) =
            maxOf(context.dp(metrics.cellDp), context.dp(metrics.plateDp + TileMetrics.LABEL_GAP_DP) + 2 * lineHeight)

        /**
         * Vue d'une case, au style Console de `KeysWidget.tile` : sans cadre, icône de 52 dp à coins arrondis, libellé
         * de 13 sp, appui sur ressort. `item_tile` fait 136 dp : ici 100 dp, assez pour deux lignes de libellé. [metrics]
         * donne les grandes cotes aux grilles du thème Contraste élevé ; les rangées du widget gardent les normales.
         * Plaque en haut de la case, libellé dessous : toutes les plaques d'une rangée sont alignées, quel que soit le nombre
         * de lignes du libellé. La case grandit si la taille du texte (Réglages > Accessibilité) lui prend plus de place.
         * [wide] (touche large) : la plaque prend toute la largeur de la case, en pastille.
         */
        fun newCell(context: Context, metrics: TileMetrics = TileMetrics.NORMAL, wide: Boolean = false): View {
            val view = LayoutInflater.from(context).inflate(R.layout.item_tile, null)
            view.background = null
            view.stateListAnimator = null
            view.setPadding(0, 0, 0, 0)
            val plate = view.findViewById<ImageView>(R.id.tile_icon).parent as View
            plate.layoutParams = LinearLayout.LayoutParams(if (wide) ViewGroup.LayoutParams.MATCH_PARENT else context.dp(metrics.plateDp), context.dp(metrics.plateDp))
            val label = view.findViewById<TextView>(R.id.tile_label).apply {
                textSize = metrics.labelSp
                (layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(TileMetrics.LABEL_GAP_DP)
            }
            view.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight(context, metrics, label.lineHeight))
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
                GradientDrawable().apply { setColor(it); cornerRadius = TileMetrics.radiusFor(plate.layoutParams.height).toFloat() }
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
                val pad = TileMetrics.symbolPadFor(plate.layoutParams.height)
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
