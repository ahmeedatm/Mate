package app.mate.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.HaEntity
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.showImmersive
import app.mate.ui.themeColor

/**
 * Panneau d'une lumière Home Assistant, ouvert par un appui long dans le widget Maison (maquette A « Tiroir ») : une
 * carte posée en bas de l'écran, jauge de luminosité à gauche, couleurs à droite (seulement si la lumière en prend) et
 * un bouton qui l'allume ou l'éteint. La luminosité part au lâcher de la jauge, la couleur au toucher d'une pastille.
 */
class HaLightPanel(
    private val context: Context,
    private val entity: HaEntity,
    /** Règle la lumière : luminosité en %, couleur 0xRRGGBB (null : inchangée). */
    private val onSet: (brightness: Int?, rgb: Int?) -> Unit,
    private val onToggle: () -> Unit,
) {
    private val light = requireNotNull(entity.light)
    private var rgb: Int? = light.rgb
    private val swatches = mutableListOf<Pair<View, Int>>()

    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.view_ha_light, null)
        val dialog = Dialog(context)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { y = context.dp(BOTTOM_DP) }
        }
        root.findViewById<TextView>(R.id.ha_light_name).text = entity.name
        root.findViewById<TextView>(R.id.ha_light_hint).setText(if (light.color) R.string.ha_light_hint else R.string.ha_light_hint_dim)
        val fader = LightFader(context).apply {
            label = entity.name
            value = if (entity.on) light.brightness ?: FULL else 0
            fill = tint()
            onChange = { value, final -> if (final) onSet(value, null) }
        }
        root.findViewById<FrameLayout>(R.id.ha_light_fader).addView(fader, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val colors = root.findViewById<GridLayout>(R.id.ha_light_colors)
        if (light.color) {
            val names = context.resources.getStringArray(R.array.ha_light_colors)
            COLORS.forEachIndexed { i, color -> colors.addView(swatch(color, names[i], fader), swatchParams()) }
        } else {
            colors.visibility = View.GONE
        }
        root.findViewById<Button>(R.id.ha_light_power).apply {
            setText(if (entity.on) R.string.ha_light_turn_off else R.string.ha_light_turn_on)
            setOnClickListener {
                onToggle()
                dialog.dismiss()
            }
        }
        dialog.showImmersive()
    }

    private fun tint() = rgb?.let { OPAQUE or it } ?: themeColor(context, R.attr.c_accent)

    private fun swatch(color: Int, name: String, fader: LightFader) = View(context).apply {
        contentDescription = name
        setOnClickListener {
            rgb = color
            fader.fill = tint()
            if (fader.value == 0) fader.value = FULL
            mark()
            onSet(null, color)
        }
        Look.pressSpring(this)
        swatches += this to color
        mark()
    }

    /** Pastille choisie : cerclée d'encre ; les autres, d'un trait fin. */
    private fun mark() = swatches.forEach { (view, color) ->
        val chosen = color == rgb
        view.isSelected = chosen
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(OPAQUE or color)
            setStroke(context.dp(if (chosen) RING_ON_DP else RING_DP), themeColor(context, if (chosen) R.attr.c_text else R.attr.c_stroke))
        }
    }

    private fun swatchParams() = GridLayout.LayoutParams().apply {
        width = context.dp(SWATCH_DP)
        height = context.dp(SWATCH_DP)
        setMargins(0, 0, context.dp(SWATCH_GAP_DP), context.dp(SWATCH_GAP_DP))
    }

    companion object {
        /** Couleurs proposées, en 0xRRGGBB (noms dans `ha_light_colors`) : des teintes de lampe, pas du thème. */
        val COLORS = listOf(0xFFB45A, 0xFF5A14, 0xFF2D55, 0xC04CFF, 0x5078FF, 0x00C8FF, 0x00FF8C, 0xFFF1D6)

        private const val OPAQUE = -0x1000000
        private const val FULL = 100
        private const val BOTTOM_DP = 24
        private const val SWATCH_DP = 56
        private const val SWATCH_GAP_DP = 16
        private const val RING_DP = 1
        private const val RING_ON_DP = 4
    }
}
