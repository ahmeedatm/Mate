package com.unowhy.unomate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.unowhy.unomate.Computers
import com.unowhy.unomate.R
import com.unowhy.unomate.ui.pointer.PadView

/**
 * Le pavé tactile du Deck sur un profil : un doigt bouge le pointeur de l'ordinateur, deux défilent, une tape clique.
 * Le widget garde l'appui long et le balayage, sinon la grille entrerait en mode Modifier ou changerait de profil.
 * Le canal vers l'ordinateur n'existe que tant que le profil est à l'écran. Hors Mac et Windows : une phrase.
 */
class PadWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val pad = if (Computers.active(context)?.os in SUPPORTED) PadView(context).apply { useHomeCard() } else null

    override val view = OwnerFrameLayout(context, ownsLongPress = pad != null, ownsSwipe = pad != null).apply {
        if (pad != null) {
            addView(pad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            asCard()
            addView(
                label(15f, R.attr.c_text_dim).apply {
                    setText(R.string.pad_unsupported)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
    }

    override fun setActive(active: Boolean) {
        pad?.setActive(active)
    }

    private companion object {
        val SUPPORTED = setOf("mac", "windows")
    }
}
