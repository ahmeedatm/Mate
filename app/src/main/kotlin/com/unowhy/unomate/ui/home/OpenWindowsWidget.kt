package com.unowhy.unomate.ui.home

import android.content.Context
import android.view.ViewGroup
import com.unowhy.unomate.ui.OpenWindowsView

/**
 * Les fenêtres de l'ordinateur sur un profil, comme l'onglet Ouvertes du Deck. Glisser une fenêtre sur un autre écran
 * la déplace, la garder dans la bande du bas la ferme : le widget garde l'appui long et le balayage, sinon la grille
 * entrerait en mode Modifier ou changerait de profil.
 */
class OpenWindowsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val openWindows = OpenWindowsView(context).apply { attach(host.scope) }

    override val view = OwnerFrameLayout(context, ownsLongPress = true, ownsSwipe = true).asCard(12).apply {
        addView(openWindows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setActive(active: Boolean) = openWindows.setActive(active)
}
