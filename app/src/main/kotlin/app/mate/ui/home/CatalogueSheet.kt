package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeColorList
import app.mate.ui.themeFont
import app.mate.ui.themeRes
import kotlin.math.abs

/**
 * Fiche d'un widget du catalogue, à droite de la liste : l'aperçu à données d'exemple ([WidgetSamples]) dans la forme
 * choisie, flèches et glissement pour changer de forme, points et nom de la forme dessous ; à droite, le texte et
 * « Ajouter le widget ». Un seul aperçu vit à la fois, recréé à chaque forme ; ni [HomeWidget.load] ni
 * [HomeWidget.setActive] : rien ne part sur le réseau. Les formes gardent la même échelle d'un bout à l'autre de la fiche.
 */
@SuppressLint("ViewConstructor")
class CatalogueSheet(context: Context, private val host: HomeHost) : LinearLayout(context) {
    private var type: WidgetType? = null
    private var choices: List<ShapeChoice> = emptyList()
    private var index = 0
    private var cell = 1 to 1
    private var onAdd: ((WidgetType, Fit) -> Unit)? = null

    private val holder = FrameLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    private val noRoom = TextView(context, null, 0, R.style.Chip).apply {
        setText(R.string.catalogue_no_room_here)
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val stage = Stage().apply {
        addView(holder, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        addView(noRoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.CENTER))
    }
    private val prev = arrow(R.drawable.ic_chevron_left, R.string.catalogue_shape_prev) { step(-1) }
    private val next = arrow(R.drawable.ic_chevron_right, R.string.catalogue_shape_next) { step(1) }
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val shapeName = text(16f, R.attr.c_text).apply {
        gravity = Gravity.CENTER
        // TalkBack lit la nouvelle forme après une flèche ou un glissement.
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val dotOn = themeColorList(context, R.attr.c_accent)
    private val dotOff = themeColorList(context, R.attr.c_text_faint)

    private val icon = ImageView(context).apply {
        setBackgroundResource(R.drawable.round_bg)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        imageTintList = themeColorList(context, R.attr.c_accent)
    }
    private val title = text(26f, R.attr.c_text).apply { typeface = themeFont(context, R.attr.c_font_title) }
    private val description = text(15f, R.attr.c_text_dim)
    private val placeDetail = text(14f, R.attr.c_text_dim)
    private val add = Button(context, null, 0, R.style.PrimaryButton).apply {
        maxLines = 2
        setOnClickListener {
            val chosen = type ?: return@setOnClickListener
            choices.getOrNull(index)?.spot?.let { onAdd?.invoke(chosen, it) }
        }
    }

    init {
        orientation = HORIZONTAL
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            // Flèches posées sur la scène, aux bords : l'aperçu prend toute la largeur de la colonne.
            addView(FrameLayout(context).apply {
                addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(prev, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(next, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply { topMargin = dp(12) })
            addView(shapeName, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(icon, LayoutParams(dp(48), dp(48)))
            addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(description, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(placeDetail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(add, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT })
        }, LayoutParams(dp(INFO_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(20) })
        add.minHeight = dp(52)
    }

    /**
     * Montre `type` avec ses formes `choices` (ordre de [SHAPE_ORDER]), ouverte sur `start`. `cellW` × `cellH` : une case
     * de la grille de l'Accueil, en px ; l'aperçu est dessiné à cette taille réelle, puis réduit pour tenir.
     */
    fun show(type: WidgetType, choices: List<ShapeChoice>, start: Int, cellW: Int, cellH: Int, onAdd: (WidgetType, Fit) -> Unit) {
        this.type = type
        this.choices = choices
        this.index = start.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
        this.cell = cellW.coerceAtLeast(1) to cellH.coerceAtLeast(1)
        this.onAdd = onAdd
        icon.setImageResource(type.icon)
        title.setText(type.title)
        description.setText(type.description)
        dots.removeAllViews()
        choices.forEach { _ ->
            dots.addView(View(context).apply { setBackgroundResource(R.drawable.dot) }, LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        render()
    }

    /** Libère l'aperçu (catalogue fermé). */
    fun clear() {
        holder.removeAllViews()
        type = null
        choices = emptyList()
        onAdd = null
    }

    private fun step(delta: Int) {
        val to = index + delta
        if (to !in choices.indices) return
        index = to
        render()
        stage.translationX = dp(SLIDE_DP) * delta.toFloat()
        stage.alpha = 0f
        Look.navSpring(stage, DynamicAnimation.TRANSLATION_X, Look.EFFECTS_DEFAULT, 0f)
        Look.navSpring(stage, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
    }

    private fun render() {
        val type = type ?: return
        val choice = choices.getOrNull(index) ?: return
        val size = choice.size
        renderPreview(type, size, choice.atCase)
        noRoom.visibility = if (choice.atCase) GONE else VISIBLE
        prev.isEnabled = index > 0
        prev.alpha = if (index > 0) 1f else DISABLED_ALPHA
        next.isEnabled = index < choices.size - 1
        next.alpha = if (index < choices.size - 1) 1f else DISABLED_ALPHA
        for (i in 0 until dots.childCount) {
            val on = i == index
            val dot = dots.getChildAt(i)
            val w = dp(if (on) DOT_ON_DP else DOT_DP)
            if (dot.layoutParams.width != w) dot.layoutParams = dot.layoutParams.apply { width = w }
            dot.backgroundTintList = if (on) dotOn else dotOff
        }
        val name = context.getString(R.string.catalogue_shape, size.w, size.h, context.getString(size.family.label))
        shapeName.text = name
        stage.contentDescription = context.getString(R.string.catalogue_shape_cd, name, index + 1, choices.size)
        placeDetail.text = when {
            choice.atCase -> null
            choice.spot != null -> context.getString(R.string.catalogue_no_room_here_detail)
            else -> context.getString(R.string.catalogue_no_room_page)
        }
        placeDetail.visibility = if (choice.atCase) GONE else VISIBLE
        add.setText(if (choice.atCase || choice.spot == null) R.string.catalogue_add else R.string.catalogue_add_free)
        add.isEnabled = choice.spot != null
        add.alpha = if (choice.spot != null) 1f else DISABLED_ALPHA
    }

    /**
     * L'aperçu à sa taille réelle sur l'Accueil, réduit d'une échelle commune à toutes les formes du widget : la plus
     * grande largeur et la plus grande hauteur qu'il accepte tiennent dans la scène. Sans exemple, une carte icône + nom.
     */
    private fun renderPreview(type: WidgetType, size: WidgetSize, fits: Boolean) {
        if (stage.width == 0) return
        val (cellW, cellH) = cell
        val scale = previewScale(choices.map { it.size }, cellW, cellH, stage.width, stage.height)
        val fullW = size.w * cellW
        val fullH = size.h * cellH
        holder.removeAllViews()
        val view = previewOf(type, size)
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale
        holder.addView(view, FrameLayout.LayoutParams(fullW, fullH))
        holder.layoutParams = (holder.layoutParams as FrameLayout.LayoutParams).apply {
            width = (fullW * scale).toInt()
            height = (fullH * scale).toInt()
        }
        holder.alpha = if (fits) 1f else DISABLED_ALPHA
    }

    private fun previewOf(type: WidgetType, size: WidgetSize): View {
        val sample = WidgetSamples.of(type) ?: return placeholder(type)
        val widget = HomeWidget.create(context, host, Placed(type, size, 0, 0, 0), noComputer = false)
        widget.applySize()
        widget.showPreview(sample)
        return widget.view
    }

    /** Widget sans exemple encore (exception de `WidgetRulesTest`) : sa carte, son icône et son nom. */
    private fun placeholder(type: WidgetType) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
        addView(ImageView(context).apply {
            setImageResource(type.icon)
            imageTintList = themeColorList(context, R.attr.c_accent)
        }, LayoutParams(dp(56), dp(56)))
        addView(text(22f, R.attr.c_text).apply {
            setText(type.title)
            gravity = Gravity.CENTER
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun arrow(drawable: Int, label: Int, onClick: () -> Unit) = ImageButton(context, null, 0, R.style.IconButton).apply {
        setImageDrawable(ContextCompat.getDrawable(context, drawable))
        imageTintList = themeColorList(context, R.attr.c_text)
        contentDescription = context.getString(label)
        setOnClickListener { onClick() }
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(themeColor(context, color))
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Scène de l'aperçu : garde les touchers pour elle (l'aperçu n'est pas vivant) et change de forme d'un glissement. */
    private inner class Stage : FrameLayout(context) {
        private var downX = 0f

        init {
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onInterceptTouchEvent(ev: MotionEvent) = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = event.x
                MotionEvent.ACTION_MOVE -> holder.translationX = (event.x - downX) * DRAG_FOLLOW
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.x - downX
                    holder.translationX = 0f
                    if (event.actionMasked == MotionEvent.ACTION_UP && abs(dx) > dp(SWIPE_DP)) step(if (dx < 0) 1 else -1)
                }
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // L'échelle dépend de la place : on redessine après le premier passage de mise en page.
            if (w != oldw || h != oldh) post { render() }
        }
    }

    private companion object {
        const val INFO_DP = 240
        const val DOT_DP = 8
        const val DOT_ON_DP = 20
        const val SWIPE_DP = 48
        const val SLIDE_DP = 40
        const val DRAG_FOLLOW = 0.5f
        const val DISABLED_ALPHA = 0.4f
    }
}
