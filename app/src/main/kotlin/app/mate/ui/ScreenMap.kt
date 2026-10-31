package app.mate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.net.ComputerDisplay
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Écrans du Mac à l'échelle, placés comme dans les réglages Moniteurs ; chaque écran liste ses fenêtres.
 * On y dépose une fenêtre glissée (View.startDragAndDrop) : au centre elle passe sur cet écran,
 * sur un bord elle en prend la moitié, en haut tout l'écran utile.
 */
class ScreenMap @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** `layout` : keep, left, right ou max (comme placeWindow côté Mac). */
    var onDrop: (payload: Any?, display: ComputerDisplay, layout: String) -> Unit = { _, _, _ -> }
    var onDragEnded: () -> Unit = {}

    /** Étire les écrans pour remplir la vue (vue « Ouvertes » : des listes de fenêtres, la forme exacte compte peu). */
    var stretch = false

    private var displays: List<ComputerDisplay> = emptyList()
    private val gap = dp(12)

    /** Remplace les écrans ; `cards` donne les vues de fenêtres à afficher dans chacun. */
    fun setScreens(displays: List<ComputerDisplay>, cards: (ComputerDisplay) -> List<View>) {
        // La liste relue en boucle (vue « Ouvertes ») ne doit pas ramener chaque écran en haut.
        val scrolled = (0 until childCount).mapNotNull { getChildAt(it) as? Pane }.associate { it.screen.id to it.scroll.scrollY }
        this.displays = displays
        removeAllViews()
        displays.forEach { d ->
            val pane = Pane(d).apply { cards(d).forEach(::addCard) }
            addView(pane)
            scrolled[d.id]?.let { y -> pane.scroll.post { pane.scroll.scrollTo(0, y) } }
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(width, height)
        val frame = frame(width, height)
        for (i in 0 until childCount) {
            val r = frame.rect(displays[i])
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(r[2] - r[0], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r[3] - r[1], MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val frame = frame(r - l, b - t)
        for (i in 0 until childCount) {
            val rect = frame.rect(displays[i])
            getChildAt(i).layout(rect[0], rect[1], rect[2], rect[3])
        }
    }

    /** Échelle et décalage qui centrent l'arrangement des écrans dans la vue. */
    private inner class Frame(val scaleX: Float, val scaleY: Float, val minX: Int, val minY: Int, val dx: Float, val dy: Float) {
        fun rect(d: ComputerDisplay): IntArray {
            val left = dx + (d.x - minX) * scaleX
            val top = dy + (d.y - minY) * scaleY
            return intArrayOf(
                (left + gap / 2).toInt(), (top + gap / 2).toInt(),
                (left + d.w * scaleX - gap / 2).toInt(), (top + d.h * scaleY - gap / 2).toInt(),
            )
        }
    }

    private fun frame(width: Int, height: Int): Frame {
        if (displays.isEmpty()) return Frame(1f, 1f, 0, 0, 0f, 0f)
        val minX = displays.minOf { it.x }
        val minY = displays.minOf { it.y }
        val spanW = (displays.maxOf { it.x + it.w } - minX).coerceAtLeast(1)
        val spanH = (displays.maxOf { it.y + it.h } - minY).coerceAtLeast(1)
        val fitX = width.toFloat() / spanW
        val fitY = height.toFloat() / spanH
        // Étirés, mais pas au point qu'un écran devienne une bande : 3 fois plus haut (ou large) au plus.
        val scaleX = if (stretch) minOf(fitX, fitY * MAX_STRETCH) else minOf(fitX, fitY)
        val scaleY = if (stretch) minOf(fitY, fitX * MAX_STRETCH) else scaleX
        return Frame(scaleX, scaleY, minX, minY, (width - spanW * scaleX) / 2, (height - spanH * scaleY) / 2)
    }

    /** Un écran : nom, fenêtres (défilables), et aperçu de la place prise pendant un glisser. */
    private inner class Pane(val screen: ComputerDisplay) : FrameLayout(context) {
        private val cards = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context)
        private val preview = View(context).apply {
            background = rounded(R.attr.c_accent_soft, dp(8)).apply { setStroke(dp(2), color(R.attr.c_accent)) }
            visibility = GONE
        }
        private val previewLabel = TextView(context).apply {
            textSize = 15f
            setTextColor(color(R.attr.c_accent))
            gravity = Gravity.CENTER
            visibility = GONE
        }
        private var zone: String? = null

        init {
            background = frameBackground(highlight = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            // Nom de l'écran et son rôle : le principal porte la barre des menus et le Dock.
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, 0, dp(6))
            }
            header.addView(TextView(context).apply {
                text = screen.label
                textSize = 14f
                setTextColor(color(R.attr.c_text))
                maxLines = 1
            })
            header.addView(TextView(context).apply {
                setText(if (screen.main) R.string.screen_main else R.string.screen_secondary)
                textSize = 12f
                setTextColor(color(if (screen.main) R.attr.c_accent else R.attr.c_text_dim))
                background = rounded(if (screen.main) R.attr.c_accent_soft else R.attr.c_surface_raised, dp(8))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                maxLines = 1
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            content.addView(header)
            content.addView(scroll.apply {
                isVerticalScrollBarEnabled = false
                addView(cards)
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(preview, LayoutParams(0, 0))
            addView(previewLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnDragListener { _, event -> onDrag(event) }
        }

        fun addCard(card: View) {
            cards.addView(card, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }

        private fun onDrag(event: DragEvent): Boolean {
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> return true
                DragEvent.ACTION_DRAG_ENTERED -> background = frameBackground(highlight = true)
                DragEvent.ACTION_DRAG_LOCATION -> showZone(zoneAt(event.x, event.y))
                DragEvent.ACTION_DRAG_EXITED -> clear()
                DragEvent.ACTION_DROP -> {
                    val layout = zoneAt(event.x, event.y)
                    clear()
                    onDrop(event.localState, screen, layout)
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    clear()
                    // Chaque écran reçoit la fin du glisser : un seul rappel suffit.
                    if (this == this@ScreenMap.getChildAt(0)) onDragEnded()
                }
            }
            return true
        }

        private fun zoneAt(x: Float, y: Float) = when {
            x < width * EDGE -> "left"
            x > width * (1 - EDGE) -> "right"
            y < height * EDGE -> "max"
            else -> "keep"
        }

        private fun showZone(next: String) {
            if (next == zone) return
            zone = next
            val inset = dp(6)
            val w = width - 2 * inset
            val h = height - 2 * inset
            val params = preview.layoutParams as LayoutParams
            when (next) {
                "left" -> { params.width = w / 2; params.height = h; params.leftMargin = inset; params.topMargin = inset }
                "right" -> { params.width = w / 2; params.height = h; params.leftMargin = inset + w - w / 2; params.topMargin = inset }
                "max" -> { params.width = w; params.height = h; params.leftMargin = inset; params.topMargin = inset }
                else -> { params.width = w * 3 / 5; params.height = h * 3 / 5; params.leftMargin = inset + w / 5; params.topMargin = inset + h / 5 }
            }
            preview.layoutParams = params
            preview.visibility = VISIBLE
            previewLabel.setText(when (next) {
                "left" -> R.string.windows_left
                "right" -> R.string.windows_right
                "max" -> R.string.windows_max
                else -> R.string.windows_move_here
            })
            previewLabel.visibility = VISIBLE
        }

        private fun clear() {
            zone = null
            preview.visibility = GONE
            previewLabel.visibility = GONE
            background = frameBackground(highlight = false)
        }

        private fun frameBackground(highlight: Boolean) = rounded(R.attr.c_bg, dp(12)).apply {
            setStroke(dp(2), color(if (highlight) R.attr.c_accent else R.attr.c_stroke))
        }
    }

    private fun rounded(colorRes: Int, radius: Int) = GradientDrawable().apply {
        setColor(color(colorRes))
        cornerRadius = radius.toFloat()
    }

    private fun color(res: Int) = themeColor(context, res)

    private companion object {
        /** Part de la largeur (ou de la hauteur, en haut) qui déclenche une moitié d'écran ou l'agrandissement. */
        const val EDGE = 0.22f
        const val MAX_STRETCH = 3f
    }
}

/**
 * Fenêtre d'un ScreenMap. Toucher : `onTap` (attendu un instant si `onDoubleTap` est donné ; de même pour le double
 * toucher si `onTripleTap` est donné). Glisser-déposer `payload` vers un écran (vue glissée estompée, passée à
 * `onDragStarted`) : en bougeant le doigt de côté, ou après un appui maintenu (écrans l'un au-dessus de l'autre) ;
 * un mouvement vertical laisse défiler la liste de l'écran.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.dragToScreens(
    payload: Any, canDrag: () -> Boolean, onDragStarted: (View) -> Unit, onDoubleTap: (() -> Unit)? = null,
    onTripleTap: (() -> Unit)? = null, onTap: () -> Unit,
) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var moved = false
    var dragging = false
    fun startDrag(v: View) {
        dragging = true
        v.isPressed = false
        v.parent.requestDisallowInterceptTouchEvent(true)
        if (canDrag() && v.startDragAndDrop(null, View.DragShadowBuilder(v), payload, 0)) {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onDragStarted(v)
            v.alpha = 0.35f
        }
    }
    val hold = Runnable { if (!moved && !dragging) startDrag(this) }
    var pendingTap: Runnable? = null
    var taps = 0
    fun fire(count: Int) {
        pendingTap = null
        taps = 0
        when (count) {
            1 -> onTap()
            2 -> onDoubleTap?.invoke()
            else -> onTripleTap?.invoke()
        }
    }
    // Chaque toucher attend le suivant, sauf le dernier geste possible (double sans triple, triple).
    fun tapped(v: View) {
        pendingTap?.let(v::removeCallbacks)
        taps++
        val last = when {
            onDoubleTap == null -> 1
            onTripleTap == null -> 2
            else -> 3
        }
        if (taps >= last) return fire(taps)
        val count = taps
        pendingTap = Runnable { fire(count) }.also { v.postDelayed(it, ViewConfiguration.getDoubleTapTimeout().toLong()) }
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                dragging = false
                v.isPressed = true
                v.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!moved && !dragging && hypot(e.x - downX, e.y - downY) > slop) {
                moved = true
                v.isPressed = false
                v.removeCallbacks(hold)
                // De côté : glisser vers un écran. Vers le haut ou le bas : la liste de l'écran défile (elle prend le geste).
                if (abs(e.x - downX) > abs(e.y - downY)) startDrag(v)
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                v.removeCallbacks(hold)
                if (!moved && !dragging) tapped(v)
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                v.removeCallbacks(hold)
            }
        }
        true
    }
}
