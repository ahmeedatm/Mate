package com.unowhy.unomate.ui.topbar

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.device.TabletSignals
import com.unowhy.unomate.net.CalendarEvent
import com.unowhy.unomate.ui.MusicChip
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.showImmersive
import com.unowhy.unomate.ui.themeColor
import com.unowhy.unomate.ui.themeFont
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Écran qui pose son en-tête dans le bandeau du haut (titre, retour, boutons) : une vue de sa mise en page, que le
 * bandeau lui prend ; null : le nom du mode.
 */
interface TopHeader {
    val topHeader: View?
}

/** Ce que les éléments du bandeau demandent à l'appli (MainActivity). */
interface TopBarHost {
    val context: Context
    val scope: CoroutineScope
    val signals: TabletSignals
    fun hasComputer(): Boolean
    fun musicSuppressed(): Boolean
    fun callActive(): Boolean
    fun agentsOffered(): Boolean
    fun openAgents()
    fun joinMeeting(event: CalendarEvent)
    fun showError(e: Throwable)

    /** Ville d'un widget Météo (ou héros) de l'Accueil, pour la météo du bandeau : city, lat, lon ; null : aucune. */
    fun homeCity(): JSONObject?
}

/**
 * Bandeau du haut, fixe au-dessus de l'écran du mode (rien ne défile dessous) : à gauche l'en-tête de l'écran (titre,
 * retour, boutons : la vue de l'écran lui-même, [setHeader]), à droite les éléments choisis (Prefs.topBar), dans l'ordre.
 * Place qui manque : ceux du début sautent ([TopBarModel.firstShown]). Mode Modifier ([edit]) : crayon de la barre ou
 * appui long sur le bandeau ; chaque élément devient une pastille à glisser, avec ✕, et « Ajouter ».
 */
class TopBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private lateinit var host: TopBarHost
    private val header = FrameLayout(context)
    private val title = TextView(context).apply {
        textSize = 24f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        typeface = themeFont(context, R.attr.c_font_title)
        setTextColor(themeColor(context, R.attr.c_text))
    }
    private var headerView: View? = null

    /** Voyant du mot de réveil « Mate » (voice/WakeWord), toujours juste avant les éléments. */
    val wakeMic = ImageView(context).apply {
        setImageResource(R.drawable.ic_mic)
        contentDescription = context.getString(R.string.wake_listening)
        visibility = GONE
        setColorFilter(themeColor(context, R.attr.c_text_faint))
    }
    private val row = TopItemsLayout(context)
    /** Mode Modifier : consigne, « Ajouter », les pastilles (qui défilent si elles débordent), « OK ». */
    private val editRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val chips = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val hint = TextView(context).apply {
        textSize = 15f
        maxWidth = dp(280)
        setText(R.string.top_edit_hint)
        setTextColor(themeColor(context, R.attr.c_text_dim))
    }

    private var items: List<TopItem> = TopBarModel.parse(Prefs.topBar(context))
    private val elements = LinkedHashMap<TopItem, TopElement>()
    private var running = false

    var editing = false
        private set

    /** Fin du mode Modifier par « OK » : MainActivity rend le crayon de la barre. */
    var onEditDone: () -> Unit = {}

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), 0, dp(24), 0)
        header.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(header, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(wakeMic, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(editRow, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        editRow.addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        editRow.addView(
            Button(context, null, 0, R.style.Chip).apply {
                setText(R.string.top_edit_add)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
                compoundDrawablePadding = dp(6)
                setOnClickListener { showAdd() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) },
        )
        editRow.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(chips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        editRow.addView(
            Button(context, null, 0, R.style.Chip).apply {
                setText(R.string.top_edit_done)
                isSelected = true
                setOnClickListener {
                    edit(false)
                    onEditDone()
                }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) },
        )
        // Appui long sur le bandeau (hors boutons) : le modifier.
        setOnLongClickListener {
            edit(true)
            true
        }
        contentDescription = context.getString(R.string.top_edit_open)
    }

    fun attach(host: TopBarHost) {
        this.host = host
        rebuild()
    }

    /** Pastille Lecture en cours, si elle est dans le bandeau (relue tout de suite après un changement d'état). */
    val music: MusicChip? get() = (elements[TopItem.MUSIC] as? MusicElement)?.chip

    /** Titre seul, pour les écrans sans en-tête propre (Accueil : le nom du profil ; Focus). */
    fun setTitle(text: CharSequence) {
        setHeader(null)
        title.text = text
    }

    /** En-tête d'un écran (retiré de sa mise en page par l'écran) ; null : le titre seul. */
    fun setHeader(view: View?) {
        if (view === headerView) return
        headerView?.let { header.removeView(it) }
        headerView = view
        title.visibility = if (view == null) VISIBLE else GONE
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            header.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            view.visibility = VISIBLE
        }
    }

    /** Bandeau à l'écran (activité au premier plan) : les éléments se relisent, sinon rien ne tourne. */
    fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        elements.values.forEach { if (on) it.start() else it.stop() }
    }

    /** Ordinateur changé ou ses offres relues (Agents) : chaque élément repart de zéro. */
    fun restart() {
        if (!running) return
        elements.values.forEach {
            it.stop()
            it.start()
        }
    }

    private fun rebuild() {
        val wanted = items.toSet()
        for ((item, element) in elements.entries.toList()) {
            if (item in wanted) continue
            if (running) element.stop()
            elements.remove(item)
        }
        row.removeAllViews()
        for (item in items) {
            val element = elements.getOrPut(item) {
                TopElement.create(item, host).also { if (running) it.start() }
            }
            (element.view.parent as? ViewGroup)?.removeView(element.view)
            row.addView(element.view)
        }
        if (editing) fillEditRow()
    }

    private fun save(next: List<TopItem>) {
        items = next
        Prefs.setTopBar(context, TopBarModel.serialize(next))
        rebuild()
    }

    // --- Mode Modifier

    fun edit(on: Boolean) {
        if (on == editing) return
        editing = on
        // En-tête de l'écran et voyant cachés : toute la largeur pour les pastilles.
        header.visibility = if (on) GONE else VISIBLE
        wakeMic.alpha = if (on) 0f else 1f
        row.visibility = if (on) GONE else VISIBLE
        editRow.visibility = if (on) VISIBLE else GONE
        if (on) fillEditRow() else chips.removeAllViews()
    }

    private fun fillEditRow() {
        chips.removeAllViews()
        for (item in items) chips.addView(editChip(item), LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
    }

    /** Pastille d'un élément : son icône et son nom (à glisser pour le ranger), puis ✕. */
    private fun editChip(item: TopItem): View {
        val chip = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(12), 0, dp(4), 0)
            tag = item
        }
        chip.addView(
            ImageView(context).apply {
                setImageResource(TopItems.icon(item))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setColorFilter(themeColor(context, R.attr.c_text_dim))
            },
            LayoutParams(dp(18), dp(18)),
        )
        chip.addView(
            TextView(context).apply {
                setText(TopItems.label(item))
                textSize = 14f
                setTextColor(themeColor(context, R.attr.c_text))
            },
            LayoutParams(-2, -2).apply { marginStart = dp(6) },
        )
        chip.addView(
            ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(themeColor(context, R.attr.c_text_dim))
                contentDescription = context.getString(R.string.top_edit_remove, context.getString(TopItems.label(item)))
                setOnClickListener { save(TopBarModel.remove(items, item)) }
            },
            LayoutParams(dp(36), dp(36)),
        )
        chip.setOnTouchListener(DragToMove(chip, item))
        return chip
    }

    /**
     * Pastille maintenue puis glissée de côté (un glisser direct fait défiler la rangée) : elle suit le doigt, lâchée
     * elle prend la place de la pastille la plus proche.
     */
    private inner class DragToMove(private val chip: View, private val item: TopItem) : OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var dragging = false
        private val grab = Runnable {
            dragging = true
            chip.parent.requestDisallowInterceptTouchEvent(true)
            chip.elevation = dp(6).toFloat()
            chip.isPressed = true
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    dragging = false
                    chip.postDelayed(grab, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (dragging) chip.translationX = dx else if (abs(dx) > slop) chip.removeCallbacks(grab)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    chip.removeCallbacks(grab)
                    chip.isPressed = false
                    if (!dragging) return true
                    dragging = false
                    val center = chip.left + chip.translationX + chip.width / 2f
                    val all = (0 until chips.childCount).map { chips.getChildAt(it) }
                    val target = all.minByOrNull { abs(it.left + it.width / 2f - center) }?.let { all.indexOf(it) } ?: return true
                    chip.translationX = 0f
                    chip.elevation = 0f
                    save(TopBarModel.move(items, item, target))
                }
            }
            return true
        }
    }

    private fun showAdd() {
        val addable = TopBarModel.addable(items, host.agentsOffered())
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.top_add_title)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        if (addable.isEmpty()) {
            list.addView(TextView(context).apply {
                setText(R.string.top_add_none)
                textSize = 16f
                setTextColor(themeColor(context, R.attr.c_text_dim))
            })
        }
        for (item in addable) {
            list.addView(
                addRow(item) {
                    dialog.dismiss()
                    save(TopBarModel.add(items, item))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
            )
        }
        dialog.showImmersive()
    }

    private fun addRow(item: TopItem, onClick: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(
            ImageView(context).apply {
                setImageResource(TopItems.icon(item))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setColorFilter(themeColor(context, R.attr.c_accent))
            },
            LayoutParams(dp(28), dp(28)),
        )
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    setText(TopItems.label(item))
                    textSize = 18f
                    setTextColor(themeColor(context, R.attr.c_text))
                })
                addView(TextView(context).apply {
                    setText(TopItems.description(item))
                    textSize = 14f
                    setTextColor(themeColor(context, R.attr.c_text_dim))
                })
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) },
        )
        setOnClickListener { onClick() }
    }
}

