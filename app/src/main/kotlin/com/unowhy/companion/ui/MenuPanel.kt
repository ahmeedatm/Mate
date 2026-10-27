package com.unowhy.companion.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.unowhy.companion.Mode
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.net.Link
import java.util.EnumMap

/**
 * Menu des thèmes Cadran : caché au repos, sauf une poignée au bord gauche. Il suit le doigt depuis le bord, s'ouvre
 * ou se referme selon la distance et la vitesse au lâcher ([DrawerGesture]), se referme au toucher du voile ou
 * après 6 s sans toucher. Le pied reprend l'ordinateur, l'heure, le WiFi et la batterie de l'ancienne barre latérale.
 */
class MenuPanel(private val activity: Activity, private val onSelect: (Mode) -> Unit) : Navigation {

    private val density = activity.resources.displayMetrics.density
    private val width = DrawerGesture.WIDTH_DP * density
    private val rowStep = activity.dp(60)

    private val host: View = activity.findViewById(R.id.menu_host)
    private val handle: View = activity.findViewById(R.id.menu_handle)
    private val scrim: View = activity.findViewById(R.id.menu_scrim)
    private val panel: View = activity.findViewById(R.id.menu_panel)
    private val ind: View = activity.findViewById(R.id.menu_ind)
    override val talk: View = activity.findViewById(R.id.menu_talk)
    override val status: View = activity.findViewById(R.id.menu_status)
    private val statusDot: View = activity.findViewById(R.id.menu_status_dot)
    private val statusName: TextView = activity.findViewById(R.id.menu_status_name)

