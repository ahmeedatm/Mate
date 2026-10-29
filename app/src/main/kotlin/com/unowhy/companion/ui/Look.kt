package com.unowhy.companion.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import kotlin.math.abs

/**
 * Ce qui donne de la vie à l'interface : une teinte par fonction (pastilles rondes des titres, tuiles d'applis)
 * et des animations courtes. La tablette est lente : animations de propriétés seulement (alpha, translation, échelle),
 * sur un calque matériel, et les boucles s'arrêtent dès que l'écran n'est plus affiché.
 */
object Look {
    /** Rétro : halo crème autour d'un texte dessiné à la main, pour le lire sur les tubes qui coulent derrière. */
    fun halo(context: Context, paint: Paint) {
        if (themeBool(context, R.attr.c_flow)) paint.setShadowLayer(HALO_RADIUS, 0f, 0f, themeColor(context, R.attr.c_surface))
    }

    /**
     * Rétro : halo crème autour des textes foncés sous [root] (Accueil, tubes derrière) ; jamais autour d'un texte
     * clair, posé sur un fond coloré (tuile, bouton plein), qu'il brouillerait.
     */
    fun haloTexts(root: View) {
        if (root is TextView) {
            val dark = ColorUtils.calculateLuminance(root.currentTextColor) < DARK_TEXT
            val halo = if (dark) themeColor(root.context, R.attr.c_surface) else 0
            if (root.shadowColor != halo) root.setShadowLayer(if (dark) HALO_RADIUS else 0f, 0f, 0f, halo)
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) haloTexts(root.getChildAt(i))
    }

    private const val HALO_RADIUS = 8f
    private const val DARK_TEXT = 0.3

    private val HUES = intArrayOf(
        R.attr.c_hue_orange, R.attr.c_hue_green, R.attr.c_hue_cyan, R.attr.c_hue_violet,
        R.attr.c_hue_red, R.attr.c_hue_pink, R.attr.c_hue_yellow, R.attr.c_hue_blue,
    )

    /** Teinte de la fonction que représente l'icône d'un titre. */
    fun iconHue(icon: Int): Int = when (icon) {
        R.drawable.ic_event, R.drawable.ic_alarm -> R.attr.c_hue_orange
        R.drawable.ic_tasks, R.drawable.ic_repeat, R.drawable.ic_tablet -> R.attr.c_hue_green
        R.drawable.ic_water, R.drawable.ic_refresh -> R.attr.c_hue_cyan
        R.drawable.ic_volume, R.drawable.ic_display -> R.attr.c_hue_violet
        R.drawable.ic_timer, R.drawable.ic_lock -> R.attr.c_hue_red
        R.drawable.ic_play, R.drawable.ic_videocam, R.drawable.ic_palette -> R.attr.c_hue_pink
        R.drawable.ic_edit -> R.attr.c_hue_yellow
        else -> R.attr.c_hue_blue
    }

    /** Couleurs connues des applis courantes ; les autres noms tirent une teinte stable. */
    private val BRANDS = listOf(
        "teams" to R.attr.c_hue_violet, "discord" to R.attr.c_hue_violet, "code" to R.attr.c_hue_blue,
        "outlook" to R.attr.c_hue_blue, "word" to R.attr.c_hue_blue, "steam" to R.attr.c_hue_blue,
        "whatsapp" to R.attr.c_hue_green, "spotify" to R.attr.c_hue_green, "excel" to R.attr.c_hue_green, "dofus" to R.attr.c_hue_green,
        "youtube" to R.attr.c_hue_red, "netflix" to R.attr.c_hue_red, "valorant" to R.attr.c_hue_red, "micro" to R.attr.c_hue_red,
        "chrome" to R.attr.c_hue_yellow, "firefox" to R.attr.c_hue_orange, "powerpoint" to R.attr.c_hue_orange,
        "edge" to R.attr.c_hue_cyan, "slack" to R.attr.c_hue_pink, "deezer" to R.attr.c_hue_pink,
    )

    /** Teinte d'un nom (tuiles d'applis et de touches sans icône) : couleur de la marque, sinon stable d'après le nom. */
    fun nameHue(name: String): Int {
        val lower = name.lowercase()
        return BRANDS.firstOrNull { lower.contains(it.first) }?.second ?: HUES[Math.floorMod(lower.hashCode(), HUES.size)]
    }

