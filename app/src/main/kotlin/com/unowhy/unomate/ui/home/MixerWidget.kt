package com.unowhy.unomate.ui.home

import android.content.Context
import android.view.ViewGroup
import com.unowhy.unomate.ui.mixer.MixerView

/**
 * Console de mixage du Deck sur un profil : sortie, micro, une tranche par appli et les scènes. La console dessine sa
 * plaque et ses coins (pas de carte). Les curseurs se tirent à la verticale et la rangée de tranches défile de côté :
 * le widget garde l'appui long et le balayage, sinon la grille entrerait en mode Modifier ou changerait de profil.
 */
class MixerWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val mixer = MixerView(context).apply { attach(host.scope) }

    override val view = OwnerFrameLayout(context, ownsLongPress = true, ownsSwipe = true).apply {
        addView(mixer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setActive(active: Boolean) = mixer.setActive(active)
}
