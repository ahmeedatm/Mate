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
 * sous celle du premier plan ; Colonne : la liste des applis (icône, nom, nombre de fenêtres), comme Touches et Applis.
 * Toucher une appli met sa fenêtre devant. Relu toutes les 5 s, seulement page à l'écran.
 */
class OpenWindowsCompact(context: Context, private val scope: CoroutineScope, private val family: ShapeFamily) : FrameLayout(context) {
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
        addView(icons(apps, OpenWindowsSummary.total(current)), LayoutParams(MATCH, MATCH))
    }

    private fun icons(apps: List<OpenWindowsSummary.Entry>, total: Int): View = when (family) {
        ShapeFamily.PILL -> FrameLayout(context).apply {
            addView(tappable(icon(apps.first().app, PILL_ICON_DP), apps.first().app), LayoutParams(dp(PILL_ICON_DP), dp(PILL_ICON_DP), Gravity.CENTER))
            addView(label(14f, R.attr.c_text_dim).apply { text = total.toString() }, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
        }
        ShapeFamily.ROW -> LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val fit = OpenWindowsSummary.fit(room.width, dp(ICON_CELL_DP), dp(GAP_DP))
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
        else -> column(apps) { entry -> row(entry.app, countText(entry.windows)) }
    }

    /** Liste sans défilement : autant de lignes que la hauteur en tient. */
    private fun <T> column(items: List<T>, line: (T) -> View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val fit = OpenWindowsSummary.fit(room.height, dp(LIST_ROW_DP), dp(LIST_GAP_DP))
        items.take(fit).forEachIndexed { i, item ->
            addView(line(item), LinearLayout.LayoutParams(MATCH, dp(LIST_ROW_DP)).apply { if (i > 0) topMargin = dp(LIST_GAP_DP) })
        }
    }

    /** Une ligne de la Colonne : icône et nom de l'appli, son nombre de fenêtres à droite s'il y en a plusieurs. */
    private fun row(app: OpenApp, count: String?): View {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(8), 0, dp(8), 0)
            addView(icon(app, LIST_ICON_DP), LinearLayout.LayoutParams(dp(LIST_ICON_DP), dp(LIST_ICON_DP)))
            addView(single(label(14f, R.attr.c_text)).apply { text = app.name }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
            count?.let { addView(label(13f, R.attr.c_text_dim).apply { text = it }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) }) }
        }
        return tappable(line, app)
    }

    private fun countText(windows: Int) = if (windows > 1) "×$windows" else null

    /** Toucher met la fenêtre devant ; l'aperçu du catalogue ne touche pas l'ordinateur. */
    private fun tappable(view: View, app: OpenApp): View = view.apply {
        contentDescription = context.getString(R.string.widget_open_focus, app.name)
        if (sample) return@apply
        setOnClickListener {
            val target = OpenWindowsSummary.target(app) ?: return@setOnClickListener
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

    /** La place du résumé : dans un `apply` sur une vue neuve, `width` et `height` seraient les siens (encore 0). */
    private val room get() = this

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
    }
}
