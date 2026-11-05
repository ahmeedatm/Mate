package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.net.AudioState
import app.mate.ui.ProgressRing
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.mixer.FaderStrip
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Formes du widget Son tenues par un [AudioLink] : chaque fonction rend la vue (sans carte) et la fonction qui la met à
 * jour quand l'état du son change.
 */
class AudioShapes(private val context: Context, private val link: AudioLink) {
    class Shape(val view: View, val render: (AudioState?) -> Unit)

    private fun color(id: Int) = themeColor(context, id)
    private fun dp(value: Int) = context.dp(value)
    private fun text(state: AudioState?) = when {
        state == null -> "–"
        state.muted -> context.getString(R.string.muted)
        state.output == null -> ""
        else -> context.getString(R.string.percent, state.output)
    }

    /** Pastille : le volume en anneau, le chiffre au centre, le titre à côté ; toucher coupe ou rend le son. */
    fun ring(): Shape {
        val ring = ProgressRing(context).apply { trackColor = color(R.attr.c_stroke) }
        val value = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = themeFont(context, R.attr.c_font_medium)
            setTextColor(color(R.attr.c_text))
        }
        val dial = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(value, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dial, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(TitleView(context).apply { setText(R.string.computer_volume) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            setOnClickListener { link.toggleMute() }
        }
        return Shape(view) { state ->
            ring.progress = (state?.output ?: 0) / AudioLink.MAX.toFloat()
            ring.color = color(if (state?.muted == true) R.attr.c_ko else R.attr.c_accent)
            value.text = text(state)
            view.contentDescription = context.getString(R.string.computer_volume) + ", " + text(state)
        }
    }

    /** Remplissage : la carte se remplit à hauteur du volume ; glisser règle, toucher coupe. */
    fun fill(): Shape {
        val fill = VolumeFill(context).apply {
            onVolume = { volume, final ->
                link.holding = !final
                link.setVolume(volume)
            }
            onMute = { link.toggleMute() }
        }
        return Shape(fill) { state ->
            fill.isEnabled = state?.output != null
            fill.muted = state?.muted == true
            fill.volume = state?.output
        }
    }

    /** Tranche de la table de mixage, la sortie seule. */
    fun fader(): Shape {
        val strip = FaderStrip(context).apply {
            title = context.getString(R.string.mixer_master)
            iconRes = R.drawable.ic_volume
            master = true
            onVolume = { volume, final ->
                link.holding = !final
                link.setVolume(volume)
            }
            onMute = { link.toggleMute() }
        }
        return Shape(strip) { state ->
            strip.volume = state?.output
            strip.muted = state?.muted == true
        }
    }

    /** Pile de boutons : +, le volume, −, puis muet et micro ; ni curseur ni glisser. */
    fun stack(): Shape {
        fun button(icon: Int, label: Int, action: () -> Unit) = ImageButton(context, null, 0, R.style.IconButton).apply {
            setImageResource(icon)
            contentDescription = context.getString(label)
            setOnClickListener { action() }
        }
        val value = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 30f
            typeface = themeFont(context, R.attr.c_font_light)
            setTextColor(color(R.attr.c_text))
        }
        val mute = button(R.drawable.ic_volume, R.string.mute_toggle) { link.toggleMute() }
        val mic = button(R.drawable.ic_mic, R.string.mic_toggle) { link.toggleMic() }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TitleView(context).apply { setText(R.string.computer_volume) })
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            val wrap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            addView(button(R.drawable.ic_add, R.string.volume_up) { link.nudge(true) }, wrap())
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(button(R.drawable.ic_remove, R.string.volume_down) { link.nudge(false) }, wrap())
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(mute)
                addView(mic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
            })
        }
        return Shape(view) { state ->
            value.text = text(state)
            alert(mute, if (state?.muted == true) R.drawable.ic_volume_off else R.drawable.ic_volume, state?.muted == true)
            alert(mic, if (state?.micMuted == true) R.drawable.ic_mic_off else R.drawable.ic_mic, state?.micMuted == true)
        }
    }

    /** Coupé = icône rouge sur fond rouge pâle, comme [app.mate.ui.ComputerAudioView]. */
    private fun alert(button: ImageButton, icon: Int, on: Boolean) {
        button.setImageResource(icon)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color(if (on) R.attr.c_ko else R.attr.c_text)))
        ViewCompat.setBackgroundTintList(button, if (on) ColorStateList.valueOf(color(R.attr.c_ko_soft)) else null)
    }
}
