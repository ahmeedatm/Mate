package com.unowhy.companion.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.unowhy.companion.R

/**
 * Ce qui donne de la vie à l'interface : une teinte par fonction (pastilles rondes des titres, tuiles d'applis)
 * et des animations courtes. La tablette est lente : animations de propriétés seulement (alpha, translation, échelle),
 * sur un calque matériel, et les boucles s'arrêtent dès que l'écran n'est plus affiché.
 */
object Look {
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

    /** Initiale neutre (emplacement vide « + », emoji choisi) : sans rond. */
    fun plainInitial(view: TextView, colorAttr: Int) {
        view.setTextColor(themeColor(view.context, colorAttr))
        view.background = null
    }

    /** Arrivée à l'écran : montée et fondu, décalés d'un élément à l'autre. */
    fun enter(view: View, index: Int) {
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
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** Flottement lent, en boucle : à arrêter avec [stop]. */
    fun float(view: View): ValueAnimator {
        val amplitude = 4 * view.resources.displayMetrics.density
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -amplitude, amplitude).apply {
            duration = 2400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** Pulsation douce, en boucle (chrono en marche). Le parent ne rogne plus l'élément agrandi. */
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
            start()
        }
    }

    /** Ressort M3 « spatial fast » (schéma standard : amortissement 0,9, raideur 1400), voir docs/ui-ux/da. */
    private fun spatialFast(view: View, property: DynamicAnimation.ViewProperty) =
        SpringAnimation(view, property).setSpring(SpringForce(1f).setStiffness(1400f).setDampingRatio(0.9f))

    /** Appui (DA Cadran) : la vue s'enfonce à 0,96 puis revient sur un ressort. Le clic n'est pas consommé. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressSpring(view: View) {
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

    /** Change l'icône : la nouvelle arrive en grandissant (0,5 → 1) sur un ressort, en fondu. Même icône : rien. */
    fun swapIcon(icon: ImageView, res: Int) {
        if (icon.tag == res) return
        val first = icon.tag == null
        icon.tag = res
        icon.setImageResource(res)
        if (first) return
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
        if (cadran) {
            setCompoundDrawablesRelative(null, null, null, null)
            isAllCaps = false
            letterSpacing = 0f
            textSize = 15f
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
