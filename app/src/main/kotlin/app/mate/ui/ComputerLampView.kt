package app.mate.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import app.mate.AnimFamily
import app.mate.Computer
import app.mate.R
import app.mate.net.Link

/**
 * Dessine le témoin de l'ordinateur ([ComputerLamp]) dans la pastille LCD `chip` (`top_computer.xml`), aux couleurs de
 * l'afficheur (`chip.context`). Connexion en cours : la diode respire (seule elle se redessine) ; état changé : elle fait un
 * petit rebond. Réduire les animations ou Réglages > Animations > alertes coupé : diode fixe.
 */
internal class ComputerLampView(private val chip: View) {
    private val glyph: ImageView = chip.findViewById(R.id.chip_glyph)
    private val slash: ImageView = chip.findViewById(R.id.chip_slash)
    private val dots: LinearLayout = chip.findViewById(R.id.chip_dots)
    private val halo: ImageView = chip.findViewById(R.id.chip_halo)
    private val led: ImageView = chip.findViewById(R.id.chip_led)
    private var breathing: ValueAnimator? = null
    private var shown: ComputerLamp.Look? = null

    init {
        // Activité recréée (thème changé) pendant la connexion : la boucle ne doit pas lui survivre.
        chip.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = breathe(false)
        })
    }

    fun show(link: Link, locked: Boolean, computers: List<Computer>, name: String) {
        val look = ComputerLamp.of(link, locked, computers)
        chip.contentDescription = "$name, ${chip.context.getString(look.spoken)}"
        if (look == shown) return
        val before = shown
        shown = look
        val text = themeColor(chip.context, R.attr.c_text)
        val faint = themeColor(chip.context, R.attr.c_text_faint)
        val color = themeColor(chip.context, look.color)
        glyph.setImageResource(
            when (look.glyph) {
                ComputerLamp.Glyph.MAC -> R.drawable.lamp_os_mac
                ComputerLamp.Glyph.WINDOWS -> R.drawable.lamp_os_windows
                ComputerLamp.Glyph.HOME -> R.drawable.ic_home
                ComputerLamp.Glyph.EMPTY -> R.drawable.lamp_socket
            },
        )
        glyph.imageTintList = ColorStateList.valueOf(if (look.glyph == ComputerLamp.Glyph.EMPTY) faint else text)
        glyph.alpha = if (look.tone == ComputerLamp.Tone.FULL) 1f else DIM_ALPHA
        slash.visibility = if (look.tone == ComputerLamp.Tone.CROSSED) View.VISIBLE else View.GONE
        slash.imageTintList = ColorStateList.valueOf(color)
        led.setImageResource(
            when (look.led) {
                ComputerLamp.Led.ON, ComputerLamp.Led.BREATHING -> R.drawable.lamp_led_on
                ComputerLamp.Led.RING -> R.drawable.lamp_led_ring
                ComputerLamp.Led.ALERT -> R.drawable.lamp_led_alert
                ComputerLamp.Led.LOCK -> R.drawable.ic_lock
            },
        )
        led.imageTintList = ColorStateList.valueOf(color)
        halo.visibility = if (look.led == ComputerLamp.Led.ON) View.VISIBLE else View.GONE
        halo.imageTintList = ColorStateList.valueOf(color)
        showDots(look, text, faint)
        breathe(look.led == ComputerLamp.Led.BREATHING)
        if (before != null && before.led != look.led) Look.blip(led)
    }

    private fun showDots(look: ComputerLamp.Look, active: Int, other: Int) {
        dots.removeAllViews()
        dots.visibility = if (look.dots == 0) View.GONE else View.VISIBLE
        val size = chip.dp(DOT_DP)
        repeat(look.dots) { i ->
            val on = i == look.activeDot
            dots.addView(
                View(chip.context).apply {
                    setBackgroundResource(if (on) R.drawable.lamp_dot else R.drawable.lamp_dot_ring)
                    backgroundTintList = ColorStateList.valueOf(if (on) active else other)
                },
                LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = chip.dp(DOT_GAP_DP) },
            )
        }
    }

    /** Diode qui respire (alpha, sur sa seule vue) pendant la connexion ; fixe à mi-lumière si les animations sont coupées. */
    private fun breathe(on: Boolean) {
        breathing?.cancel()
        breathing = null
        led.alpha = 1f
        if (!on) return
        if (!Look.on(chip.context, AnimFamily.ALERTS)) {
            led.alpha = BREATH_STILL
            return
        }
        breathing = ObjectAnimator.ofFloat(led, View.ALPHA, BREATH_LOW, 1f).apply {
            duration = BREATH_HALF_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private companion object {
        const val DIM_ALPHA = 0.4f
        const val DOT_DP = 4
        const val DOT_GAP_DP = 3
        const val BREATH_LOW = 0.25f
        const val BREATH_STILL = 0.7f
        const val BREATH_HALF_MS = 800L
    }
}