    private val rows = EnumMap<Mode, TextView>(Mode::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var panelSpring: SpringAnimation? = null
    private var scrimSpring: SpringAnimation? = null
    private var handleSpring: SpringAnimation? = null
    private var indSpring: SpringAnimation? = null
    private var chosen: Runnable? = null

    var isOpen = false
        private set

    /** Pendant l'écoute, le menu ne se referme pas tout seul. */
    private val autoClose = Runnable { if (talk.isSelected) scheduleClose() else close() }

    init {
        val items = activity.findViewById<LinearLayout>(R.id.menu_items)
        for (mode in Mode.entries) {
            val row = activity.layoutInflater.inflate(R.layout.item_menu, items, false) as TextView
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(mode.icon, 0, 0, 0)
            // Le menu a la place du libellé entier (la barre latérale, 96 dp, dit « Ordi »).
            row.setText(if (mode == Mode.COMPUTER) R.string.menu_computer else mode.label)
            row.setOnClickListener { choose(mode) }
            Look.pressSpring(row)
            items.addView(row)
            rows[mode] = row
        }
        panel.isClickable = true
        Look.pressSpring(talk)
        Look.pressSpring(status)

        // L'état de la tablette change de place sans être détaché (il pilote la luminosité de nuit et le rappel d'eau).
        val tablet = activity.findViewById<TabletStatusView>(R.id.tablet_status)
        (tablet.parent as ViewGroup).removeView(tablet)
        tablet.inline()
        (panel as LinearLayout).addView(tablet, LinearLayout.LayoutParams(-1, activity.dp(40)).apply { marginStart = activity.dp(20); marginEnd = activity.dp(20) })

        panel.translationX = -width
        scrim.setOnClickListener { close() }
        setUpHandle()
        host.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility") // le toucher simple passe par performClick
    private fun setUpHandle() {
        var tracker: VelocityTracker? = null
        var downX = 0f
        var base = 0f
        var dragging = false
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        handle.setOnClickListener { open() }
        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downX = event.rawX
                    dragging = false
                    // Reprise d'un menu en train de se refermer : le doigt repart de où il est.
                    base = if (panel.isVisible) (1 + panel.translationX / width).coerceIn(0f, 1f) else 0f
                    cancelSprings()
                    handler.removeCallbacks(autoClose)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dx = event.rawX - downX
                    if (!dragging && kotlin.math.abs(dx) > slop) {
                        dragging = true
                        reveal()
                    }
                    if (dragging) follow((base + dx / width).coerceIn(0f, 1f))
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.apply { addMovement(event); computeCurrentVelocity(1000) }
                    val velocity = tracker?.xVelocity ?: 0f
                    if (!dragging) v.performClick() else {
                        val progress = (base + (event.rawX - downX) / width).coerceIn(0f, 1f)
                        if (DrawerGesture.shouldOpen(progress, velocity / density)) open(velocity) else close()
                    }
                    tracker?.recycle()
                    tracker = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) close()
                    tracker?.recycle()
                    tracker = null
                }
            }
            true
        }
        // Le geste Retour du système part du même bord (API 29+) : la poignée le garde pour elle.
        if (Build.VERSION.SDK_INT >= 29) {
            handle.addOnLayoutChangeListener { v, _, top, right, bottom, _, _, _, _ ->
                val half = activity.dp(100)
                val middle = (top + bottom) / 2
                v.systemGestureExclusionRects = listOf(Rect(0, middle - half, right, middle + half))
            }
        }
    }

    private fun reveal() {
        panel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun follow(progress: Float) {
        panel.translationX = -width * (1 - progress)
        scrim.alpha = progress
        handle.alpha = 1 - progress
    }

    private fun cancelSprings() {
        listOf(panelSpring, scrimSpring, handleSpring).forEach { it?.cancel() }
    }

    /** `velocity` : vitesse du doigt (px/s), d'où part le ressort. */
    fun open(velocity: Float = 0f) {
        isOpen = true
        reveal()
        cancelSprings()
        panelSpring = Look.springTo(panel, DynamicAnimation.TRANSLATION_X, Look.SPATIAL_DEFAULT, 0f, velocity)
        scrimSpring = Look.springTo(scrim, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        handleSpring = Look.springTo(handle, DynamicAnimation.ALPHA, Look.EFFECTS_FAST, 0f)
        enter()
        scheduleClose()
    }

    fun close() {
        handler.removeCallbacks(autoClose)
        if (!isOpen && !panel.isVisible) return
        isOpen = false
        cancelSprings()
        panelSpring = Look.springTo(panel, DynamicAnimation.TRANSLATION_X, Look.SPATIAL_FAST, -width) {
            panel.visibility = View.GONE
            scrim.visibility = View.GONE
            panel.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        scrimSpring = Look.springTo(scrim, DynamicAnimation.ALPHA, Look.EFFECTS_FAST, 0f)
        handleSpring = Look.springTo(handle, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
    }

    private fun scheduleClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    /** Un toucher n'importe où remet à zéro le délai de fermeture. */
    fun touched() {
        if (isOpen) scheduleClose()
    }

    /** Parler et les destinations arrivent l'une après l'autre : glissent de 14 dp vers leur place en fondu. */
    private fun enter() {
        val views = listOf(talk) + rows.values
        views.forEachIndexed { i, view ->
            view.translationX = if (Look.reduced(activity)) 0f else -14 * density
            view.alpha = if (Look.reduced(activity)) 1f else 0f
            if (Look.reduced(activity)) return@forEachIndexed
            view.postDelayed({
                if (!isOpen) return@postDelayed
                Look.springTo(view, DynamicAnimation.TRANSLATION_X, Look.SPATIAL_FAST, 0f)
                Look.springTo(view, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
            }, 40L + i * 28L)
        }
    }

    /** Toucher une destination : le fond glisse jusqu'à elle, puis le menu se ferme et l'écran change. */
    private fun choose(mode: Mode) {
        mark(mode, animate = true)
        chosen?.let { handler.removeCallbacks(it) }
        chosen = Runnable {
            close()
            onSelect(mode)
        }.also { handler.postDelayed(it, CHOOSE_DELAY_MS) }
    }

    private fun mark(mode: Mode, animate: Boolean) {
        rows.forEach { (m, row) -> row.isSelected = m == mode }
        val target = (Mode.entries.indexOf(mode) * rowStep).toFloat()
        if (ind.translationY == target) return
        indSpring?.cancel()
        if (animate) {
            indSpring = Look.springTo(ind, DynamicAnimation.TRANSLATION_Y, Look.SPATIAL_DEFAULT, target)
        } else {
            ind.translationY = target
        }
    }

    override fun select(mode: Mode, changed: Boolean) = mark(mode, animate = isOpen && changed)

    override fun showFocus(state: FocusTimer.State) {
        rows.getValue(Mode.FOCUS).text = focusLabel(activity, state)
    }

    /** Pas de pastille dans le menu : le nombre de tâches restantes est dans la carte Tâches. */
    override fun setPendingTasks(count: Int) = Unit

    override fun showLink(link: Link) = renderLink(activity, statusDot, statusName, status, link)

    private companion object {
        const val AUTO_CLOSE_MS = 6_000L
        const val CHOOSE_DELAY_MS = 220L
    }
}
