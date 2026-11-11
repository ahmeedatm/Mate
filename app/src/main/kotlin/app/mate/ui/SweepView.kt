package app.mate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.AnimFamily
import app.mate.R

/**
 * Balayage orange des thèmes Instrument, au changement d'écran : un aplat c_signal traverse la zone de contenu. Il couvre
 * l'ancien écran (une image prise juste avant le changement, [capture]), puis découvre le nouveau. Le numéro de l'écran et
 * son nom roulent avec lui. Durées et course : [SweepModel]. Rien d'autre qu'alpha et translation, sur calque matériel.
 * Coupé (Réduire les animations, échelle d'animation à zéro, famille NAVIGATION désactivée) : pas de balayage.
 */
class SweepView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val plate = FrameLayout(context).apply { setBackgroundColor(themeColor(context, R.attr.c_signal)) }
    private val edge = View(context).apply { setBackgroundColor(themeColor(context, R.attr.c_signal_edge)) }
    private val text = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val number = TextView(context).apply {
        typeface = themeFont(context, R.attr.c_font_display)
        setTextColor(themeColor(context, R.attr.c_on_signal))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, NUMBER_SP)
        includeFontPadding = false
    }
    private val name = TextView(context).apply {
        typeface = themeFont(context, R.attr.c_font_title)
        setTextColor(themeColor(context, R.attr.c_on_signal))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP)
        isAllCaps = true
        letterSpacing = NAME_SPACING
        includeFontPadding = false
    }
    private var snapshot: Bitmap? = null
    private var generation = 0

    init {
        setBackgroundColor(themeColor(context, R.attr.c_bg))
        visibility = GONE
        addView(picture, LayoutParams(MATCH, MATCH))
        addView(plate, LayoutParams(MATCH, MATCH))
        plate.addView(edge, LayoutParams(dp(EDGE_DP), MATCH))
        text.addView(number, LinearLayout.LayoutParams(WRAP, WRAP))
        text.addView(name, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(GAP_DP) })
        plate.addView(text, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(TEXT_MARGIN_DP)
            rightMargin = dp(TEXT_MARGIN_DP)
            bottomMargin = dp(TEXT_BOTTOM_DP)
        })
    }

    /**
     * Image de [container] (le contenu du mode, sans le bandeau ni le balayage) avant le changement d'écran ; null quand
     * le balayage ne se joue pas (animations coupées, rien à montrer). Libérée par [play] ou [cancel].
     */
    fun capture(container: View): Bitmap? {
        if (!wanted(context) || container.width == 0 || container.height == 0) return null
        // RGB 565 : pas de transparence à garder, et deux fois moins de mémoire que l'ARGB sur 128 Mo de tas.
        return Bitmap.createBitmap(container.width, container.height, Bitmap.Config.RGB_565).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(themeColor(context, R.attr.c_bg))
                container.draw(this)
            }
        }
    }

    /** Joue le balayage sur [before] (l'ancien écran), vers [number] et [label] (le nouvel écran). */
    fun play(before: Bitmap, lastMove: Int, number: String, label: String) {
        cancel()
        val plan = SweepModel.plan(lastMove, width.toFloat(), resources.displayMetrics.density)
        place(plan.direction)
        this.number.text = number
        name.text = label
        snapshot = before
        picture.setImageBitmap(before)
        picture.visibility = VISIBLE
        plate.translationX = plan.plateStart
        text.translationX = plan.textStart
        visibility = VISIBLE
        val turn = ++generation
        plate.animate().translationX(0f).setDuration(SweepModel.COVER_MS).setInterpolator(EASE)
            .withEndAction { if (turn == generation) cover(plan, turn) }.start()
        text.animate().translationX(0f).setDuration(SweepModel.TEXT_COVER_MS).setInterpolator(TEXT_EASE).start()
    }

    /** L'aplat couvre le nouvel écran : l'image de l'ancien part, l'aplat continue et découvre le nouveau. */
    private fun cover(plan: SweepModel.Plan, turn: Int) {
        releasePicture()
        text.animate().cancel()
        plate.animate().translationX(plan.plateEnd).setDuration(SweepModel.REVEAL_MS).setInterpolator(EASE)
            .withEndAction { if (turn == generation) finish() }.start()
        text.animate().translationX(plan.textEnd).setDuration(SweepModel.TEXT_REVEAL_MS).setInterpolator(EASE).start()
    }

    private fun finish() {
        visibility = GONE
        plate.translationX = 0f
        text.translationX = 0f
    }

    /** Arrête un balayage en cours (un nouveau changement d'écran repart d'un aplat propre) et libère l'image. */
    fun cancel() {
        generation++
        plate.animate().cancel()
        text.animate().cancel()
        releasePicture()
        finish()
    }

    /** Libère l'image de l'ancien écran (tas de 128 Mo) : elle n'est plus affichée après le balayage. */
    private fun releasePicture() {
        picture.setImageDrawable(null)
        picture.visibility = GONE
        snapshot?.recycle()
        snapshot = null
    }

    /** Aplat et texte du côté d'où il arrive : le bord est sur le côté de sortie, le texte près du côté d'arrivée. */
    private fun place(direction: Int) {
        val edgeParams = edge.layoutParams as FrameLayout.LayoutParams
        edgeParams.gravity = Gravity.TOP or (if (direction > 0) Gravity.END else Gravity.START)
        edge.layoutParams = edgeParams
        val textParams = text.layoutParams as FrameLayout.LayoutParams
        textParams.gravity = Gravity.BOTTOM or (if (direction > 0) Gravity.START else Gravity.END)
        text.layoutParams = textParams
    }

    companion object {
        private const val MATCH = LayoutParams.MATCH_PARENT
        private const val WRAP = LayoutParams.WRAP_CONTENT
        private const val EDGE_DP = 14
        private const val GAP_DP = 30
        private const val TEXT_MARGIN_DP = 48
        private const val TEXT_BOTTOM_DP = 34
        private const val NUMBER_SP = 200f
        private const val NAME_SP = 100f
        private const val NAME_SPACING = 0.05f

        /** Courbe de l'aplat (maquette : cubic-bezier(.65,0,.35,1)) et du texte qui arrive (.22,1,.36,1). */
        private val EASE = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        private val TEXT_EASE = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        /** Le balayage se joue : famille NAVIGATION ouverte, animations non réduites ni coupées à l'échelle zéro. */
        fun wanted(context: Context): Boolean = Look.on(context, AnimFamily.NAVIGATION) && !Look.reduced(context)
    }
}
