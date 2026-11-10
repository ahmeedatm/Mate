package app.mate.ui

import android.graphics.Bitmap
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import app.mate.Prefs
import app.mate.R
import kotlin.random.Random

/**
 * Grain « vintage » par-dessus tout l'écran, sur tous les thèmes quand Réglages > Thème > Grain est allumé (Prefs.grain,
 * éteint par défaut) : l'effet Noise de la maquette (deux couleurs, densité 73 %, grain d'un pixel). Chaque palette a ses
 * deux couleurs (`c_grain_*`), transparentes dans Contraste élevé (lisibilité d'abord : jamais de grain). Une tuile de bruit fixe répétée en avant-plan de la vue : rien n'est recalculé ensuite.
 * Posé après ThemePattern, il s'ajoute au motif du thème au lieu de l'effacer.
 */
object Grain {
    const val TILE = 256
    private const val DENSITY = 0.73f

    /** Au moins une des deux couleurs se voit (alpha non nul). */
    fun active(light: Int, dark: Int) = (light ushr 24) != 0 || (dark ushr 24) != 0

    /** Le réglage est allumé et le thème a un grain visible. */
    fun shown(setting: Boolean, light: Int, dark: Int) = setting && active(light, dark)

    fun applyTo(root: View) {
        val light = themeColor(root.context, R.attr.c_grain_light)
        val dark = themeColor(root.context, R.attr.c_grain_dark)
        if (!shown(Prefs.grain(root.context), light, dark)) return
        val grain = BitmapDrawable(root.resources, Bitmap.createBitmap(pixels(light, dark), TILE, TILE, Bitmap.Config.ARGB_8888)).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
        }
        root.foregroundGravity = Gravity.FILL
        root.foreground = root.foreground?.let { LayerDrawable(arrayOf(it, grain)) } ?: grain
    }

    /** Pixels de la tuile : toujours les mêmes (graine fixe), `light` ou `dark` à parts égales sur 73 % d'entre eux. */
    fun pixels(light: Int, dark: Int): IntArray {
        val random = Random(42)
        return IntArray(TILE * TILE) {
            if (random.nextFloat() >= DENSITY) 0 else if (random.nextBoolean()) light else dark
        }
    }
}
