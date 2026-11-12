package app.mate.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.mate.AnimFamily
import app.mate.Computer
import app.mate.R
import app.mate.net.Link

/**
 * Dessine le témoin de l'ordinateur ([ComputerLamp]) dans la pastille LCD `chip` (`top_computer.xml`), aux couleurs de
 * l'afficheur (`chip.context`). Connexion en cours : la diode respire (seule elle se redessine) ; état changé : elle fait un
 * petit rebond. Réduire les animations ou Réglages > Animations > alertes coupé : diode fixe.
 */
internal class ComputerLampView(private val chip: View, lifecycle: Lifecycle) {
    private val glyph: ImageView = chip.findViewById(R.id.chip_glyph)
    private val slash: ImageView = chip.findViewById(R.id.chip_slash)
    private val dots: LinearLayout = chip.findViewById(R.id.chip_dots)
    private val halo: ImageView = chip.findViewById(R.id.chip_halo)
    private val led: ImageView = chip.findViewById(R.id.chip_led)
    private var breathing: ValueAnimator? = null
    private var shown: ComputerLamp.Look? = null

    init {
        // Écran éteint, appli en arrière-plan ou recréée : la boucle s'arrête, et reprend au retour si on se connecte encore.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = refresh()
            override fun onStop(owner: LifecycleOwner) = breathe(false)
        })
    }

    /** Réglages des animations changés, ou écran de retour : la diode respire ou non selon l'état affiché. */
    fun refresh() = breathe(shown?.led == ComputerLamp.Led.BREATHING)

    fun show(link: Link, locked: Boolean, computers: List<Computer>, name: String) {
        val look = ComputerLamp.of(link, locked, computers)
        chip.contentDescription = "$name, ${chip.context.getString(look.spoken)}"
        if (look == shown) return
        val before = shown
        shown = look
        val text = themeColor(chip.context, R.attr.c_text)
        val color = themeColor(chip.context, look.color)
        glyph.setImageResource(
            when (look.glyph) {
                ComputerLamp.Glyph.MAC -> R.drawable.lamp_os_mac
                ComputerLamp.Glyph.WINDOWS -> R.drawable.lamp_os_windows
                ComputerLamp.Glyph.HOME -> R.drawable.ic_home
                ComputerLamp.Glyph.EMPTY -> R.drawable.lamp_socket
            },
        )
        // Le socle vide porte ses couleurs (pointillé c_text_faint) : pas de teinte.
        glyph.imageTintList = if (look.glyph == ComputerLamp.Glyph.EMPTY) null else ColorStateList.valueOf(text)
        glyph.alpha = if (look.tone == ComputerLamp.Tone.FULL) 1f else DIM_ALPHA
        slash.visibility = if (look.tone == ComputerLamp.Tone.CROSSED) View.VISIBLE else View.GONE
        slash.imageTintList = ColorStateList.valueOf(color)
        led.setImageResource(
            when (look.led) {
                ComputerLamp.Led.ON -> R.drawable.lamp_led_on
                ComputerLamp.Led.BREATHING -> R.drawable.lamp_led_half
                ComputerLamp.Led.RING -> R.drawable.lamp_led_ring
                ComputerLamp.Led.ALERT -> R.drawable.lamp_led_alert
                ComputerLamp.Led.LOCK -> R.drawable.ic_lock
            },
        )
        led.imageTintList = ColorStateList.valueOf(color)
        halo.visibility = if (look.led == ComputerLamp.Led.ON) View.VISIBLE else View.GONE
        halo.imageTintList = ColorStateList.valueOf(color)
        showDots(look)
        breathe(look.led == ComputerLamp.Led.BREATHING)
        if (before != null && before.led != look.led) Look.blip(led)
    }

    /** Un point par ordinateur (plein c_text pour l'actif, creux c_text_faint), couleurs portées par les dessins. */
    private fun showDots(look: ComputerLamp.Look) {
        dots.removeAllViews()
        dots.visibility = if (look.dots == 0) View.GONE else View.VISIBLE
        val size = chip.dp(DOT_DP)
        repeat(look.dots) { i ->
            dots.addView(
                View(chip.context).apply { setBackgroundResource(if (i == look.activeDot) R.drawable.lamp_dot else R.drawable.lamp_dot_ring) },
                LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = chip.dp(DOT_GAP_DP) },
            )
        }
    }

    /** Diode qui respire (alpha, sur sa seule vue) pendant la connexion ; fixe si les animations sont coupées (sa forme à demi pleine suffit). */
    private fun breathe(on: Boolean) {
        breathing?.cancel()
        breathing = null
        led.alpha = 1f
        if (!on) return
        if (!Look.on(chip.context, AnimFamily.ALERTS)) {
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
        const val DIM_ALPHA = 0.5f
        const val DOT_DP = 6
        const val DOT_GAP_DP = 3
        const val BREATH_LOW = 0.25f
        const val BREATH_HALF_MS = 800L
    }
}
