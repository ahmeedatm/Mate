package app.mate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import androidx.dynamicanimation.animation.SpringAnimation
import app.mate.R
import kotlin.math.roundToInt

/** Cellule de remplissage d'un compteur (espace de la largeur d'un chiffre), à gauche d'un nombre plus court. */
const val FILL_CHAR = ' '

/** `text` complété à gauche par des cellules de remplissage jusqu'à `width` cellules. */
fun padCells(text: String, width: Int): String = text.padStart(width, FILL_CHAR)

/** Cellules qui changent de `old` à `new` (alignés à droite, complétés par [FILL_CHAR]) : seules celles-là bougent. */
fun changedCells(old: String, new: String): List<Int> {
    val width = maxOf(old.length, new.length)
    val a = padCells(old, width)
    val b = padCells(new, width)
    return (0 until width).filter { a[it] != b[it] }
}

/**
 * Texte en cellules de largeur fixe (chaque glyphe centré dans la sienne : les chiffres ne sautent pas) : une cellule qui change bascule, l'ancien glyphe sort par
 * le haut pendant que le nouveau monte du bas (DA Console douce). Un seul `View` dessiné à la main, pas un `TextView` par
 * chiffre ; il ne se redessine que pendant la bascule (400 à 800 ms), jamais entre deux changements.
 *
 * Entrée : translation 62 % de la hauteur → 0 (ressort par défaut) et fondu ; sortie : 62 % vers le haut (ressort rapide)
 * et fondu. 30 ms d'écart d'un chiffre au suivant. Les ressorts sont annulés quand la vue part.
 */
