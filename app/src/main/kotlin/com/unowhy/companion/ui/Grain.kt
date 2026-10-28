package com.unowhy.companion.ui

import android.graphics.Bitmap
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import com.unowhy.companion.R
import kotlin.random.Random

/**
 * Grain « vintage » du thème Rétro, par-dessus tout l'écran : l'effet Noise de la maquette (deux couleurs, densité
 * 73 %, grain d'un pixel). Une tuile de bruit fixe répétée en avant-plan de la vue : rien n'est recalculé ensuite.
 * Sans `c_grain_*` dans le thème, rien n'est posé.
 */
object Grain {
    private const val TILE = 256
    private const val DENSITY = 0.73f

    fun applyTo(root: View) {
        val light = themeColor(root.context, R.attr.c_grain_light)
        val dark = themeColor(root.context, R.attr.c_grain_dark)
        if (light == 0 && dark == 0) return
        root.foregroundGravity = Gravity.FILL
        root.foreground = BitmapDrawable(root.resources, tile(light, dark)).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
        }
    }

    private fun tile(light: Int, dark: Int): Bitmap {
        val random = Random(42)
        val pixels = IntArray(TILE * TILE) {
            if (random.nextFloat() >= DENSITY) 0 else if (random.nextBoolean()) light else dark
        }
        return Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888)
    }
}
