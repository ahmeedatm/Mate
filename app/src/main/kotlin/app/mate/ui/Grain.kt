package app.mate.ui

import android.graphics.Bitmap
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import app.mate.R
import kotlin.random.Random

/**
 * Grain « vintage » de Rétro, Cadran, Cadran nuit et Maison, par-dessus tout l'écran : l'effet Noise de la maquette
 * (deux couleurs, densité 73 %, grain d'un pixel). Une tuile de bruit fixe répétée en avant-plan de la vue : rien n'est
 * recalculé ensuite. Deux couleurs transparentes (`c_grain_*`, les autres thèmes) : rien n'est posé.
 */
object Grain {
    const val TILE = 256
    private const val DENSITY = 0.73f

    /** Au moins une des deux couleurs se voit (alpha non nul). */
    fun active(light: Int, dark: Int) = (light ushr 24) != 0 || (dark ushr 24) != 0

    fun applyTo(root: View) {
        val light = themeColor(root.context, R.attr.c_grain_light)
        val dark = themeColor(root.context, R.attr.c_grain_dark)
        if (!active(light, dark)) return
        root.foregroundGravity = Gravity.FILL
        root.foreground = BitmapDrawable(root.resources, Bitmap.createBitmap(pixels(light, dark), TILE, TILE, Bitmap.Config.ARGB_8888)).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
        }
    }

    /** Pixels de la tuile : toujours les mêmes (graine fixe), `light` ou `dark` à parts égales sur 73 % d'entre eux. */
    fun pixels(light: Int, dark: Int): IntArray {
        val random = Random(42)
        return IntArray(TILE * TILE) {
            if (random.nextFloat() >= DENSITY) 0 else if (random.nextBoolean()) light else dark
        }
    }
}