class RollingText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Cell(var cur: Char) {
        var prev = NONE
        var inY = 0f
        var inA = 1f
        var outY = 0f
        var outA = 0f
        var start: Runnable? = null
        val springs = ArrayList<SpringAnimation>(4)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Ombre du thème (Hush : 0 1px 6px en pixels, comme Widget.Hush.Text) ; transparente ailleurs, donc rien à dessiner de plus.
        val shadow = themeColor(context, R.attr.c_text_shadow)
        if (Color.alpha(shadow) != 0) {
            setShadowLayer(SHADOW_RADIUS_PX, 0f, 1f, shadow)
        }
    }
    private var cells = ArrayList<Cell>()
    private val one = CharArray(1)

    /** Hauteur de la vue, et hauteur de la case où le texte est centré (la case peut dépasser la vue : l'excédent est rogné). */
    private var viewHeightPx = 0
    private var boxHeightPx = 0
    /**
     * Largeur d'une cellule de chiffre, en em, au moins : élargie au chiffre le plus large de la police ([digitEm]),
     * sinon le « 0 » de la police débordait de sa case, rognée, et le premier chiffre de l'heure paraissait coupé. Les deux
     * points n'en prennent que [separatorEm].
     */
    var cellEm = 0.56f
        set(value) { field = value; requestLayout() }

    /** Chiffre le plus large de la police, en em (ne dépend pas de la taille) ; recalculé au changement de police. */
    private var digitEm = -1f
    var separatorEm = 0.26f
    /** Nombre de cellules au moins (compteur de deux chiffres : « 9 » garde sa cellule vide à gauche). */
    var minCells = 1
    var color = Color.BLACK
        set(value) { field = value; invalidate() }
    var typeface: Typeface?
        get() = paint.typeface
        set(value) { paint.typeface = value; digitEm = -1f; requestLayout(); invalidate() }

    /**
     * Taille du texte, hauteur de la vue et de la case du texte (0 : 1 em), en pixels. Sans `requestLayout` : à appeler
     * depuis le `onMeasure` du parent, juste avant de mesurer cette vue ; ailleurs, la vue se redimensionne au prochain
     * changement de texte.
     */
    fun setSize(sizePx: Float, viewHeight: Int = 0, boxHeight: Int = viewHeight) {
        if (paint.textSize == sizePx && viewHeightPx == viewHeight && boxHeightPx == boxHeight) return
        paint.textSize = sizePx
        viewHeightPx = viewHeight
        boxHeightPx = boxHeight
        forceLayout()
        invalidate()
    }

    /** Pose le texte. `animate` : les cellules qui changent basculent ; sinon elles changent d'un coup. */
    fun set(value: String, animate: Boolean) {
        val width = maxOf(value.length, cells.size, minCells)
        if (width != cells.size) grow(width)
        val next = padCells(value, width)
        val before = String(CharArray(width) { cells[it].cur })
        val changed = changedCells(before, next)
        contentDescription = value.replace(FILL_CHAR, ' ').trim()
        var digit = 0
        for (i in 0 until width) {
            val isDigit = next[i] != SEPARATOR
            if (i in changed) {
                val cell = cells[i]
                finish(cell)
                val old = cell.cur
                cell.cur = next[i]
                if (animate && old != next[i] && isDigit) roll(cell, old, digit * STAGGER_MS)
            }
            if (isDigit) digit++
        }
        requestLayout()
        invalidate()
    }

    private fun grow(width: Int) {
        cells.forEach { finish(it) }
        val old = cells
        cells = ArrayList<Cell>(width).apply {
            repeat(width - old.size) { add(Cell(FILL_CHAR)) }
            addAll(old)
        }
    }

    /** Pose l'état final de la cellule : plus de ressort, plus d'ancien glyphe. */
    private fun finish(cell: Cell) {
        cell.start?.let { removeCallbacks(it) }
        cell.start = null
        cell.springs.forEach { it.cancel() }
        cell.springs.clear()
        cell.prev = NONE
        cell.inY = 0f
        cell.inA = 1f
    }

    private fun roll(cell: Cell, old: Char, delayMs: Long) {
        val d = cellHeight() * SHIFT
        cell.prev = old
        cell.inY = d
        cell.inA = 0f
        cell.outY = 0f
        cell.outA = 1f
        val go = Runnable {
            cell.start = null
            cell.springs.addAll(
                listOfNotNull(
                    Look.spring(context, Look.SPATIAL_DEFAULT, d, 0f, { cell.inY = it; invalidate() }),
                    Look.spring(context, Look.EFFECTS_DEFAULT, 0f, 1f, { cell.inA = it; invalidate() }),
                    Look.spring(context, Look.SPATIAL_FAST, 0f, -d, { cell.outY = it; invalidate() }),
                    Look.spring(context, Look.EFFECTS_FAST, 1f, 0f, { cell.outA = it; invalidate() }, { cell.prev = NONE; invalidate() }),
                ),
            )
        }
        cell.start = go
        postDelayed(go, delayMs)
    }

    override fun onDetachedFromWindow() {
        cells.forEach { finish(it) }
        super.onDetachedFromWindow()
    }

    private fun cellHeight() = if (boxHeightPx > 0) boxHeightPx.toFloat() else paint.textSize

    private fun cellWidth(cell: Cell) = (if (cell.cur == SEPARATOR) separatorEm else maxOf(cellEm, widestDigit())) * paint.textSize

    private fun widestDigit(): Float {
        if (digitEm < 0f) {
            val size = paint.textSize
            paint.textSize = MEASURE_PX
            digitEm = DIGITS.maxOf { paint.measureText(it.toString()) } / MEASURE_PX * DIGIT_MARGIN
            paint.textSize = size
        }
        return digitEm
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = cells.sumOf { cellWidth(it).toDouble() }.roundToInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(if (viewHeightPx > 0) viewHeightPx else cellHeight().roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val metrics = paint.fontMetrics
        // Comme en CSS : le texte est centré dans sa ligne (1 em par défaut).
        val baseline = (cellHeight() - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent
        var x = 0f
        for (cell in cells) {
            val w = cellWidth(cell)
            val cx = x + w / 2
            if (cell.cur == SEPARATOR) {
                glyph(canvas, cell.cur, cx, baseline - 0.06f * paint.textSize, 1f)
            } else {
                canvas.withClip(x, 0f, x + w, height.toFloat()) {
                    if (cell.prev != NONE) glyph(canvas, cell.prev, cx, baseline + cell.outY, cell.outA)
                    glyph(canvas, cell.cur, cx, baseline + cell.inY, cell.inA)
                }
            }
            x += w
        }
    }

    private fun glyph(canvas: Canvas, ch: Char, cx: Float, y: Float, alpha: Float) {
        if (ch == FILL_CHAR || ch == ' ' || alpha <= 0f) return
        paint.color = color
        paint.alpha = (Color.alpha(color) * alpha.coerceAtMost(1f)).roundToInt()
        one[0] = ch
        canvas.drawText(one, 0, 1, cx, y, paint)
    }

    private companion object {
        const val NONE = '\u0000'
        const val SHADOW_RADIUS_PX = 6f
        const val SEPARATOR = ':'
        const val SHIFT = 0.62f
        const val STAGGER_MS = 30L
        const val DIGITS = "0123456789"
        const val MEASURE_PX = 100f

        /** Un peu d'air autour du chiffre le plus large : l'antialiasing de ses bords ne touche pas la case. */
        const val DIGIT_MARGIN = 1.04f
    }
}
