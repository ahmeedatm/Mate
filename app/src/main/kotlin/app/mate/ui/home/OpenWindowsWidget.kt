package app.mate.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.mate.ui.OpenWindowsView

/**
 * Les fenêtres de l'ordinateur sur un profil, comme l'onglet Ouvertes du Deck. En Bloc, Grand et Plein écran, les
 * écrans : glisser une fenêtre sur un autre écran la déplace, la garder sur la corbeille, en bas des écrans, la ferme ;
 * le widget garde alors l'appui long et le balayage, sinon la grille entrerait en mode Modifier ou changerait de profil.
 * En Pastille, Ligne et Colonne, un résumé ([OpenWindowsCompact]) : toucher une appli la met devant, et l'appui long
 * revient à la grille (agrandir, Modifier).
 */
class OpenWindowsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private var compact = OpenWindowsSummary.compact(family)
    private var active = false
    private var sample = false

    /** Créées à la demande : la vue des écrans ne sert qu'aux grandes formes, le résumé qu'aux petites. */
    private var screens: OpenWindowsView? = null
    private var summary: OpenWindowsCompact? = null

    override val view = object : OwnerFrameLayout(context) {
        override val ownsLongPress get() = !compact
        override val ownsSwipe get() = !compact
    }.asCard(12)

    init {
        applySize()
    }

    override fun applySize() {
        compact = OpenWindowsSummary.compact(family)
        view.removeAllViews()
        screens?.setActive(false)
        summary?.setActive(false)
        val shown: View = if (compact) {
            // Le résumé dépend de la famille (Pastille, Ligne, Colonne) : refait à chaque changement de forme.
            OpenWindowsCompact(context, host.scope, family).apply {
                if (sample) showSample(OpenWindowsSample.data(context))
                summary = this
            }
        } else {
            (screens ?: OpenWindowsView(context).apply { attach(host.scope) }.also { screens = it }).apply { if (sample) showSample(OpenWindowsSample.data(context)) }
        }
        view.addView(shown, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (active) setActive(true)
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (compact) summary?.setActive(active) else screens?.setActive(active)
    }

    /** Aperçu du catalogue : des fenêtres d'exemple, dans le résumé ou sur les écrans selon la forme. */
    override fun showPreview(sample: WidgetSample) {
        if (sample !is OpenWindowsSample) return
        this.sample = true
        val data = OpenWindowsSample.data(context)
        if (compact) summary?.showSample(data) else screens?.showSample(data)
    }
}
