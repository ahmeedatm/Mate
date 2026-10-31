package app.mate.ui

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import app.mate.AnimFamily
import app.mate.R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * L'écran suit le doigt pendant le balayage vertical entre les entrées de la barre (Accueil : WidgetGrid ; autres
 * écrans : RailSwipeLayout) : il glisse d'une partie du geste et s'estompe un peu ; au bout de la barre, il résiste
 * (élastique plafonné). Lâché sans changer d'écran : il revient en place sur un ressort ; changé : il est remis en place
 * d'un coup, le nouvel écran arrive par Look.sharedAxis. Seulement translation et opacité, calque matériel le temps du
 * geste : rien n'est remesuré (tablette 4.2).
 */
object SwipeFollow {

    /** Part du geste suivie quand un écran attend dans ce sens. */
    private const val FOLLOW = 0.4f

    /** Course la plus grande au bout de la barre (dp) : l'écran ne fait que céder un peu. */
    private const val EDGE_MAX_DP = 40

    /** Course (dp) à laquelle l'écran est le plus estompé. */
    private const val FADE_DP = 160

    private const val MIN_ALPHA = 0.7f

    /** Déplacement affiché pour `dy` du doigt (px) : `edgeMax` = course plafond au bout de la barre (px). */
    fun offset(dy: Float, canGo: Boolean, edgeMax: Float): Float =
        if (canGo) dy * FOLLOW else sign(dy) * edgeMax * (1f - exp(-abs(dy) / (edgeMax * 4f)))

    /** Opacité pour un déplacement affiché `offset` (px), `fade` = course de l'estompe la plus forte (px). */
    fun alpha(offset: Float, fade: Float): Float = 1f - (1f - MIN_ALPHA) * (abs(offset) / fade).coerceAtMost(1f)

    /** Doigt à `dy` du départ (px) ; `canGo` : un écran attend dans ce sens. */
    fun drag(view: View, dy: Float, canGo: Boolean) {
        if (!Look.on(view.context, AnimFamily.NAVIGATION)) return
        // Un retour en place encore en cours (geste repris tout de suite) laisse la main au doigt.
        stop(view)
        if (view.layerType != View.LAYER_TYPE_HARDWARE) view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val shown = offset(dy, canGo, view.dp(EDGE_MAX_DP).toFloat())
        view.translationY = shown
        view.alpha = alpha(shown, view.dp(FADE_DP).toFloat())
    }

    /** Lâché sans changer d'écran (trop court, ou bout de la barre) : retour en place sur un ressort. */
    fun release(view: View) {
        if (view.translationY == 0f && view.alpha == 1f) return
        Look.navSpring(view, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        Look.navSpring(view, DynamicAnimation.TRANSLATION_Y, Look.SPATIAL_FAST, 0f) { view.setLayerType(View.LAYER_TYPE_NONE, null) }
    }

    /** L'écran change : celui-ci est remis en place tout de suite (il est masqué ou recouvert par le nouveau). */
    fun reset(view: View) {
        stop(view)
        view.translationY = 0f
        view.alpha = 1f
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun stop(view: View) = (view.getTag(R.id.nav_springs) as? Map<*, *>)?.values?.forEach { (it as? SpringAnimation)?.cancel() }
}
