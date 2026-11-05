package app.mate.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.ComputerWindow
import app.mate.net.OpenApp
import app.mate.net.OpenWindows
import app.mate.net.runCatchingCancellable
import app.mate.ui.IconCache
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fenêtres ouvertes en Pastille, Ligne et Colonne (#183) : un résumé sans défilement ni glisser ([OpenWindowsSummary]).
 * Toucher une appli met sa fenêtre devant. Relu toutes les 5 s, seulement page à l'écran.
 * [variant] : provisoire, le temps qu'Ahmed choisisse (A icônes, B chiffre, C titres).
 */
class OpenWindowsCompact(context: Context, private val scope: CoroutineScope, private val family: ShapeFamily) : FrameLayout(context) {
    var variant = "A"
        set(value) {
            field = value
            render()
        }

    private var data: OpenWindows? = null
    private var status: String = context.getString(R.string.open_loading)
    private var poll: Job? = null
    private var sample = false
    private var drawnWidth = 0
    private var drawnHeight = 0

    /** Aperçu du catalogue : des données fixes, ni réseau ni boucle. */
    fun showSample(sample: OpenWindows) {
        this.sample = true
        data = sample
        render()
    }

    fun setActive(active: Boolean) {
        poll?.cancel()
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
        if (w != drawnWidth || h != drawnHeight) post { render() }
    }

    private fun render() {
        drawnWidth = width
        drawnHeight = height
        removeAllViews()
        val current = data ?: return addView(note(status), LayoutParams(MATCH, WRAP, Gravity.CENTER))
        val apps = OpenWindowsSummary.apps(current)
        if (apps.isEmpty()) return addView(note(context.getString(R.string.widget_open_none_short)), LayoutParams(MATCH, WRAP, Gravity.CENTER))
        val total = OpenWindowsSummary.total(current)
        val content = when (variant) {
            "B" -> numberFirst(apps, total)
            "C" -> titles(current, apps)
            else -> icons(apps, total)
        }
        addView(content, LayoutParams(MATCH, MATCH))
    }

    // ---- A : icônes (comme Touches et Applis) ----

    private fun icons(apps: List<OpenWindowsSummary.Entry>, total: Int): View = when (family) {
        ShapeFamily.PILL -> FrameLayout(context).apply {
            addView(tappable(icon(apps.first().app, PILL_ICON_DP), apps.first().app), LayoutParams(dp(PILL_ICON_DP), dp(PILL_ICON_DP), Gravity.CENTER))
            addView(label(14f, R.attr.c_text_dim).apply { text = total.toString() }, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
        }
        ShapeFamily.ROW -> LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val fit = OpenWindowsSummary.fit(width, dp(ICON_CELL_DP), dp(GAP_DP))
            apps.take(fit).forEachIndexed { i, entry ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(icon(entry.app, ICON_DP), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
                    // Le premier plan : un trait sous l'icône (en plus du nom lu par TalkBack), pas une couleur seule.
                    addView(View(context).apply {
                        setBackgroundColor(color(R.attr.c_accent))
                        visibility = if (entry.front) VISIBLE else INVISIBLE
                    }, LinearLayout.LayoutParams(dp(FRONT_MARK_DP), dp(3)).apply { topMargin = dp(6) })
                }
                addView(tappable(cell, entry.app), LinearLayout.LayoutParams(dp(ICON_CELL_DP), MATCH).apply { if (i > 0) marginStart = dp(GAP_DP) })
            }
        }
        else -> column(apps) { entry -> row(entry.app, entry.app.name, null, countText(entry.windows)) }
    }

    // ---- B : le nombre d'abord ----

    private fun numberFirst(apps: List<OpenWindowsSummary.Entry>, total: Int): View {
        val number = label(if (family == ShapeFamily.PILL) 40f else 34f, R.attr.c_text).apply {
            typeface = themeFont(context, R.attr.c_font_light)
            text = total.toString()
        }
        val unit = label(13f, R.attr.c_text_dim).apply { text = context.resources.getQuantityString(R.plurals.widget_open_count, total, total).substringAfter(' ') }
        return when (family) {
            ShapeFamily.PILL -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(number)
                addView(unit)
            }
            ShapeFamily.ROW -> LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(number)
                addView(unit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                addView(single(label(14f, R.attr.c_text_dim)).apply {
                    text = context.getString(R.string.widget_open_front, apps.first().app.name)
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            }
            else -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(number)
                addView(unit, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })
                val room = OpenWindowsSummary.fit(height - dp(NUMBER_BLOCK_DP), dp(ICON_CELL_DP), dp(GAP_DP))
                apps.take(room).forEach { entry ->
                    addView(tappable(icon(entry.app, ICON_DP), entry.app), LinearLayout.LayoutParams(dp(ICON_CELL_DP), dp(ICON_CELL_DP)).apply { topMargin = dp(GAP_DP) })
                }
            }
        }
    }

    // ---- C : les titres des fenêtres ----

    private fun titles(current: OpenWindows, apps: List<OpenWindowsSummary.Entry>): View {
        val front = apps.first().app
        val frontWindow = OpenWindowsSummary.target(front)
        return when (family) {
            ShapeFamily.PILL -> tappable(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(icon(front, ICON_DP), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
                addView(single(label(13f, R.attr.c_text)).apply {
                    text = front.name
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }, front)
            ShapeFamily.ROW -> row(front, front.name, frontWindow, null)
            else -> column(OpenWindowsSummary.windows(current)) { (app, window) -> row(app, app.name, window, null, window) }
        }
    }

    // ---- Briques ----

    /** Liste sans défilement : autant de lignes que la hauteur en tient. */
    private fun <T> column(items: List<T>, line: (T) -> View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val fit = OpenWindowsSummary.fit(height, dp(LIST_ROW_DP), dp(LIST_GAP_DP))
        items.take(fit).forEachIndexed { i, item ->
            addView(line(item), LinearLayout.LayoutParams(MATCH, dp(LIST_ROW_DP)).apply { if (i > 0) topMargin = dp(LIST_GAP_DP) })
        }
    }

    /** Une ligne : icône, nom de l'appli et titre de la fenêtre (ou un nombre de fenêtres à droite). */
    private fun row(app: OpenApp, name: String, window: ComputerWindow?, count: String?, target: ComputerWindow? = null): View {
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(single(label(14f, R.attr.c_text)).apply { text = name })
            window?.let { w -> addView(single(label(13f, R.attr.c_text_dim)).apply { text = w.title.ifBlank { context.getString(R.string.windows_untitled) } }) }
        }
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(8), 0, dp(8), 0)
            addView(icon(app, LIST_ICON_DP), LinearLayout.LayoutParams(dp(LIST_ICON_DP), dp(LIST_ICON_DP)))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
            count?.let { addView(label(13f, R.attr.c_text_dim).apply { text = it }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) }) }
        }
        return tappable(line, app, target)
    }

    private fun countText(windows: Int) = if (windows > 1) "×$windows" else null

    /** Toucher met la fenêtre devant ; l'aperçu du catalogue ne touche pas l'ordinateur. */
    private fun tappable(view: View, app: OpenApp, window: ComputerWindow? = null): View = view.apply {
        contentDescription = context.getString(R.string.widget_open_focus, app.name)
        if (sample) return@apply
        setOnClickListener {
            val target = window ?: OpenWindowsSummary.target(app) ?: return@setOnClickListener
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            scope.launch {
                runCatchingCancellable { BackendClient(context).windows.openWindowAction(app, target, "focus") }
                    .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
                refresh()
            }
        }
    }

    private fun icon(app: OpenApp, sizeDp: Int) = ImageView(context).apply {
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
        minimumWidth = dp(sizeDp)
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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val POLL_MS = 5_000L
        const val PILL_ICON_DP = 56
        const val ICON_DP = 40
        const val ICON_CELL_DP = 64
        const val GAP_DP = 8
        const val FRONT_MARK_DP = 20
        const val LIST_ROW_DP = 56
        const val LIST_GAP_DP = 4
        const val LIST_ICON_DP = 28

        /** Hauteur du nombre et de son unité en haut de la Colonne (variante B). */
        const val NUMBER_BLOCK_DP = 72
    }
}