/** Nom, description et icône de chaque élément (catalogue « Ajouter » et pastilles du mode Modifier). */
object TopItems {
    fun label(item: TopItem) = when (item) {
        TopItem.MUSIC -> R.string.top_music
        TopItem.MEETING -> R.string.top_meeting
        TopItem.FOCUS -> R.string.top_focus
        TopItem.CALL -> R.string.top_call
        TopItem.AGENTS -> R.string.top_agents
        TopItem.WEATHER -> R.string.top_weather
        TopItem.DATE -> R.string.top_date
        TopItem.CLOCK -> R.string.top_clock
        TopItem.WIFI -> R.string.top_wifi
        TopItem.BATTERY -> R.string.top_battery
    }

    fun description(item: TopItem) = when (item) {
        TopItem.MUSIC -> R.string.top_music_desc
        TopItem.MEETING -> R.string.top_meeting_desc
        TopItem.FOCUS -> R.string.top_focus_desc
        TopItem.CALL -> R.string.top_call_desc
        TopItem.AGENTS -> R.string.top_agents_desc
        TopItem.WEATHER -> R.string.top_weather_desc
        TopItem.DATE -> R.string.top_date_desc
        TopItem.CLOCK -> R.string.top_clock_desc
        TopItem.WIFI -> R.string.top_wifi_desc
        TopItem.BATTERY -> R.string.top_battery_desc
    }

    fun icon(item: TopItem) = when (item) {
        TopItem.MUSIC -> R.drawable.ic_play
        TopItem.MEETING -> R.drawable.ic_event
        TopItem.FOCUS -> R.drawable.ic_timer
        TopItem.CALL -> R.drawable.ic_mic
        TopItem.AGENTS -> R.drawable.ic_agents
        TopItem.WEATHER -> R.drawable.ic_cloud
        TopItem.DATE -> R.drawable.ic_event
        TopItem.CLOCK -> R.drawable.ic_clock
        TopItem.WIFI -> R.drawable.ic_wifi
        TopItem.BATTERY -> R.drawable.ic_battery
    }
}

/**
 * Les éléments sur une ligne, centrés en hauteur, [GAP] entre deux ; plus large que la place donnée (moins
 * [RESERVE] laissés au titre) : ceux du début ne sont pas placés.
 */
class TopItemsLayout(context: Context) : ViewGroup(context) {
    private var first = 0
    private val gap = context.dp(GAP)
    private val reserve = context.dp(RESERVE)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val height = MeasureSpec.getSize(heightSpec)
        val room = (MeasureSpec.getSize(widthSpec) - reserve).coerceAtLeast(0)
        val childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        val widths = (0 until childCount).map { i ->
            val child = getChildAt(i)
            if (child.visibility == GONE) {
                0
            } else {
                val lp = child.layoutParams
                val w = if (lp != null && lp.width > 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val h = if (lp != null && lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else childHeight
                child.measure(w, h)
                child.measuredWidth
            }
        }
        first = TopBarModel.firstShown(widths, gap, room)
        val shown = widths.drop(first).filter { it > 0 }
        setMeasuredDimension(shown.sum() + gap * (shown.size - 1).coerceAtLeast(0), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (i < first) {
                child.layout(0, 0, 0, 0)
                continue
            }
            val top = ((b - t - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
            x += child.measuredWidth + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private companion object {
        const val GAP = 16
        const val RESERVE = 240
    }
}