    /** Icône teintée dans une pastille ronde de sa couleur (filtre de couleur : le teintage de TextView ne l'écrase pas). */
    fun badge(context: Context, icon: Int, color: Int, sizeDp: Int = 30, iconDp: Int = 18): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val inset = ((sizeDp - iconDp) * density / 2).toInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(color, 0x38))
        }
        val glyph = ContextCompat.getDrawable(context, icon)!!.mutate().apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
    }

    /** Initiale d'une tuile : lettre de la teinte du nom sur un rond de la même teinte. */
    fun tintInitial(view: TextView, name: String) {
        val color = themeColor(view.context, nameHue(name))
        view.setTextColor(color)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(color, 0x30))
        }
    }

    /**
     * Texte lisible sur un fond imposé (tuile colorée de deck.json) : le texte du thème ou le fond du thème,
     * celui qui contraste le plus. Ex. Verrouiller (ardoise) en thème clair : texte clair.
     */
    fun readableOn(context: Context, fill: Int): Int {
        val text = themeColor(context, R.attr.c_text)
        val bg = themeColor(context, R.attr.c_bg)
        val opaque = ColorUtils.setAlphaComponent(fill, 0xFF)
        return if (ColorUtils.calculateContrast(text, opaque) >= ColorUtils.calculateContrast(bg, opaque)) text else bg
    }

    /** Initiale neutre (emplacement vide « + », emoji choisi) : sans rond. */
    fun plainInitial(view: TextView, colorAttr: Int) {
        view.setTextColor(themeColor(view.context, colorAttr))
        view.background = null
    }

    /** Arrivée à l'écran : montée et fondu, décalés d'un élément à l'autre. */
    fun enter(view: View, index: Int) {
        if (reduced(view.context)) {
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        val shift = 18 * view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = shift
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(index * 45L).setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f)).withLayer().start()
    }

    /** Petit rebond (sélection, ajout). */
    fun pop(view: View) {
        view.animate().cancel()
        if (reduced(view.context)) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** Flottement lent, en boucle : à arrêter avec [stop]. Réduire les animations : immobile (pas lancé). */
    fun float(view: View): ValueAnimator {
        val amplitude = 4 * view.resources.displayMetrics.density
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -amplitude, amplitude).apply {
            duration = 2400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            if (!still(view.context)) start()
        }
    }

    /** Pulsation douce, en boucle (chrono en marche). Le parent ne rogne plus l'élément agrandi. Réduire les animations : pas lancée. */
    fun pulse(view: View): ValueAnimator {
        var parent = view.parent as? android.view.ViewGroup
        repeat(2) {
            parent?.clipChildren = false
            parent?.clipToPadding = false
            parent = parent?.parent as? android.view.ViewGroup
        }
        return ValueAnimator.ofFloat(1f, 1.07f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.scaleX = it.animatedValue as Float; view.scaleY = view.scaleX }
            if (!still(view.context)) start()
        }
    }

    /** Ressort M3 (schéma standard, docs/ui-ux/da) : raideur et amortissement. */
    class Spring(val stiffness: Float, val damping: Float)

    val SPATIAL_FAST = Spring(1400f, 0.9f)
    val SPATIAL_DEFAULT = Spring(700f, 0.9f)
    val SPATIAL_SLOW = Spring(300f, 0.9f)
    val EFFECTS_FAST = Spring(3800f, 1f)
    val EFFECTS_DEFAULT = Spring(1600f, 1f)
    val EFFECTS_SLOW = Spring(800f, 1f)

    /**
     * Réduire les animations (Réglages > Accessibilité) ou échelle d'animation d'Android à zéro (kiosque, réglage
     * développeur, tests instrumentés) : états finaux posés sans animer. Les ressorts ne suivent pas cette échelle d'eux-mêmes.
     */
    fun reduced(context: Context): Boolean = Motion.transitions(
        Prefs.reduceMotion(context),
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )

    /**
     * Réduire les animations, demandé dans Réglages > Accessibilité : plus rien ne bouge en continu, et même les ressorts
     * joués malgré l'échelle à zéro se posent. Pas l'échelle d'Android : le kiosque la met à zéro pour la vitesse (voir Motion).
     */
    fun still(context: Context): Boolean = Motion.loops(Prefs.reduceMotion(context))

    private fun force(token: Spring, to: Float) = SpringForce(to).setStiffness(token.stiffness).setDampingRatio(token.damping)

    private fun spatialFast(view: View, property: DynamicAnimation.ViewProperty) =
        SpringAnimation(view, property).setSpring(force(SPATIAL_FAST, 1f))

    /**
     * Ressort sur une valeur seule : `onUpdate` reçoit la valeur de `from` vers `to`, `onEnd` quand elle est posée
     * (pas si on l'annule). À annuler avec `cancel()` quand la vue part. Échelle d'animation à zéro : `to` tout de suite, null,
     * sauf `always` (joue quand même : voir [slideIn]) ; Réduire les animations : `to` tout de suite, même avec `always`.
     */
    fun spring(
        context: Context, token: Spring, from: Float, to: Float, onUpdate: (Float) -> Unit, onEnd: (() -> Unit)? = null, always: Boolean = false,
    ): SpringAnimation? {
        if (from == to || (!always && reduced(context)) || still(context)) {
            onUpdate(to)
            onEnd?.invoke()
            return null
        }
        return SpringAnimation(FloatValueHolder(from))
            .setSpring(force(token, to))
            .setMinimumVisibleChange(maxOf(abs(to - from) * 0.002f, 0.0005f))
            .addUpdateListener { _, value, _ -> onUpdate(value) }
            .addEndListener { _, canceled, _, _ -> if (!canceled) onEnd?.invoke() }
            .also { it.start() }
    }

    /**
     * Ressort sur une propriété de vue, lancé à `velocity` (px/s : celle du doigt qui lâche). `onEnd` quand elle est posée
     * (pas si on l'annule). Échelle d'animation à zéro : valeur posée tout de suite, null.
     */
    fun springTo(
        view: View, property: DynamicAnimation.ViewProperty, token: Spring, to: Float, velocity: Float = 0f, onEnd: (() -> Unit)? = null,
    ): SpringAnimation? {
        if (reduced(view.context)) {
            property.setValue(view, to)
            onEnd?.invoke()
            return null
        }
        return SpringAnimation(view, property).setSpring(force(token, to)).setStartVelocity(velocity)
            .addEndListener { _, canceled, _, _ -> if (!canceled) onEnd?.invoke() }
            .also { it.start() }
    }

    /** Arrivée d'un élément de l'Accueil Cadran : il monte de `dy` dp (ressort par défaut) en fondu, après `delayMs`. */
    fun enterSpring(view: View, delayMs: Long, dy: Int) {
        view.animate().cancel()
        if (reduced(view.context)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = dy * view.resources.displayMetrics.density
        view.postDelayed({
            SpringAnimation(view, DynamicAnimation.TRANSLATION_Y).setSpring(force(SPATIAL_DEFAULT, 0f)).start()
            SpringAnimation(view, DynamicAnimation.ALPHA).setSpring(force(EFFECTS_DEFAULT, 1f)).start()
        }, delayMs)
    }

    /**
     * Changement d'onglet (axe partagé M3, docs/ui-ux/specs-ds.md) : les vues visibles entrent de 30 dp du côté
     * `direction` (1 droite, -1 gauche) en fondu, sur un calque matériel. Rend les ressorts, à annuler avec [settle].
     * Joue même échelle d'animation à zéro : le kiosque la met à zéro pour la vitesse (docs/kiosque.md), et ces ressorts
     * (Android 10 : ils ne lisent pas l'échelle) ne touchent que des propriétés de vue sur calque matériel.
     * Réduire les animations : rien ne glisse.
     */
    fun slideIn(views: List<View>, direction: Int): List<SpringAnimation> {
        if (direction == 0 || views.isEmpty() || still(views.first().context)) return emptyList()
        val shift = direction * SHARED_AXIS_DP * views.first().resources.displayMetrics.density
        return views.filter { it.visibility == View.VISIBLE }.flatMap { view ->
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.translationX = shift
            view.alpha = 0f
            listOf(
                SpringAnimation(view, DynamicAnimation.TRANSLATION_X).setSpring(force(SPATIAL_DEFAULT, 0f))
                    .addEndListener { _, _, _, _ -> view.setLayerType(View.LAYER_TYPE_NONE, null) },
                SpringAnimation(view, DynamicAnimation.ALPHA).setSpring(force(EFFECTS_DEFAULT, 1f)),
            )
        }.onEach { it.start() }
    }

    /** Arrête un [slideIn] en cours et remet les vues en place. */
    fun settle(springs: List<SpringAnimation>, views: List<View>) {
        springs.forEach { it.cancel() }
        views.forEach {
            it.translationX = 0f
            it.alpha = 1f
            it.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private const val SHARED_AXIS_DP = 30

    /** Appui (DA Cadran) : la vue s'enfonce à 0,96 puis revient sur un ressort. Le clic n'est pas consommé. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressSpring(view: View) {
        if (still(view.context)) return
        val x = spatialFast(view, DynamicAnimation.SCALE_X)
        val y = spatialFast(view, DynamicAnimation.SCALE_Y)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0.96f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                else -> null
            }?.let { x.animateToFinalPosition(it); y.animateToFinalPosition(it) }
            false
        }
    }

    /**
     * Appui piloté de l'extérieur, pour une vue qui ne reçoit pas elle-même le DOWN et l'UP (case d'une liste dont le
     * clic passe par la liste) : `true` l'enfonce à 0,96, `false` la rend. [pressSpring] pour une vue cliquable.
     */
    fun pressTo(view: View, pressed: Boolean) {
        if (still(view.context)) return
        val (x, y) = springsOf(view) ?: (spatialFast(view, DynamicAnimation.SCALE_X) to spatialFast(view, DynamicAnimation.SCALE_Y))
            .also { view.setTag(R.id.press_springs, it) }
        val to = if (pressed) 0.96f else 1f
        x.animateToFinalPosition(to)
        y.animateToFinalPosition(to)
    }

    @Suppress("UNCHECKED_CAST")
    private fun springsOf(view: View) = view.getTag(R.id.press_springs) as? Pair<SpringAnimation, SpringAnimation>

    /** Vue réutilisée pour une autre case : plus d'appui en cours, taille normale. */
    fun pressReset(view: View) {
        springsOf(view)?.let { (x, y) -> x.cancel(); y.cancel() }
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /** Change l'icône : la nouvelle arrive en grandissant (0,5 → 1) sur un ressort, en fondu. Même icône : rien. */
    fun swapIcon(icon: ImageView, res: Int) {
        if (icon.tag == res) return
        val first = icon.tag == null
        icon.tag = res
        icon.setImageResource(res)
        if (first || still(icon.context)) return
        icon.scaleX = 0.5f
        icon.scaleY = 0.5f
        icon.alpha = 0f
        spatialFast(icon, DynamicAnimation.SCALE_X).animateToFinalPosition(1f)
        spatialFast(icon, DynamicAnimation.SCALE_Y).animateToFinalPosition(1f)
        icon.animate().alpha(1f).setDuration(120).start()
    }

    fun stop(animator: ValueAnimator?, view: View) {
        animator?.cancel()
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}

/**
 * Titre de carte (style CardTitle) : son icône (`android:drawableStart`) devient une pastille de la teinte de la fonction.
 * Dans le code : `TitleView(context)` puis [setIcon]. En Cadran : texte simple, sans icône ni majuscules.
 */
class TitleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = R.style.CardTitle,
) : TextView(context, attrs, defStyleAttr, defStyleRes) {
    private val cadran = themeBool(context, R.attr.c_cadran)

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.drawableStart), defStyleAttr, defStyleRes)
        val icon = a.getResourceId(0, 0)
        a.recycle()
        // TalkBack : titre de section, atteint par la navigation de titre en titre.
        ViewCompat.setAccessibilityHeading(this, true)
        if (cadran) {
            setCompoundDrawablesRelative(null, null, null, null)
            isAllCaps = false
            letterSpacing = Accessibility.letterSpacing(context)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, themePx(context, R.attr.c_title_size).toFloat())
            setTextColor(themeColor(context, R.attr.c_text_dim))
        } else if (icon != 0) {
            setIcon(icon)
        }
    }

    fun setIcon(icon: Int, hue: Int = Look.iconHue(icon)) {
        if (cadran) return
        setCompoundDrawablesRelative(Look.badge(context, icon, themeColor(context, hue)), null, null, null)
    }
}
