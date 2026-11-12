package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.OpenApp
import app.mate.net.OpenWindows
import app.mate.net.runCatchingCancellable
import app.mate.ui.IconCache
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fenêtres ouvertes en Pastille, Ligne et Colonne (#183) : un résumé sans défilement ni glisser ([OpenWindowsSummary]).
 * Pastille : l'icône de l'appli au premier plan et le nombre de fenêtres ; Ligne : les icônes des applis, un trait
 * sous celle du premier plan ; Colonne : la liste des applis (icône, nom, nombre de fenêtres), comme Touches et Applis
 * ([TileShapes]). Toucher une appli met sa fenêtre devant. Relu toutes les 5 s, seulement page à l'écran ; redessiné
 * seulement si la liste, la phrase ou la taille changent. [initial] : la liste déjà lue avant un changement de forme.
 */
class OpenWindowsCompact(
    context: Context,
    private val scope: CoroutineScope,
    private val family: ShapeFamily,
    initial: OpenWindows? = null,
    /** Thème Instrument (fenêtre LCD) : un capuchon par appli, au lieu des icônes et de la liste. */
    private val instrument: Boolean = false,
) : FrameLayout(context) {
    /** Dernière liste lue, gardée par le widget quand la forme change. */
    var data: OpenWindows? = initial
        private set
    private var status: String = context.getString(R.string.open_loading)
    private var poll: Job? = null
    private var action: Job? = null
    private var sample = false

    /** Ce qui est à l'écran : rien n'est refait tant que ça ne change pas (un toucher en cours n'est pas perdu). */
    private var drawn: Triple<Any?, Int, Int>? = null

    /** Aperçu du catalogue : des données fixes, ni réseau ni boucle. */
    fun showSample(sample: OpenWindows) {
        this.sample = true
        data = sample
        render()
    }

    fun setActive(active: Boolean) {
        poll?.cancel()
        action?.cancel()
        poll = null
        if (!active || sample) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun refresh() {
        runCatchingCancellable { BackendClient(context).windows.openWindows() }
            .onSuccess { data = it }
            // Un échec isolé garde la liste affichée ; sans liste, la phrase de l'erreur.
            .onFailure { if (data == null) status = context.errorText(it) }
        render()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { render() }
    }

    private fun render() {
        val state = Triple(data ?: status, width, height)
        if (state == drawn) return
        drawn = state
        removeAllViews()
        val current = data ?: return addView(note(status), LayoutParams(MATCH, WRAP, Gravity.CENTER))
        val apps = OpenWindowsSummary.apps(current)
        if (apps.isEmpty()) return addView(note(context.getString(R.string.widget_open_none_short)), LayoutParams(MATCH, WRAP, Gravity.CENTER))
        addView(icons(apps, OpenWindowsSummary.total(current)), LayoutParams(MATCH, MATCH))
    }

    private fun icons(apps: List<OpenWindowsSummary.Entry>, total: Int): View = if (instrument) caps(apps) else when (family) {
        ShapeFamily.PILL -> pill(apps.first(), total)
        ShapeFamily.ROW -> LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val fit = TileShapes.fit(room.width, dp(TileShapes.ICON_DP), dp(TileShapes.ICON_GAP_DP))
            apps.take(fit).forEachIndexed { i, entry ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.row_bg)
                    addView(icon(entry.app), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
                    // Le premier plan : un trait sous l'icône, et « au premier plan » dans la description lue par TalkBack.
                    addView(View(context).apply {
                        setBackgroundColor(color(R.attr.c_accent))
                        visibility = if (entry.front) VISIBLE else INVISIBLE
                    }, LinearLayout.LayoutParams(dp(FRONT_MARK_DP), dp(3)).apply { topMargin = dp(6) })
                }
                val lp = LinearLayout.LayoutParams(dp(TileShapes.ICON_DP), MATCH).apply { if (i > 0) marginStart = dp(TileShapes.ICON_GAP_DP) }
                addView(tappable(cell, entry, describe(entry)), lp)
            }
        }
        else -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val fit = TileShapes.fit(room.height, dp(TileShapes.LIST_ROW_DP), dp(TileShapes.LIST_GAP_DP))
            apps.take(fit).forEachIndexed { i, entry ->
                addView(row(entry), LinearLayout.LayoutParams(MATCH, dp(TileShapes.LIST_ROW_DP)).apply { if (i > 0) topMargin = dp(TileShapes.LIST_GAP_DP) })
            }
        }
    }

    /**
     * Instrument : Pastille, l'appli devant ; Ligne, les capuchons côte à côte ; Colonne, empilés ([KeyCaps.stripFit]).
     * Numéro « 01 », icône grisée, « CHROME · 2 », DEL allumée pour l'appli au premier plan.
     */
    private fun caps(apps: List<OpenWindowsSummary.Entry>): View {
        val density = resources.displayMetrics.density
        val gap = dp(TileShapes.ICON_GAP_DP)
        fun cap(entry: OpenWindowsSummary.Entry, index: Int) = WindowCapViews.inflate(context).also {
            val windows = context.resources.getQuantityString(R.plurals.widget_open_windows, entry.windows, entry.windows)
            WindowCapViews.bind(
                it, KeyCaps.number(index), entry.app, WindowCaps.appLabel(entry.app.name, entry.windows), entry.front,
                context.getString(R.string.widget_open_row_desc, describe(entry), windows), scope.takeUnless { sample },
            )
            tappable(it, entry, it.contentDescription.toString())
            press(it)
        }
        return when (family) {
            ShapeFamily.PILL -> FrameLayout(context).apply {
                addView(cap(apps.first(), 0), LayoutParams(MATCH, minOf(dp(KeyCaps.CELL_DP), room.height), Gravity.CENTER))
            }
            ShapeFamily.ROW -> LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                apps.take(KeyCaps.stripFit(family, room.width, room.height, density)).forEachIndexed { i, entry ->
                    addView(cap(entry, i), LinearLayout.LayoutParams(0, minOf(dp(KeyCaps.CELL_DP), room.height), 1f).apply { if (i > 0) marginStart = gap })
                }
            }
            else -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val row = dp(TileShapes.LIST_ROW_PANEL_DP)
                apps.take(KeyCaps.stripFit(family, room.width, room.height, density)).forEachIndexed { i, entry ->
                    val view = cap(entry, i)
                    tighten(view)
                    addView(view, LinearLayout.LayoutParams(MATCH, row).apply { if (i > 0) topMargin = dp(TileShapes.LIST_GAP_PANEL_DP) })
                }
            }
        }
    }

    /** Colonne : des capuchons de 56 dp, l'icône et le haut du capuchon se serrent pour que le nom tienne. */
    private fun tighten(cap: View) {
        cap.findViewById<View>(R.id.key_face).setPadding(dp(6), dp(TIGHT_TOP_DP), dp(6), dp(2))
        (cap.findViewById<View>(R.id.tile_icon).parent as View).layoutParams.apply {
            width = dp(TIGHT_ICON_DP)
            height = dp(TIGHT_ICON_DP)
        }
        cap.findViewById<TextView>(R.id.tile_initial).textSize = TIGHT_INITIAL_SP
    }

    /** Le capuchon s'enfonce sous le doigt et revient sur son ressort ; le toucher reste à `tappable`. */
    @SuppressLint("ClickableViewAccessibility")
    private fun press(cap: View) {
        cap.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> TileGridView.press(v, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> TileGridView.press(v, false)
            }
            false
        }
    }

    /** Pastille : l'icône de l'appli au premier plan, le nombre de fenêtres juste dessous. */
    private fun pill(front: OpenWindowsSummary.Entry, total: Int): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.row_bg)
            addView(icon(front.app), LinearLayout.LayoutParams(dp(PILL_ICON_DP), dp(PILL_ICON_DP)))
            addView(label(13f, R.attr.c_text_dim).apply {
                text = total.toString()
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        }
        val windows = context.resources.getQuantityString(R.plurals.widget_open_windows, total, total)
        return tappable(box, front, context.getString(R.string.widget_open_pill_desc, front.app.name, windows))
    }

    /** Une ligne de la Colonne : icône et nom de l'appli, son nombre de fenêtres à droite s'il y en a plusieurs. */
    private fun row(entry: OpenWindowsSummary.Entry): View {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(8), 0, dp(8), 0)
            addView(icon(entry.app), LinearLayout.LayoutParams(dp(LIST_ICON_DP), dp(LIST_ICON_DP)))
            addView(single(label(14f, R.attr.c_text)).apply { text = entry.app.name }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
            if (entry.windows > 1) {
                addView(label(13f, R.attr.c_text_dim).apply { text = context.getString(R.string.widget_open_times, entry.windows) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }
        }
        val windows = context.resources.getQuantityString(R.plurals.widget_open_windows, entry.windows, entry.windows)
        return tappable(line, entry, context.getString(R.string.widget_open_row_desc, describe(entry), windows))
    }

    /** « Mettre Chrome devant », ou « Discord, au premier plan » : le premier plan n'est pas dit par le seul trait. */
    private fun describe(entry: OpenWindowsSummary.Entry) =
        context.getString(if (entry.front) R.string.widget_open_front_desc else R.string.widget_open_focus, entry.app.name)

    /**
     * Toucher met la fenêtre devant, puis relit la liste quand l'ordinateur a fini (sa réponse si elle la contient,
     * sinon après son animation) ; l'aperçu du catalogue ne touche pas l'ordinateur.
     */
    private fun tappable(view: View, entry: OpenWindowsSummary.Entry, description: String): View = view.apply {
        contentDescription = description
        if (sample) return@apply
        setOnClickListener {
            val target = OpenWindowsSummary.target(entry.app) ?: return@setOnClickListener
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action?.cancel()
            action = scope.launch {
                runCatchingCancellable { BackendClient(context).windows.openWindowAction(entry.app, target, "focus") }
                    .onSuccess { list ->
                        if (list != null) {
                            data = list
                            render()
                        } else {
                            delay(SETTLE_MS)
                            refresh()
                        }
                    }
                    .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun icon(app: OpenApp) = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_deck)
        setColorFilter(color(R.attr.c_text_dim))
        fun show(bitmap: Bitmap) {
            clearColorFilter()
            setImageBitmap(bitmap)
        }
        if (sample) return@apply
        IconCache.peekApp(app.name)?.let(::show) ?: scope.launch { IconCache.loadApp(BackendClient(context), app.name)?.let(::show) }
    }

    private fun note(text: String) = label(14f, R.attr.c_text_dim).apply {
        this.text = text
        gravity = Gravity.CENTER
    }

    private fun label(sp: Float, colorAttr: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color(colorAttr))
    }

    private fun single(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun color(attr: Int) = themeColor(context, attr)

    /** La place du résumé : dans un `apply` sur une vue neuve, `width` et `height` seraient les siens (encore 0). */
    private val room get() = this

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val POLL_MS = 5_000L

        /** Attente de l'animation de l'ordinateur avant de relire, comme la vue des écrans. */
        const val SETTLE_MS = 1_500L
        const val PILL_ICON_DP = 56
        const val ICON_DP = 40
        const val FRONT_MARK_DP = 20
        const val LIST_ICON_DP = 28
        const val TIGHT_TOP_DP = 12
        const val TIGHT_ICON_DP = 20
        const val TIGHT_INITIAL_SP = 16f
    }
}
