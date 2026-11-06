package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import app.mate.ui.dp
import app.mate.ui.themeColor

/** Côté du disque : comme l'anneau de la tête des cartes à liste, pour que les widgets voisins s'alignent. */
const val HUE_DISC_DP = 56

/** Fond du disque : la teinte à 18 %. */
private const val DISC_ALPHA = 0x2E

/**
 * Icône d'un widget dans un disque de sa teinte `c_hue_*` (variante B de #181) : un repère de type, pas un bouton
 * (ignoré par TalkBack, la carte dit ce qu'elle est). Toujours rond : il prend le plus petit côté offert, sans dépasser
 * [HUE_DISC_DP] ; dans une rangée basse il rapetisse au lieu d'être rogné. À poser avec [hueDiscParams].
 */
fun hueDisc(context: Context, @DrawableRes icon: Int, @AttrRes hue: Int): ImageView = HueDiscView(context).apply {
    val tint = themeColor(context, hue)
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(tint)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ColorUtils.setAlphaComponent(tint, DISC_ALPHA))
    }
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Place du disque dans sa rangée : [HUE_DISC_DP] de large au plus, toute la hauteur offerte, il se réduit au carré. */
fun hueDiscParams(context: Context) = LinearLayout.LayoutParams(context.dp(HUE_DISC_DP), LinearLayout.LayoutParams.MATCH_PARENT)

private class HueDiscView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = context.dp(HUE_DISC_DP)
        fun bound(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) max else minOf(max, MeasureSpec.getSize(spec))
        val side = minOf(bound(widthMeasureSpec), bound(heightMeasureSpec))
        setMeasuredDimension(side, side)
    }
}
