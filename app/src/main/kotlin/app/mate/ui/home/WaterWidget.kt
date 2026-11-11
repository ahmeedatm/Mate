package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.focus.Water
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.WaterSettings
import app.mate.ui.themeFont
import java.util.Date

/** Exemple de l'Eau pour le catalogue : verres bus, objectif et rappel, sans toucher aux vrais verres du jour. */
class WaterSample(val state: Water.State, val nextReminderMs: Long?) : WidgetSample

/**
 * Verres d'eau du jour (sur la tablette, sans l'ordinateur) : un segment par verre de l'objectif, rempli au fil de la
 * journée ; + = un verre bu, − = corriger, cloche = rappel marche / arrêt, toucher le titre = objectif et rappel. Une mise
 * en page par famille de formes :
 * - Pastille : compte, + et la jauge dessous ;
 * - Ligne : tout sur une ligne, la jauge au milieu (tient dans la rangée basse de 92 dp) ;
 * - Colonne : les verres empilés de bas en haut ;
 * - Bloc, Grand, Plein écran : les verres debout côte à côte, chacun touchable (le compte va jusqu'à lui) ; Grand et
 *   Plein écran disent aussi l'heure du prochain rappel.
 */
class WaterWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(16).apply { orientation = LinearLayout.VERTICAL }

    private val count = TextView(context).apply {
        compoundDrawableTintList = ColorStateList.valueOf(color(R.attr.c_hue_cyan))
        typeface = themeFont(context, R.attr.c_font_display)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        maxLines = 1
    }
    /** Fenêtre LCD (Instrument), Pastille : « EAU · VERRES » au-dessus du compte (les autres formes ont leur titre). */
    private val caption = lcdCaption().apply { setText(R.string.lcd_water_title) }
    private val unit = label(14f, R.attr.c_text_dim).apply { setText(R.string.water_glasses_label) }
    private val next = label(14f, R.attr.c_text_dim).apply { maxLines = 1 }
    private val bell = iconButton(R.style.IconButton_Flat, R.drawable.ic_alarm_off, 0) {
        Water.setReminder(context, !Water.state(context).reminder)
        Look.pop(it)
    }
    // Teinte posée à la main : un ImageButton créé en code ne lit pas le `tint` du style (icône blanche sur thème clair).
    private val minus = iconButton(R.style.IconButton_Flat, R.drawable.ic_remove, R.string.water_minus) { Water.add(context, -1) }
        .apply { imageTintList = ColorStateList.valueOf(color(R.attr.c_text_dim)) }
    private val plus = iconButton(R.style.IconButton_Primary, R.drawable.ic_add, R.string.water_plus) {
        Water.add(context, 1)
        Look.pop(count)
    }.apply {
        // Thème à boutons au filet (Instrument nuit, `c_btn_edge`) : noir, filet gris, icône orange, sans la teinte pleine.
        if (Color.alpha(color(R.attr.c_btn_edge)) == 0) backgroundTintList = ColorStateList.valueOf(color(R.attr.c_hue_cyan))
        else imageTintList = ColorStateList.valueOf(color(R.attr.c_on_primary))
    }

    /** Les verres : couchés (pastille, ligne), empilés (colonne) ou debout côte à côte (bloc et plus). */
    private val gauge = LinearLayout(context).apply {
        // Les verres ne sont touchables que s'ils font 48 dp de large : la largeur connue, on redessine.
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) post { render() }
        }
    }
    private var drawn: List<Any>? = null

    init {
        // Rangée basse (92 dp) : le titre LCD n'a pas la place, le compte et la jauge d'abord.
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val low = bottom - top < dp(CAPTION_MIN_DP)
            if ((caption.visibility == View.GONE) != low) caption.post { caption.visibility = if (low) View.GONE else View.VISIBLE }
        }
    }
    private val listener: (Water.State) -> Unit = { render() }
    private var preview: WaterSample? = null
    private var bound: WidgetSize? = null

    override fun applySize() {
        if (placed.size == bound) return
        bound = placed.size
        for (v in listOf(count, unit, next, bell, minus, plus, gauge, caption)) (v.parent as? ViewGroup)?.removeView(v)
        view.removeAllViews()
        count.setCompoundDrawablesRelative(null, null, null, null)
        when (family) {
            ShapeFamily.PILL -> pill()
            ShapeFamily.ROW -> row()
            ShapeFamily.COLUMN -> column()
            ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL -> glasses()
        }
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        preview = sample as? WaterSample ?: return
        render()
    }

    override fun setActive(active: Boolean) {
        if (active) Water.observe(listener) else Water.unobserve(listener)
        render()
    }

    /** Relu chaque minute : un nouveau jour remet la jauge à zéro, l'heure du prochain rappel avance. */
    override fun onMinute() = render()

    private fun title() = TitleView(context).apply {
        setText(R.string.water_title)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_water, 0, 0, 0)
        setTextColor(color(R.attr.c_hue_cyan))
        compoundDrawableTintList = ColorStateList.valueOf(color(R.attr.c_hue_cyan))
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { WaterSettings.show(context) }
    }

    /** Une case : « 3 / 8 », cloche et + sur une ligne, la jauge dessous (corriger : réglages). */
    private fun pill() {
        view.setPadding(dp(16), dp(8), dp(16), dp(8))
        view.gravity = Gravity.CENTER_VERTICAL
        countSize(22f)
        count.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_water, 0, 0, 0)
        count.compoundDrawablePadding = dp(8)
        gauge.orientation = LinearLayout.HORIZONTAL
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bell, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        }
        if (lcd) view.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(LCD_CAPTION_DP)))
        view.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(8) })
    }

    /** Une ligne : titre, compte, la jauge qui prend la place restante, cloche, − et + (48 dp de haut en tout). */
    private fun row() {
        view.setPadding(dp(16), dp(8), dp(16), dp(8))
        view.gravity = Gravity.CENTER_VERTICAL
        countSize(20f)
        gauge.orientation = LinearLayout.HORIZONTAL
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title())
            addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
            addView(gauge, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginStart = dp(16); marginEnd = dp(8) })
            addView(bell, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        }
        view.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Colonne : titre et cloche, le compte, les verres empilés de bas en haut, − et + en bas. */
    private fun column() {
        view.setPadding(dp(16), dp(12), dp(16), dp(16))
        view.gravity = Gravity.NO_GRAVITY
        countSize(28f)
        gauge.orientation = LinearLayout.VERTICAL
        view.addView(header(withCount = false))
        view.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        view.addView(unit)
        view.addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        view.addView(buttons(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    /** Bloc et plus grand : les verres debout côte à côte, le prochain rappel (Grand, Plein écran), − et + en bas. */
    private fun glasses() {
        view.setPadding(dp(20), dp(12), dp(20), dp(16))
        view.gravity = Gravity.NO_GRAVITY
        countSize(if (family == ShapeFamily.FULL) 40f else 28f)
        gauge.orientation = LinearLayout.HORIZONTAL
        view.addView(header(withCount = true))
        view.addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        val bottom = buttons()
        if (family != ShapeFamily.BLOCK) bottom.addView(next, 0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun header(withCount: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title(), LinearLayout.LayoutParams(if (withCount) ViewGroup.LayoutParams.WRAP_CONTENT else 0, ViewGroup.LayoutParams.WRAP_CONTENT, if (withCount) 0f else 1f))
        if (withCount) addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        addView(bell, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun buttons() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(plus, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(16) })
    }

    /** Police agrandie (Accessibilité) : le compte ne dépasse pas sa taille à 1,0, celle qui tient sur sa ligne. */
    private fun countSize(sp: Float) {
        count.textSize = sp
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(count.textSize, sp * context.resources.displayMetrics.density))
    }

    private fun iconButton(style: Int, icon: Int, description: Int, onClick: (View) -> Unit) = ImageButton(context, null, 0, style).apply {
        setImageResource(icon)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        if (description != 0) contentDescription = context.getString(description)
        setOnClickListener(onClick)
    }

    private fun render() {
        val state = preview?.state ?: Water.state(context)
        val done = state.glasses >= state.goal
        val wide = family == ShapeFamily.ROW || family == ShapeFamily.BLOCK || family == ShapeFamily.LARGE || family == ShapeFamily.FULL
        // Objectif atteint : dit par le vert du compte et des verres, sans signe dans le texte.
        count.text = context.getString(if (wide) R.string.water_count else R.string.water_count_short, state.glasses, state.goal)
        count.setTextColor(color(if (done) R.attr.c_ok else R.attr.c_text))
        bell.setImageResource(if (state.reminder) R.drawable.ic_alarm else R.drawable.ic_alarm_off)
        bell.imageTintList = ColorStateList.valueOf(color(if (state.reminder) R.attr.c_hue_cyan else R.attr.c_text_dim))
        bell.contentDescription = context.getString(if (state.reminder) R.string.water_bell_on else R.string.water_bell_off)
        val at = if (preview != null) preview?.nextReminderMs else Water.nextReminder(context)
        next.text = at?.let { context.getString(R.string.water_next_reminder, DateFormat.getTimeFormat(context).format(Date(it))) } ?: ""
        drawGauge(state, done)
    }

    private fun drawGauge(state: Water.State, done: Boolean) {
        val stacked = gauge.orientation == LinearLayout.VERTICAL
        val upright = family == ShapeFamily.BLOCK || family == ShapeFamily.LARGE || family == ShapeFamily.FULL
        val gap = dp(if (upright) 8 else if (stacked) 4 else 3)
        val touch = upright && (gauge.width - gap * (state.goal - 1)) / state.goal >= dp(48)
        // Redessiné seulement si quelque chose a changé (render passe aussi chaque minute).
        val key = listOf(state.glasses, state.goal, done, family, touch, stacked)
        if (key == drawn) return
        drawn = key
        gauge.removeAllViews()
        // Empilés, le premier verre est en bas : on dessine du dernier au premier.
        val order = if (stacked) (state.goal - 1 downTo 0) else (0 until state.goal)
        for ((n, i) in order.withIndex()) {
            val filled = i < state.glasses
            val glass = View(context).apply {
                // Vide : un contour (c_graphic, 3:1 sur la tuile), un aplat c_surface_raised ne se voyait pas.
                background = GradientDrawable().apply {
                    if (filled) setColor(color(if (done) R.attr.c_ok else R.attr.c_hue_cyan)) else setStroke(dp(2), color(R.attr.c_graphic))
                    cornerRadius = dp(if (upright) 10 else 6).toFloat()
                }
                if (touch) {
                    // Toucher un verre pose le compte jusqu'à lui ; toucher le dernier rempli le retire.
                    val target = if (i + 1 == state.glasses) i else i + 1
                    contentDescription = context.getString(if (filled) R.string.water_glass_full else R.string.water_glass_empty, i + 1, state.goal)
                    setOnClickListener { Water.set(context, target) }
                } else {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
            val params = if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (n > 0) topMargin = gap }
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (n > 0) marginStart = gap }
            gauge.addView(glass, params)
        }
    }

    private companion object {
        /** Hauteur sous laquelle la Pastille laisse tomber son titre LCD. */
        const val CAPTION_MIN_DP = 120
    }
}
