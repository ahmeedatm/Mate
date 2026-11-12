package app.mate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.audio.Crunch
import java.util.Locale

/**
 * Corbeille de la vue « Ouvertes », posée sur le bas des écrans : invisible au repos, grande pendant un glisser.
 * Contre les accidents, lâcher dessus ne suffit pas : il faut y garder la fenêtre [ARM_MS] (la corbeille se remplit
 * de rouge, puis tremble, vibre et dit « Lâche pour fermer ») ; ressortir avant remet tout à zéro, et un lâcher trop
 * tôt ne fait rien. Fenêtre avalée : petit croc (son généré, [Crunch]) et « Miam ! ».
 * Toujours VISIBLE (seule l'opacité change) : Android ne prévient pas une vue masquée qu'un glisser commence.
 */
class TrashZone(
    context: Context,
    /** Thème Instrument : une longue touche façon dock (socle, capuchon, DEL qui s'allume au survol), libellé en capitales. */
    private val instrument: Boolean = false,
    private val onDrop: (payload: Any?) -> Unit,
) : FrameLayout(context) {

    private val bin = LinearLayout(context)
    private val icon = ImageView(context)
    private val label = TextView(context)
    private val led = View(context)
    private val fill = ClipDrawable(rounded(R.attr.c_ko_soft), Gravity.START, ClipDrawable.HORIZONTAL)
    private val frame = rounded(R.attr.c_surface)
    private var armed = false
    private var wobble: Animator? = null
    /** Retour au repos après un glisser ; annulé si un autre glisser commence avant. */
    private val backToRest = Runnable { show(dragging = false) }
    private val arming = ValueAnimator.ofInt(0, MAX_LEVEL).apply {
        duration = ARM_MS
        addUpdateListener { fill.level = it.animatedValue as Int }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationStart(animation: Animator) { cancelled = false }
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) { if (!cancelled) arm() }
        })
    }

    init {
        bin.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Fond opaque d'abord : posée sur les cartes, une surface en verre (Hush, Spider-Man) les laisserait transparaître.
            background = if (instrument) keyBackground() else LayerDrawable(arrayOf(rounded(R.attr.c_bg), frame, fill))
            alpha = 0f
        }
        icon.setImageResource(R.drawable.ic_delete)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (instrument) {
            // Petite DEL éteinte à gauche du libellé ; l'icône ne sert plus (ses animations restent sans effet).
            led.setBackgroundResource(R.drawable.dock_led)
            led.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            bin.addView(led, LinearLayout.LayoutParams(dp(14), dp(4)).apply { marginEnd = dp(10) })
            label.textSize = 12f
            label.typeface = themeFont(context, R.attr.c_font_label)
            label.letterSpacing = 0.06f
        } else {
            bin.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
            label.textSize = 20f
            label.typeface = Typeface.DEFAULT_BOLD
        }
        bin.addView(label)
        addView(bin, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        reset()
        setOnDragListener { _, event -> onDrag(event) }
    }

    private fun onDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> { removeCallbacks(backToRest); reset(); show(dragging = true) }
            DragEvent.ACTION_DRAG_ENTERED -> {
                label.text = text(R.string.trash_hold)
                tint(R.attr.c_ko)
                icon.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
                arming.start()
            }
            DragEvent.ACTION_DRAG_EXITED -> reset()
            DragEvent.ACTION_DROP -> {
                if (armed) swallow(event.localState) else reset()
            }
            // Après « Miam ! », on laisse voir la corbeille un instant avant de revenir au repos.
            // (Toujours : un glisser annulé pendant que la corbeille tremble la laissait affichée.)
            DragEvent.ACTION_DRAG_ENDED -> postDelayed(backToRest, if (label.text == text(R.string.trash_done)) DONE_MS else 0)
        }
        return true
    }

    private fun arm() {
        armed = true
        label.text = text(R.string.trash_release)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Prête à avaler : la corbeille tremble (pas avec Réduire les animations : le texte suffit).
        if (Look.still(context)) return
        wobble = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, -12f, 12f, -8f, 8f, 0f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun swallow(payload: Any?) {
        stopWobble()
        armed = false
        label.text = text(R.string.trash_done)
        Crunch.play(context)
        icon.animate().scaleX(1.5f).scaleY(1.5f).setDuration(110).withEndAction {
            icon.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
        onDrop(payload)
    }

    private fun show(dragging: Boolean) {
        bin.animate().alpha(if (dragging) 1f else 0f).setDuration(FADE_MS).start()
        if (!dragging) reset()
    }

    private fun reset() {
        arming.cancel()
        stopWobble()
        armed = false
        fill.level = 0
        icon.animate().cancel()
        icon.scaleX = 1f
        icon.scaleY = 1f
        label.text = text(if (instrument) R.string.trash_idle_key else R.string.trash_idle)
        tint(R.attr.c_text_dim)
    }

    private fun stopWobble() {
        wobble?.cancel()
        wobble = null
        icon.rotation = 0f
    }

    private fun tint(colorRes: Int) {
        if (instrument) {
            // Survolée (couleur d'alerte) : la DEL c_signal s'allume et le capuchon prend son contour orange.
            val hovered = colorRes == R.attr.c_ko
            bin.isSelected = hovered
            led.isSelected = hovered
            label.setTextColor(color(if (hovered) R.attr.c_text else R.attr.c_text_dim))
            return
        }
        val c = color(colorRes)
        icon.imageTintList = ColorStateList.valueOf(c)
        label.setTextColor(c)
        frame.setStroke(dp(2), c, dp(10).toFloat(), dp(6).toFloat())
    }

    /** Capitales en Instrument, comme les autres libellés de touches. */
    private fun text(res: Int) = context.getString(res).let { if (instrument) it.uppercase(Locale.FRENCH) else it }

    /** Socle fixe, capuchon par-dessus (4 dp plus haut), et la jauge de rouge qui se remplit pendant l'armement. */
    private fun keyBackground() = LayerDrawable(
        arrayOf(
            ContextCompat.getDrawable(context, R.drawable.dock_key_base)?.mutate(),
            ContextCompat.getDrawable(context, R.drawable.dock_key_cap)?.mutate(),
            fill,
        ),
    ).apply {
        setLayerInset(0, 0, dp(KEY_LIFT_DP), 0, 0)
        setLayerInset(1, 0, 0, 0, dp(KEY_LIFT_DP))
        setLayerInset(2, 0, 0, 0, dp(KEY_LIFT_DP))
    }

    private fun rounded(colorRes: Int) = GradientDrawable().apply {
        setColor(color(colorRes))
        cornerRadius = dp(16).toFloat()
    }

    private fun color(res: Int) = themeColor(context, res)

    private companion object {
        /** Temps à garder la fenêtre sur la corbeille avant que le lâcher la ferme. */
        const val ARM_MS = 900L
        const val MAX_LEVEL = 10_000
        const val FADE_MS = 150L
        const val DONE_MS = 700L
        const val KEY_LIFT_DP = 4
    }
}
