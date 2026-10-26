package com.unowhy.companion.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
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
import com.unowhy.companion.R
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.themeColor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Grille de l'Accueil : place chaque widget (vue dont le `tag` est son [Placed]) sur ses cases. Seule la page
 * affichée est visible. Appui long n'importe où : mode Modifier ; balayage horizontal : page suivante.
 *
 * Mode Modifier : les widgets ne reçoivent plus les touchers. Chacun a un ✕ (retirer), sa taille (changer) et, s'il
 * en a, ⚙ (réglages) ; on le glisse vers d'autres cases ; une case vide (+) ajoute un widget à cet endroit.
 *
 * Étroit (panneau document ouvert) : 2 colonnes, les widgets se suivent dans l'ordre de lecture (la page défile).
 */
class WidgetGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    interface Listener {
        fun onEnterEdit()
        fun onPageSwipe(delta: Int)
        fun onAddAt(col: Int, row: Int)
        fun onRemove(placed: Placed)
        fun onCycleSize(placed: Placed)
        fun onSettings(placed: Placed)
        fun hasSettings(placed: Placed): Boolean
        fun canMove(placed: Placed, col: Int, row: Int): Boolean
        fun onMove(placed: Placed, col: Int, row: Int)
    }

    var listener: Listener? = null
    var editing = false
        set(value) { field = value; endDrag(); invalidate() }
    var page = 0
        set(value) { field = value; showPage() }
    var narrow = false
        set(value) { if (field != value) { field = value; requestLayout() } }
    /** Hauteur visible (le ScrollView autour mesure sans limite) : la grille normale la remplit exactement. */
    var viewportHeight = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val density = resources.displayMetrics.density
    private val gap = dp(20)
    private val narrowRowHeight = dp(176)
    private var cellW = 0
    private var cellH = 0
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
    private val accent = themeColor(context, R.attr.c_accent)
    private val raised = themeColor(context, R.attr.c_surface_raised)
    private val ko = themeColor(context, R.attr.c_ko)
    private val textColor = themeColor(context, R.attr.c_text)
    private val faint = themeColor(context, R.attr.c_text_faint)

    init {
        setWillNotDraw(false)
        removeIcon.setTint(textColor)
        settingsIcon.setTint(textColor)
    }

    private fun placedOf(child: View) = child.tag as? Placed

    // --- Mesure et placement

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = if (narrow) 2 else COLS
        cellW = (width - paddingLeft - paddingRight - gap * (cols - 1)) / cols
        val height: Int
        if (narrow) {
            computeFlow()
            val rows = flow.values.maxOfOrNull { it[1] + it[3] } ?: 1
            cellH = narrowRowHeight
            height = paddingTop + paddingBottom + rows * cellH + (rows - 1) * gap
        } else {
            height = if (viewportHeight > 0) viewportHeight else MeasureSpec.getSize(heightMeasureSpec)
            cellH = (height - paddingTop - paddingBottom - gap * (ROWS - 1)) / ROWS
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility == View.GONE) continue
            val (_, _, w, h) = cells(placed)
            child.measure(
                MeasureSpec.makeMeasureSpec(w * cellW + (w - 1) * gap, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h * cellH + (h - 1) * gap, MeasureSpec.EXACTLY),
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
            val top = paddingTop + row * (cellH + gap)
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
        requestLayout()
        invalidate()
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
    private var ignoreUntilUp = false

    private val enterEdit = Runnable {
        if (narrow) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        cancelChildren()
        ignoreUntilUp = true
        listener?.onEnterEdit()
    }

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
                if (!onSlider) postDelayed(enterEdit, longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(enterEdit)
                if (!stolen && !onSlider && !narrow && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2) {
                    cancelChildren()
                    stolen = true
                    swiping = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(enterEdit)
                if (swiping && ev.actionMasked == MotionEvent.ACTION_UP) {
                    val dx = ev.x - downX
                    if (abs(dx) > width / 8) listener?.onPageSwipe(if (dx < 0) 1 else -1)
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

    private fun cancelChildren() {
        if (stolen) return
        stolen = true
        val cancel = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
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
        for (i in childCount - 1 downTo 0) if (find(getChildAt(i), x, y)) return true
        return false
    }

    // --- Mode Modifier : toucher, glisser

    private var dragView: View? = null
    private var dragging = false
    private var dropCol = 0
    private var dropRow = 0
    private var dropOk = false
    private var pressed: Hit? = null

    private sealed class Hit {
        class Remove(val placed: Placed) : Hit()
        class Size(val placed: Placed) : Hit()
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
            }
            MotionEvent.ACTION_MOVE -> {
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
                    dropRow = ((view.top + dy - paddingTop) / (cellH + gap)).roundToInt().coerceIn(0, ROWS - placed.size.h)
                    dropOk = listener?.canMove(placed, dropCol, dropRow) == true
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val hit = pressed
                if (dragging) {
                    val placed = dragView?.let { placedOf(it) }
                    val ok = dropOk
                    endDrag()
                    if (placed != null && ok) listener?.onMove(placed, dropCol, dropRow)
                } else if (hit != null && hit.javaClass == hitAt(ev.x, ev.y)?.javaClass) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    when (hit) {
                        is Hit.Remove -> listener?.onRemove(hit.placed)
                        is Hit.Size -> listener?.onCycleSize(hit.placed)
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

    private fun endDrag() {
        dragView?.apply { translationX = 0f; translationY = 0f; alpha = 1f }
        dragView = null
        dragging = false
        invalidate()
    }

    private fun hitAt(x: Float, y: Float): Hit? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (removeRect(child).contains(x, y)) return Hit.Remove(placed)
            if (placed.type.sizes.size > 1 && sizeRect(child, placed).contains(x, y)) return Hit.Size(placed)
            if (listener?.hasSettings(placed) == true && settingsRect(child).contains(x, y)) return Hit.Settings(placed)
        }
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            val placed = placedOf(child) ?: continue
            if (child.visibility == View.VISIBLE && x >= child.left && x < child.right && y >= child.top && y < child.bottom) return Hit.Body(child, placed)
        }
        val col = ((x - paddingLeft) / (cellW + gap)).toInt()
        val row = ((y - paddingTop) / (cellH + gap)).toInt()
        return if (col in 0 until COLS && row in 0 until ROWS) Hit.Empty(col, row) else null
    }

    // Boutons du mode Modifier, posés sur les coins du widget (grandes cibles : la tablette se touche de loin).
    private val button = dp(44)
    private val inset = dp(8)

    private fun removeRect(v: View) = RectF((v.left + inset).toFloat(), (v.top + inset).toFloat(), (v.left + inset + button).toFloat(), (v.top + inset + button).toFloat())

    private fun settingsRect(v: View) = RectF((v.right - inset - button).toFloat(), (v.bottom - inset - button).toFloat(), (v.right - inset).toFloat(), (v.bottom - inset).toFloat())

    private fun sizeLabel(placed: Placed) = context.getString(placed.size.label)

    private fun sizeRect(v: View, placed: Placed): RectF {
        text.textSize = 15 * density
        val w = text.measureText(sizeLabel(placed) + "  ⇆") + dp(28)
        return RectF(v.right - inset - w, (v.top + inset).toFloat(), (v.right - inset).toFloat(), (v.top + inset + button).toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (editing && !narrow) drawEmptyCells(canvas)
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
            if (placed.type.sizes.size > 1) drawRound(canvas, sizeRect(child, placed), raised) { r ->
                text.color = textColor
                text.textSize = 15 * density
                canvas.drawText(sizeLabel(placed) + "  ⇆", r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
            if (listener?.hasSettings(placed) == true) drawRound(canvas, settingsRect(child), raised) { r -> drawIcon(canvas, settingsIcon, r) }
            canvas.restore()
        }
        if (dragging) {
            val placed = dragView?.let { placedOf(it) } ?: return
            val left = paddingLeft + dropCol * (cellW + gap)
            val top = paddingTop + dropRow * (cellH + gap)
            val rect = RectF(left.toFloat(), top.toFloat(), (left + placed.size.w * cellW + (placed.size.w - 1) * gap).toFloat(), (top + placed.size.h * cellH + (placed.size.h - 1) * gap).toFloat())
            stroke.color = if (dropOk) accent else ko
            fill.color = (if (dropOk) accent else ko) and 0x33FFFFFF
            canvas.drawRoundRect(rect, 20 * density, 20 * density, fill)
            canvas.drawRoundRect(rect, 20 * density, 20 * density, stroke)
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
            val top = (paddingTop + r * (cellH + gap)).toFloat()
            val rect = RectF(left + dp(2), top + dp(2), left + cellW - dp(2), top + cellH - dp(2))
            canvas.drawRoundRect(rect, 20 * density, 20 * density, dashed)
            canvas.drawText("+", rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
