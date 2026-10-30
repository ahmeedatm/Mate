package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.customview.widget.ExploreByTouchHelper
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Grille de l'Accueil : place chaque widget (vue dont le `tag` est son [Placed]) sur ses cases. Seule la page
 * affichée est visible. Appui long sur un widget : il s'agrandit (le mode Modifier vient du crayon de la barre) ; balayage vertical : profil suivant (vers le haut) ou
 * précédent (vers le bas), sauf si ce qui est sous le doigt défile encore dans ce sens (liste d'un widget).
 *
 * Mode Modifier : les widgets ne reçoivent plus les touchers. Chacun a un ✕ (retirer), une poignée dans le coin bas-droit
 * (la tirer : il prend la taille permise la plus proche de la zone tirée) et, s'il en a, ⚙ (réglages) ; on le glisse vers
 * d'autres cases ; une case vide (+) ajoute un widget à cet endroit.
 *
 * Étroit (panneau document ouvert) : 2 colonnes, les widgets se suivent dans l'ordre de lecture (la page défile).
 */
class WidgetGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    interface Listener {
        fun onExpand(placed: Placed)
        fun onPageSwipe(delta: Int)

        /** Balayage en cours, doigt à `dy` px du départ : l'écran le suit (SwipeFollow). */
        fun onPageDrag(dy: Float) {}

        /** Balayage lâché trop court ou annulé : l'écran revient en place. */
        fun onPageDragEnd() {}
        fun onAddAt(col: Int, row: Int)
        fun onRemove(placed: Placed)

        /** Taille et case permises de `placed` les plus proches de la zone tirée (bords en cases), null si aucune. */
        fun pickSize(placed: Placed, left: Float, top: Float, right: Float, bottom: Float): Fit?
        fun onResize(placed: Placed, fit: Fit)

        /** Action d'accessibilité de la poignée : taille suivante qui tient. */
        fun onCycleSize(placed: Placed)
        fun onSettings(placed: Placed)
        fun hasSettings(placed: Placed): Boolean
        fun canMove(placed: Placed, col: Int, row: Int): Boolean
        fun onMove(placed: Placed, col: Int, row: Int)
    }

    var listener: Listener? = null
    /** Widget qui ne répond plus au toucher (ordinateur injoignable) : il reste affiché, l'appui long et le balayage restent à la grille. */
    var blocked: (Placed) -> Boolean = { false }
    var editing = false
        set(value) {
            field = value
            endDrag()
            // Le fournisseur d'accessibilité remplace les vrais widgets pour TalkBack : seulement en mode Modifier.
            ViewCompat.setAccessibilityDelegate(this, if (value) a11y else null)
            a11y.invalidateRoot()
            invalidate()
        }
    var page = 0
        set(value) { field = value; showPage() }
    var narrow = false
        set(value) { if (field != value) { field = value; requestLayout() } }
    /** Hauteur visible (le ScrollView autour mesure sans limite) : la grille normale la remplit exactement. */
    var viewportHeight = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val density = resources.displayMetrics.density
    /** Gouttière entre les cases. */
    var gap = dp(20)
        set(value) { if (field != value) { field = value; requestLayout() } }
    /** Poids des [ROWS] lignes (voir [RowProfile]) ; null : lignes égales, comme avant le profil à lignes inégales. */
    var rowWeights: IntArray? = null
        set(value) { field = value; requestLayout() }
    private val narrowRowHeight = dp(176)
    private var cellW = 0
    /** Hauteur de chaque ligne en mode normal (en mode étroit, toutes valent [narrowRowHeight]). */
    private var rowH = IntArray(ROWS)
    /** Cases occupées en mode étroit (le flux remplace la position enregistrée). */
    private val flow = HashMap<Placed, IntArray>()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = ViewConfiguration.getLongPressTimeout().toLong()

    // --- Dessin du mode Modifier
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
        color = themeColor(context, R.attr.c_stroke)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val removeIcon = ContextCompat.getDrawable(context, R.drawable.ic_close)!!.mutate()
    private val settingsIcon = ContextCompat.getDrawable(context, R.drawable.ic_settings)!!.mutate()
    private val resizeIcon = ContextCompat.getDrawable(context, R.drawable.ic_resize)!!.mutate()
    private val accent = themeColor(context, R.attr.c_accent)
    private val raised = themeColor(context, R.attr.c_surface_raised)
    private val ko = themeColor(context, R.attr.c_ko)
    private val textColor = themeColor(context, R.attr.c_text)
    private val faint = themeColor(context, R.attr.c_text_faint)
    private val emptyHint = context.getString(R.string.home_empty_hint)
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18 * resources.displayMetrics.scaledDensity
        color = themeColor(context, R.attr.c_text_dim)
        typeface = themeFont(context, R.attr.c_font_regular)
    }

    init {
        setWillNotDraw(false)
        removeIcon.setTint(textColor)
        settingsIcon.setTint(textColor)
        resizeIcon.setTint(textColor)
    }

    /** TalkBack : la poignée de chaque widget redimensionnable (description, un appui = taille suivante). */
    private val a11y = object : ExploreByTouchHelper(this) {
        private fun resizable() = (0 until childCount).filter { i ->
            val child = getChildAt(i)
            editing && child.isVisible && (placedOf(child)?.type?.sizes?.size ?: 0) > 1
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int =
            resizable().firstOrNull { resizeRect(getChildAt(it)).touchable().contains(x, y) } ?: HOST_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids += resizable()
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val child = getChildAt(id)
            val placed = child?.let { placedOf(it) }
            if (child == null || placed == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = context.getString(R.string.widget_resize_cd, context.getString(placed.type.title), context.getString(placed.size.label))
            val r = resizeRect(child).touchable()
            node.setBoundsInParent(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val placed = getChildAt(id)?.let { placedOf(it) } ?: return false
            listener?.onCycleSize(placed)
            invalidateVirtualView(id)
            return true
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = (editing && a11y.dispatchHoverEvent(event)) || super.dispatchHoverEvent(event)

    private fun placedOf(child: View) = child.tag as? Placed

    private fun pageEmpty() = (0 until childCount).none { placedOf(getChildAt(it))?.page == page }

    // --- Mesure et placement

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = if (narrow) 2 else COLS
        cellW = (width - paddingLeft - paddingRight - gap * (cols - 1)) / cols
        val height: Int
        if (narrow) {
            computeFlow()
            val rows = flow.values.maxOfOrNull { it[1] + it[3] } ?: 1
            height = paddingTop + paddingBottom + spanHeight(0, rows)
        } else {
            height = if (viewportHeight > 0) viewportHeight else MeasureSpec.getSize(heightMeasureSpec)
            rowH = computeRows(height - paddingTop - paddingBottom)
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility == View.GONE) continue
            val (_, row, w, h) = cells(placed)
            child.measure(
                MeasureSpec.makeMeasureSpec(w * cellW + (w - 1) * gap, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(spanHeight(row, h), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility == View.GONE) continue
            val (c, row) = cells(placed)
            val left = paddingLeft + c * (cellW + gap)
            val top = paddingTop + rowTop(row)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    /** Seuls les widgets de la page affichée sont visibles (à rappeler après un ajout). */
    fun showPage() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            child.visibility = if (placed.page == page) View.VISIBLE else View.GONE
        }
        // Écran sans widget : le message dessiné au centre est aussi lu par TalkBack.
        contentDescription = if (pageEmpty()) emptyHint else null
        requestLayout()
        invalidate()
    }

    private fun computeRows(room: Int): IntArray {
        val weights = rowWeights ?: return IntArray(ROWS) { (room - gap * (ROWS - 1)) / ROWS }
        return RowProfile.rowHeights(room, gap, weights)
    }

    private fun rowHeight(r: Int) = if (narrow) narrowRowHeight else rowH[r]

    /** Ordonnée du haut de la ligne `r`, dans la zone sous le padding. */
    private fun rowTop(r: Int): Int {
        var top = 0
        for (i in 0 until r) top += rowHeight(i) + gap
        return top
    }

    /** Hauteur de `h` lignes à partir de `r`, gouttières comprises. */
    private fun spanHeight(r: Int, h: Int): Int = rowTop(r + h) - rowTop(r) - gap

    /** Ligne sous l'ordonnée `y` (la gouttière compte pour la ligne du dessus), [ROWS] si en dessous de la dernière. */
    private fun rowAt(y: Float): Int {
        for (r in 0 until ROWS) if (y < rowTop(r) + rowHeight(r) + gap) return r
        return ROWS
    }

    /** Colonne, ligne, largeur, hauteur affichées (enregistrées, ou le flux en mode étroit). */
    private fun cells(placed: Placed): IntArray =
        if (narrow) flow[placed] ?: intArrayOf(0, 0, 1, 1) else intArrayOf(placed.col, placed.row, placed.size.w, placed.size.h)

    /** Mode étroit : widgets de la page dans l'ordre de lecture, au plus 2 cases de large, à la première place libre. */
    private fun computeFlow() {
        flow.clear()
        val taken = HashSet<Long>()
        val items = (0 until childCount).mapNotNull { placedOf(getChildAt(it)) }.filter { it.page == page }.sortedWith(compareBy({ it.row }, { it.col }))
        for (p in items) {
            val w = p.size.w.coerceAtMost(2)
            val h = p.size.h
            var r = 0
            while (true) {
                val c = (0..2 - w).firstOrNull { c -> (0 until w).all { dx -> (0 until h).all { dy -> (r + dy) * 8L + c + dx !in taken } } }
                if (c != null) {
                    for (dx in 0 until w) for (dy in 0 until h) taken += (r + dy) * 8L + c + dx
                    flow[p] = intArrayOf(c, r, w, h)
                    break
                }
                r++
            }
        }
    }

    // --- Touchers

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    /** Geste repris aux widgets (appui long, balayage) : ils ont reçu ACTION_CANCEL. */
    private var stolen = false
    private var swiping = false
    private var onSlider = false
    private var owner: GestureOwner? = null
    private var ignoreUntilUp = false

    /** Widget maintenu : il s'agrandit ([Listener.onExpand]). Le mode Modifier s'ouvre par le crayon de la barre. */
    private val expand = Runnable {
        val placed = placedAt(downX, downY)?.takeIf { it.type.zoomable && !blocked(it) } ?: return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        cancelChildren()
        ignoreUntilUp = true
        listener?.onExpand(placed)
    }

    private fun placedAt(x: Float, y: Float): Placed? = (childCount - 1 downTo 0).map(::getChildAt).firstOrNull { child ->
        child.isVisible && x >= child.left && x < child.right && y >= child.top && y < child.bottom && placedOf(child) != null
    }?.let(::placedOf)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (editing) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) ignoreUntilUp = false
            if (!ignoreUntilUp) handleEdit(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) ignoreUntilUp = false
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = SystemClock.uptimeMillis()
                stolen = false; swiping = false; ignoreUntilUp = false
                onSlider = sliderAt(ev.x, ev.y)
                owner = ownerAt(ev.x, ev.y)
                if (GestureRules.allowLongPress(owner, onSlider)) postDelayed(expand, longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(expand)
                if (!stolen && GestureRules.allowSwipe(owner, onSlider, narrow) && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2 &&
                    !scrollsAt(downX, downY, if (dy < 0) 1 else -1)
                ) {
                    cancelChildren()
                    stolen = true
                    swiping = true
                }
                if (swiping) listener?.onPageDrag(dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(expand)
                if (swiping && ev.actionMasked == MotionEvent.ACTION_UP) {
                    val dy = ev.y - downY
                    // Vers le haut : profil suivant, qui arrive par le bas (Look.sharedAxis de HomeFragment.showProfile).
                    if (abs(dy) > height / 8) listener?.onPageSwipe(if (dy < 0) 1 else -1) else listener?.onPageDragEnd()
                } else if (swiping) {
                    listener?.onPageDragEnd()
                }
                val wasStolen = stolen || ignoreUntilUp
                stolen = false; swiping = false; ignoreUntilUp = false
                if (wasStolen) return true
            }
        }
        if (stolen || ignoreUntilUp) return true
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        return (0 until childCount).any { i ->
            val child = getChildAt(i)
            val placed = placedOf(child)
            placed != null && child.isVisible && blocked(placed) &&
                ev.x >= child.left && ev.x < child.right && ev.y >= child.top && ev.y < child.bottom
        }
    }

    private fun cancelChildren() {
        if (stolen) return
        stolen = true
        val cancel = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /**
     * Une vue sous le doigt défile encore dans ce sens (`direction` 1 : son contenu monte, le doigt va vers le haut) :
     * le geste est pour elle, pas pour changer de profil.
     */
    private fun scrollsAt(x: Float, y: Float, direction: Int): Boolean {
        fun find(view: View, x: Float, y: Float): Boolean {
            if (view.visibility != View.VISIBLE) return false
            if (x < view.left || x >= view.right || y < view.top || y >= view.bottom) return false
            if (view.canScrollVertically(direction)) return true
            if (view is ViewGroup) {
                val lx = x - view.left + view.scrollX
                val ly = y - view.top + view.scrollY
                for (i in view.childCount - 1 downTo 0) if (find(view.getChildAt(i), lx, ly)) return true
            }
            return false
        }
        for (i in childCount - 1 downTo 0) if (!veiled(getChildAt(i)) && find(getChildAt(i), x, y)) return true
        return false
    }

    /** Un curseur (volume) se tire à l'horizontale : pas de balayage de page ni d'appui long dessus. */
    private fun sliderAt(x: Float, y: Float): Boolean {
        fun find(view: View, x: Float, y: Float): Boolean {
            if (view.visibility != View.VISIBLE) return false
            if (x < view.left || x >= view.right || y < view.top || y >= view.bottom) return false
            if (view is SeekBar) return true
            if (view is ViewGroup) {
                val lx = x - view.left + view.scrollX
                val ly = y - view.top + view.scrollY
                for (i in view.childCount - 1 downTo 0) if (find(view.getChildAt(i), lx, ly)) return true
            }
            return false
        }
        for (i in childCount - 1 downTo 0) if (!veiled(getChildAt(i)) && find(getChildAt(i), x, y)) return true
        return false
    }

    /** Widget voilé (ordinateur injoignable) : il ne reçoit aucun toucher, donc ne garde ni appui long ni balayage. */
    private fun veiled(child: View) = placedOf(child)?.let(blocked) == true

    /**
     * Les gestes gardés par les vues sous le doigt : même descente que [sliderAt], les propriétaires imbriqués se
     * cumulent (un widget qui garde le balayage, une touche dedans qui garde l'appui long). Null s'il n'y en a pas.
     */
    private fun ownerAt(x: Float, y: Float): GestureOwner? {
        var longPress = false
        var swipe = false
        // Vrai si un propriétaire est dans la vue : les frères du dessous restent lus tant que rien n'est trouvé
        // (un calque transparent au-dessus d'un propriétaire ne lui prend pas le geste).
        fun walk(view: View, x: Float, y: Float): Boolean {
            if (view.visibility != View.VISIBLE) return false
            if (x < view.left || x >= view.right || y < view.top || y >= view.bottom) return false
            var found = false
            if (view is GestureOwner) {
                longPress = longPress || view.ownsLongPress
                swipe = swipe || view.ownsSwipe
                found = true
            }
            if (view is ViewGroup) {
                val lx = x - view.left + view.scrollX
                val ly = y - view.top + view.scrollY
                for (i in view.childCount - 1 downTo 0) if (walk(view.getChildAt(i), lx, ly)) { found = true; break }
            }
            return found
        }
        for (i in childCount - 1 downTo 0) if (!veiled(getChildAt(i)) && walk(getChildAt(i), x, y)) break
        if (!longPress && !swipe) return null
        return object : GestureOwner {
            override val ownsLongPress = longPress
            override val ownsSwipe = swipe
        }
    }

    // --- Mode Modifier : toucher, glisser

    private var dragView: View? = null
    private var dragging = false
    private var dropCol = 0
    private var dropRow = 0
    private var dropOk = false
    private var pressed: Hit? = null

    /** Poignée tirée : widget, zone brute sous le doigt (px de la grille) et taille qui s'y calerait. */
    private var resizeView: View? = null
    private var resizing = false
    private var resizePick: Fit? = null
    private val resizeZone = RectF()

    private sealed class Hit {
        class Remove(val placed: Placed) : Hit()
        class Resize(val view: View, val placed: Placed) : Hit()
        class Settings(val placed: Placed) : Hit()
        class Body(val view: View, val placed: Placed) : Hit()
        class Empty(val col: Int, val row: Int) : Hit()
    }

    private fun handleEdit(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                pressed = hitAt(ev.x, ev.y)
                dragging = false
                dragView = (pressed as? Hit.Body)?.view
                resizeView = (pressed as? Hit.Resize)?.view
                resizing = false
                resizePick = null
            }
            MotionEvent.ACTION_MOVE -> {
                resizeView?.let { return moveResize(it, ev) }
                val view = dragView ?: return
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    view.bringToFront()
                    view.alpha = 0.85f
                }
                if (dragging) {
                    view.translationX = dx
                    view.translationY = dy
                    val placed = placedOf(view) ?: return
                    dropCol = ((view.left + dx - paddingLeft) / (cellW + gap)).roundToInt().coerceIn(0, COLS - placed.size.w)
                    val y = view.top + dy - paddingTop
                    dropRow = (0..ROWS - placed.size.h).minBy { abs(rowTop(it) - y) }
                    dropOk = listener?.canMove(placed, dropCol, dropRow) == true
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val hit = pressed
                val resized = resizeView?.takeIf { resizing }?.let(::placedOf)
                val fit = resizePick
                if (resizeView != null) {
                    endDrag()
                    if (resized != null && fit != null && fit != Fit(resized.size, resized.col, resized.row)) listener?.onResize(resized, fit)
                } else if (dragging) {
                    val placed = dragView?.let { placedOf(it) }
                    val ok = dropOk
                    endDrag()
                    if (placed != null && ok) listener?.onMove(placed, dropCol, dropRow)
                } else if (hit != null && hit.javaClass == hitAt(ev.x, ev.y)?.javaClass) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    when (hit) {
                        is Hit.Remove -> listener?.onRemove(hit.placed)
                        is Hit.Resize -> Unit
                        is Hit.Settings -> listener?.onSettings(hit.placed)
                        is Hit.Empty -> listener?.onAddAt(hit.col, hit.row)
                        is Hit.Body -> if (listener?.hasSettings(hit.placed) == true) listener?.onSettings(hit.placed)
                    }
                }
                pressed = null
            }
            MotionEvent.ACTION_CANCEL -> { endDrag(); pressed = null }
        }
    }

    /** Poignée en cours de tirage : la zone suit le doigt, la taille permise la plus proche se cale sur la grille. */
    private fun moveResize(view: View, ev: MotionEvent) {
        val placed = placedOf(view) ?: return
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (!resizing) {
            if (abs(dx) <= slop && abs(dy) <= slop) return
            resizing = true
            view.alpha = 0.85f
        }
        // Zone entre la case en haut à gauche du widget et le doigt, dans tous les sens : vers la gauche ou le haut,
        // un widget collé au bord droit ou en bas peut quand même grandir.
        val x = view.right + dx
        val y = view.bottom + dy
        val origin = cellFrame(placed.col, placed.row, WidgetSize.S)
        resizeZone.set(min(origin.left, x), min(origin.top, y), max(origin.right, x), max(origin.bottom, y))
        val fx = (x - paddingLeft + gap) / (cellW + gap)
        val fy = cellsSpanned(0, y - paddingTop)
        resizePick = listener?.pickSize(
            placed,
            min(placed.col.toFloat(), fx),
            min(placed.row.toFloat(), fy),
            max(placed.col + 1f, fx),
            max(placed.row + 1f, fy),
        )
        invalidate()
    }

    /** Lignes couvertes, fractions comprises, de la ligne `row` jusqu'à l'ordonnée `y` (sous le padding). */
    private fun cellsSpanned(row: Int, y: Float): Float {
        var cells = 0f
        for (r in row until ROWS) cells += ((y - rowTop(r) + gap) / (rowHeight(r) + gap)).coerceIn(0f, 1f)
        return cells
    }

    private fun endDrag() {
        dragView?.apply { translationX = 0f; translationY = 0f; alpha = 1f }
        dragView = null
        dragging = false
        resizeView?.alpha = 1f
        resizeView = null
        resizing = false
        resizePick = null
        invalidate()
    }

    private fun hitAt(x: Float, y: Float): Hit? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (removeRect(child).touchable().contains(x, y)) return Hit.Remove(placed)
            if (listener?.hasSettings(placed) == true && settingsRect(child).touchable().contains(x, y)) return Hit.Settings(placed)
            if (placed.type.sizes.size > 1 && resizeRect(child).touchable().contains(x, y)) return Hit.Resize(child, placed)
        }
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility == View.VISIBLE && x >= child.left && x < child.right && y >= child.top && y < child.bottom) return Hit.Body(child, placed)
        }
        val col = ((x - paddingLeft) / (cellW + gap)).toInt()
        val row = rowAt((y - paddingTop).coerceAtLeast(0f))
        return if (col in 0 until COLS && row in 0 until ROWS) Hit.Empty(col, row) else null
    }

    // Boutons du mode Modifier, posés sur les coins du widget (grandes cibles : la tablette se touche de loin).
    private val button = dp(44)
    private val inset = dp(8)

    /** Zone de toucher d'un bouton : 2 dp de plus de chaque côté que le dessin (44 dp), soit 48 dp. */
    private val touchSlack = (dp(48) - button) / 2f

    private fun RectF.touchable() = apply { inset(-touchSlack, -touchSlack) }

    /** Haut des boutons du haut (✕ et ⚙) : à cheval sur le bord haut du widget, son titre reste lisible. */
    private fun topButtonsY(v: View) = v.top - button / 2

    private fun removeRect(v: View) = RectF((v.left + inset).toFloat(), topButtonsY(v).toFloat(), (v.left + inset + button).toFloat(), (topButtonsY(v) + button).toFloat())

    private fun settingsRect(v: View) = RectF((v.right - inset - button).toFloat(), topButtonsY(v).toFloat(), (v.right - inset).toFloat(), (topButtonsY(v) + button).toFloat())

    /** Poignée de redimensionnement : coin bas-droit, dans le widget. */
    private fun resizeRect(v: View) = RectF((v.right - inset - button).toFloat(), (v.bottom - inset - button).toFloat(), (v.right - inset).toFloat(), (v.bottom - inset).toFloat())

    /** Cadre des cases de `size` à partir de (`col`, `row`) : la taille calée sur la grille, pour le dépôt et le redimensionnement. */
    private fun cellFrame(col: Int, row: Int, size: WidgetSize): RectF {
        val left = paddingLeft + col * (cellW + gap)
        val top = paddingTop + rowTop(row)
        return RectF(left.toFloat(), top.toFloat(), (left + size.w * cellW + (size.w - 1) * gap).toFloat(), (top + spanHeight(row, size.h)).toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (editing && !narrow) drawEmptyCells(canvas)
        // Écran vide hors mode Modifier : dire comment le remplir, au lieu d'une page blanche.
        if (!editing && pageEmpty()) {
            canvas.drawText(emptyHint, width / 2f, height / 2f - (hintText.descent() + hintText.ascent()) / 2, hintText)
        }
        super.dispatchDraw(canvas)
        if (!editing) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility != View.VISIBLE) continue
            val dx = child.translationX
            val dy = child.translationY
            canvas.save()
            canvas.translate(dx, dy)
            stroke.color = accent
            val radius = 20 * density
            canvas.drawRoundRect(RectF(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat()), radius, radius, stroke)
            drawRound(canvas, removeRect(child), ko) { r -> drawIcon(canvas, removeIcon, r) }
            if (placed.type.sizes.size > 1) drawRound(canvas, resizeRect(child), raised) { r -> drawIcon(canvas, resizeIcon, r) }
            if (listener?.hasSettings(placed) == true) drawRound(canvas, settingsRect(child), raised) { r -> drawIcon(canvas, settingsIcon, r) }
            canvas.restore()
        }
        if (dragging) {
            val placed = dragView?.let { placedOf(it) } ?: return
            val rect = cellFrame(dropCol, dropRow, placed.size)
            stroke.color = if (dropOk) accent else ko
            fill.color = (if (dropOk) accent else ko) and 0x33FFFFFF
            canvas.drawRoundRect(rect, 20 * density, 20 * density, fill)
            canvas.drawRoundRect(rect, 20 * density, 20 * density, stroke)
        }
        if (resizing) {
            if (resizeView?.let { placedOf(it) } == null) return
            // Zone sous le doigt en pointillés ; la taille permise où elle se calera, pleine.
            dashed.color = accent
            canvas.drawRoundRect(resizeZone, 20 * density, 20 * density, dashed)
            dashed.color = themeColor(context, R.attr.c_stroke)
            resizePick?.let { fit ->
                val rect = cellFrame(fit.col, fit.row, fit.size)
                stroke.color = accent
                fill.color = accent and 0x33FFFFFF
                canvas.drawRoundRect(rect, 20 * density, 20 * density, fill)
                canvas.drawRoundRect(rect, 20 * density, 20 * density, stroke)
            }
        }
    }

    private inline fun drawRound(canvas: Canvas, rect: RectF, color: Int, content: (RectF) -> Unit) {
        fill.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        content(rect)
    }

    private fun drawIcon(canvas: Canvas, icon: android.graphics.drawable.Drawable, r: RectF) {
        val s = dp(24)
        val l = (r.centerX() - s / 2).toInt()
        val t = (r.centerY() - s / 2).toInt()
        icon.setBounds(l, t, l + s, t + s)
        icon.draw(canvas)
    }

    /** Cases libres de la page : pointillés et « + » (toucher = ajouter un widget ici). */
    private fun drawEmptyCells(canvas: Canvas) {
        val taken = (0 until childCount).mapNotNull { placedOf(getChildAt(it)) }.filter { it.page == page }
        text.color = faint
        text.textSize = 34 * density
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (taken.any { it.covers(c, r) }) continue
            val left = (paddingLeft + c * (cellW + gap)).toFloat()
            val top = (paddingTop + rowTop(r)).toFloat()
            val rect = RectF(left + dp(2), top + dp(2), left + cellW - dp(2), top + rowHeight(r) - dp(2))
            canvas.drawRoundRect(rect, 20 * density, 20 * density, dashed)
            canvas.drawText("+", rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
