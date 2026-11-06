package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.mate.Computers
import app.mate.R
import app.mate.ui.pointer.PadView

/**
 * Le pavé tactile du Deck sur un profil. En Bloc, Grand et Plein écran, le pavé entier : un doigt bouge le pointeur
 * de l'ordinateur, deux défilent, une tape clique. En Pastille, Ligne et Colonne, [PadCompact] : un bouton Clic ou une
 * bande de défilement. Le widget garde l'appui long et le balayage, sinon la grille entrerait en mode Modifier ou
 * changerait de profil. Le canal vers l'ordinateur n'existe que tant que le profil est à l'écran. Hors Mac et Windows :
 * une phrase (sauf dans l'aperçu du catalogue, qui montre le pavé sans ordinateur).
 */
class PadWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val supported get() = sample || Computers.active(context)?.os in SUPPORTED
    private var sample = false
    private var active = false

    /** Le pavé entier, créé une fois (il garde son enregistrement de macro) ; le petit, refait à chaque forme. */
    private var pad: PadView? = null
    private var compact: PadCompact? = null

    override val view = object : OwnerFrameLayout(context) {
        override val ownsLongPress get() = supported
        override val ownsSwipe get() = supported
    }

    init {
        applySize()
    }

    override fun applySize() {
        pad?.setActive(false)
        compact?.setActive(false)
        compact = null
        view.removeAllViews()
        view.background = null
        view.setPadding(0, 0, 0, 0)
        if (!supported) return showUnsupported()
        val shown: View = if (OpenWindowsSummary.compact(family)) {
            PadCompact(context, family).also { compact = it }
        } else {
            pad ?: PadView(context).apply {
                useHomeCard()
                enableRecording(host.scope)
            }.also { pad = it }
        }
        view.addView(shown, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (sample) pad?.showSample()
        if (active) setActive(true)
    }

    private fun showUnsupported() {
        view.asCard()
        view.addView(
            label(15f, R.attr.c_text_dim).apply {
                setText(R.string.pad_unsupported)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (sample) return
        compact?.setActive(active)
        pad?.setActive(active && compact == null)
    }

    /** Aperçu du catalogue : le pavé ou ses boutons, sans canal ni ordinateur. */
    override fun showPreview(sample: WidgetSample) {
        if (sample !is PadSample) return
        this.sample = true
        applySize()
    }

    private companion object {
        val SUPPORTED = setOf("mac", "windows")
    }
}

/** Exemple du Pavé tactile pour le catalogue : rien à charger, le pavé se montre tel quel, sans canal. */
object PadSample : WidgetSample
