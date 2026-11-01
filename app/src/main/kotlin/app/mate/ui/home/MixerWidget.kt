package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import app.mate.Computers
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.mixer.MixerApps
import app.mate.ui.mixer.MixerView
import app.mate.ui.showImmersive
import app.mate.ui.themeColor

/**
 * Console de mixage du Deck sur un profil : sortie, micro, une tranche par appli et les scènes. La console dessine sa
 * plaque et ses coins (pas de carte). Les curseurs se tirent à la verticale et la rangée de tranches défile de côté :
 * le widget garde l'appui long et le balayage, sinon la grille entrerait en mode Modifier ou changerait de profil.
 *
 * ⚙ du mode Modifier : les applis à montrer, dans l'ordre voulu (`MixerApps`, rangées par ordinateur).
 */
class MixerWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val mixer = MixerView(context).apply {
        attach(host.scope)
        appFilter = { live -> MixerApps.channels(chosen(), live) }
    }

    override val view = OwnerFrameLayout(context, ownsLongPress = true, ownsSwipe = true).apply {
        addView(mixer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setActive(active: Boolean) {
        // Relu en arrivant sur l'écran, pas à chaque relecture de la table (4 fois par seconde).
        if (active) loaded = null
        mixer.setActive(active)
    }

    private val computerId get() = Computers.active(context)?.id

    /** Choix lu une fois (ordinateur actif + config) ; oublié à l'arrivée sur l'écran et après un changement. */
    private var loaded: List<MixerApps.App>? = null

    private fun chosen() = loaded ?: MixerApps.read(placed.config, computerId).also { loaded = it }

    // --- Réglages (⚙ du mode Modifier)

    override val hasSettings get() = true

    override fun showSettings() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mixer_apps_title)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .showImmersive()
        fillSettings(box)
    }

    /** Les choisies d'abord (poignée pour les ordonner), puis celles qui ont du son ; refait après chaque changement. */
    private fun fillSettings(box: LinearLayout) {
        box.removeAllViews()
        // Rien de lu (pas encore, ou injoignable) : les choisies seules, décochables ; lu sans liste : Mac.
        val live = if (mixer.hasState) mixer.liveApps else emptyList()
        val candidates = MixerApps.candidates(chosen(), live, mixer::titleOf)
        val hint = when {
            !mixer.hasState -> R.string.mixer_apps_unknown
            live == null -> R.string.mixer_no_apps
            candidates.isEmpty() -> R.string.mixer_apps_none
            else -> R.string.mixer_apps_hint
        }
        box.addView(label(15f, R.attr.c_text_dim).apply {
            setText(hint)
            setPadding(0, 0, 0, dp(8))
        })
        if (live == null) return
        val chosenIds = chosen().map { it.id }.toSet()
        candidates.forEach { app -> box.addView(row(box, app, app.id in chosenIds)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun row(box: LinearLayout, app: MixerApps.App, checked: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        val row = this
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_drag)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, R.attr.c_text_dim)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(4), dp(12), dp(8), dp(12))
            // Seules les applis cochées s'ordonnent ; les autres suivent, dans l'ordre où elles jouent.
            visibility = if (checked) View.VISIBLE else View.INVISIBLE
            setOnTouchListener { _, e ->
                if (!checked || e.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                row.startDragAndDrop(null, View.DragShadowBuilder(row), row, 0)
                true
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val check = CheckBox(context).apply {
            text = app.name
            textSize = 17f
            setTextColor(themeColor(context, R.attr.c_text))
            isChecked = checked
            // Cocher ou décocher change de groupe : la liste se refait (poignées, ordre).
            setOnCheckedChangeListener { _, _ -> commit(box, rebuild = true) }
            if (checked) {
                // TalkBack (la case a le focus) : ordonner sans glisser.
                ViewCompat.addAccessibilityAction(this, context.getString(R.string.mixer_scene_before)) { _, _ -> nudge(box, row, -1) }
                ViewCompat.addAccessibilityAction(this, context.getString(R.string.mixer_scene_after)) { _, _ -> nudge(box, row, 1) }
            }
        }
        addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tag = Row(app, check, checked)
        if (checked) setOnDragListener { target, event -> onDrag(box, target, event) }
    }

    /** Ligne du ⚙ : l'appli, sa case, et si elle était cochée quand la liste a été faite (seules celles-là s'ordonnent). */
    private data class Row(val app: MixerApps.App, val check: CheckBox, val ordered: Boolean)

    /** Une ligne cochée glissée sur une autre cochée prend sa place ; à la fin, l'ordre est enregistré. */
    private fun onDrag(box: LinearLayout, target: View, event: DragEvent): Boolean {
        val dragged = event.localState as? View ?: return false
        if (dragged.parent !== box) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> if (target === dragged) dragged.alpha = DRAGGED_ALPHA
            DragEvent.ACTION_DRAG_ENTERED -> if (target !== dragged) {
                val to = box.indexOfChild(target)
                box.removeView(dragged)
                box.addView(dragged, to)
            }
            DragEvent.ACTION_DRAG_ENDED -> if (target === dragged) {
                dragged.alpha = 1f
                commit(box, rebuild = false)
            }
        }
        return true
    }

    private fun nudge(box: LinearLayout, row: View, by: Int): Boolean {
        val checked = (0 until box.childCount).map { box.getChildAt(it) }.filter { (it.tag as? Row)?.ordered == true }
        val from = checked.indexOf(row)
        val to = from + by
        if (from < 0 || to !in checked.indices) return false
        val index = box.indexOfChild(checked[to])
        box.removeView(row)
        box.addView(row, index)
        commit(box, rebuild = false)
        return true
    }

    /**
     * Choix lu à l'écran (lignes cochées, dans leur ordre) : rangé pour cet ordinateur, la table suit tout de suite.
     * `rebuild` : une case a changé, la liste se refait ; un déplacement est déjà à l'écran (focus TalkBack gardé).
     */
    private fun commit(box: LinearLayout, rebuild: Boolean) {
        val apps = (0 until box.childCount).mapNotNull { (box.getChildAt(it).tag as? Row)?.takeIf { r -> r.check.isChecked }?.app }
        if (!MixerApps.write(placed.config, computerId, apps)) return
        loaded = apps
        host.save()
        mixer.refreshApps()
        if (rebuild) box.post { fillSettings(box) }
    }

    private companion object {
        const val DRAGGED_ALPHA = 0.35f
    }
}
