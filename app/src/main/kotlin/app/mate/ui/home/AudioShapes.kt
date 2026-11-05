package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.AudioState
import app.mate.ui.ProgressRing
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Formes du widget Son tenues par un [AudioLink] (Pastille, Colonne) : chaque fonction rend la vue (sans carte) et la
 * fonction qui la met à jour quand l'état du son change.
 */
class AudioShapes(private val context: Context, private val link: AudioLink) {
    class Shape(val view: View, val render: (AudioState?) -> Unit)

    private fun color(id: Int) = themeColor(context, id)
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
        val fill = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val dial = FrameLayout(context).apply {
            addView(ring, fill())
            addView(value, fill())
        }
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dial, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(
                TitleView(context).apply { setText(R.string.computer_volume) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) },
            )
            setOnClickListener { link.toggleMute() }
        }
        return Shape(view) { state ->
            ring.progress = (state?.output ?: 0) / AudioLink.MAX.toFloat()
            ring.color = color(if (state?.muted == true) R.attr.c_ko else R.attr.c_accent)
            value.text = text(state)
            view.isEnabled = state != null
            view.contentDescription = context.getString(R.string.computer_volume) + ", " + text(state)
        }
    }

    /** Colonne : la carte se remplit à hauteur du volume ; glisser règle, toucher coupe. */
    fun fill(): Shape {
        val fill = VolumeFill(context).apply {
            onVolume = { volume, final ->
                link.holding = !final
                link.setVolume(volume)
            }
            onMute = { link.toggleMute() }
        }
        return Shape(fill) { state ->
            // Sans volume réglable, le toucher coupe encore le son (le glisser est ignoré par la vue).
            fill.isEnabled = state != null
            fill.muted = state?.muted == true
            fill.volume = state?.output
        }
    }
}
