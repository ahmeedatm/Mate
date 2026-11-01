package app.mate.ui.pointer

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.PointerButton
import app.mate.net.PointerChannel
import app.mate.net.PointerGesture
import app.mate.net.runCatchingCancellable
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.themeColor
import app.mate.ui.themeRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Onglet « Pavé » du deck : une grande surface où les doigts pilotent le pointeur de l'ordinateur (un doigt bouge,
 * deux défilent, une tape clique), et deux boutons Clic gauche / Clic droit dessous. Le canal vers l'ordinateur
 * n'existe que tant que la vue est active ; l'écran reste allumé pendant ce temps. Sur un profil, un bouton Enregistrer
 * note les gestes faits (avec leurs pauses) jusqu'à Terminer, et les range dans une touche Perso (`MacroRecorder`).
 */
class PadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val surface = FrameLayout(context)
    private var channel: PointerChannel? = null
    private var gestures: PadGestures? = null
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var recordButton: Button? = null
    private var recorder: MacroRecorder? = null
    private var scope: CoroutineScope? = null

    init {
        orientation = VERTICAL
        visibility = GONE

        surface.setBackgroundResource(themeRes(context, R.attr.c_card_bg))
        surface.addView(
            TextView(context).apply {
                setText(R.string.pad_hint)
                textSize = 15f
                setTextColor(themeColor(context, R.attr.c_text_faint))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        surface.setOnTouchListener { v, e -> touch(v, e) }
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        buttons.addView(pill(R.string.pad_left_click, PointerButton.LEFT), LayoutParams(0, dp(BUTTON_DP), 1f))
        buttons.addView(pill(R.string.pad_right_click, PointerButton.RIGHT), LayoutParams(0, dp(BUTTON_DP), 1f).apply { marginStart = dp(12) })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun pill(label: Int, button: PointerButton?) = Button(context).apply {
        setText(label)
        isAllCaps = false
        textSize = 16f
        setTextColor(themeColor(context, R.attr.c_text))
        setBackgroundResource(R.drawable.pill_bg)
        if (button != null) setOnClickListener { channel?.click(button) }
    }

    /** Ajoute le bouton Enregistrer ; [scope] enregistre la touche sur l'ordinateur sans bloquer l'écran. */
    fun enableRecording(scope: CoroutineScope) {
        if (recordButton != null) return
        this.scope = scope
        recordButton = pill(R.string.pad_record, null).also {
            it.setOnClickListener { toggleRecording() }
            buttons.addView(it, LayoutParams(0, dp(BUTTON_DP), 1f).apply { marginStart = dp(12) })
        }
    }

    private fun toggleRecording() {
        val done = recorder
        if (done == null) {
            recorder = MacroRecorder()
            showRecording()
            return
        }
        recorder = null
        showRecording()
        if (done.isEmpty) return toast(context.getString(R.string.pad_record_empty))
        scope?.launch {
            runCatchingCancellable { BackendClient(context).deck.addPersoKey(done.toKey(context.getString(R.string.pad_record_label))) }
                .onSuccess { toast(context.getString(R.string.pad_record_saved, it + 1)) }
                .onFailure { toast(context.errorText(it)) }
        }
    }

    private fun showRecording() {
        val current = recorder
        recordButton?.text = if (current == null) context.getString(R.string.pad_record) else context.getString(R.string.pad_record_stop, current.gestures)
    }

    /** Un geste fait sur le Pavé : noté s'il y a un enregistrement en cours. */
    private fun noteGesture(gesture: PointerGesture) {
        val current = recorder ?: return
        if (!current.add(gesture, SystemClock.elapsedRealtime())) toast(context.getString(R.string.pad_record_full))
        showRecording()
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** Sur un profil, la surface prend le fond des cartes de l'Accueil, comme les autres widgets (translucide en Rétro). */
    fun useHomeCard() = surface.setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))

    /** Affichée : canal ouvert et écran gardé allumé ; cachée : canal fermé. */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        keepScreenOn = active
        if (active) {
            if (channel != null) return
            val opened = PointerChannel.create(context)
            channel = opened
            // Les seuils sont en pixels : on les adapte à la densité de l'écran.
            val density = resources.displayMetrics.density
            gestures = PadGestures({ opened.send(it); if (it is PadCommand.Gesture) noteGesture(it.gesture) }, TAP_SLOP_PX * density, PX_PER_NOTCH * density, { surface.width.toFloat() })
        } else {
            gestures?.onTouch(TouchAction.CANCEL, 0, 0f, 0f, 0L)
            gestures = null
            // Un enregistrement ne survit pas à un changement d'écran.
            recorder = null
            showRecording()
            channel?.close()
            channel = null
        }
    }

    private fun touch(v: View, e: MotionEvent): Boolean {
        val g = gestures ?: return true
        // Les glissés de côté sont pour le pavé : ni le balayage d'onglets ni un défilement parent ne les prennent.
        v.parent?.requestDisallowInterceptTouchEvent(true)
        val up = e.actionMasked == MotionEvent.ACTION_POINTER_UP
        val lifted = if (up) e.actionIndex else -1
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == lifted) continue
            x += e.getX(i)
            y += e.getY(i)
            n++
        }
        if (n > 0) {
            x /= n
            y /= n
        }
        // Écart entre les deux doigts posés (le pincement ne compte qu'à deux doigts).
        var spread = 0f
        if (n == 2) {
            val idx = (0 until e.pointerCount).filter { it != lifted }
            spread = hypot(e.getX(idx[0]) - e.getX(idx[1]), e.getY(idx[0]) - e.getY(idx[1]))
        }
        val t = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> g.onTouch(TouchAction.DOWN, n, x, y, t, spread)
            MotionEvent.ACTION_MOVE -> g.onTouch(TouchAction.MOVE, n, x, y, t, spread)
            MotionEvent.ACTION_POINTER_UP -> g.onTouch(TouchAction.UP, n, x, y, t, spread)
            MotionEvent.ACTION_UP -> g.onTouch(TouchAction.UP, 0, x, y, t)
            MotionEvent.ACTION_CANCEL -> g.onTouch(TouchAction.CANCEL, 0, x, y, t)
        }
        return true
    }

    private companion object {
        const val BUTTON_DP = 56
    }
}
