package app.mate.ui.home

import android.content.Context
import app.mate.ui.YtMusicView

/**
 * YouTube Music qui joue sur l'ordinateur, piloté d'ici : lecteur et file d'attente en XL (4 × 2) et L3, lecteur seul
 * en L (2 × 2). Relu seulement quand l'écran est affiché.
 */
class YtMusicWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val music = YtMusicView(context).apply { attach(host.scope) }
    override val view = music.asCard(20)

    override fun applySize() {
        music.showQueue = placed.size != WidgetSize.L
    }

    override fun setActive(active: Boolean) = music.setActive(active)
}
