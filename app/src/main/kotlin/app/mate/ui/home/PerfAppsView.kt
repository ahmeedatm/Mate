package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.net.AppWarn
import app.mate.net.PcApp
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * « Ce qui consomme » du widget Performances (maquette B choisie par Eliot) : le souci du moment dans un bandeau, puis les
 * applis les plus gourmandes, chacune avec une barre processeur et une barre mémoire ; une appli qui abuse porte un
 * « processeur » ou « mémoire » précédé de l'icône d'alerte (l'alerte ne repose pas sur la seule couleur). Faute de hauteur, une appli tient sur
 * une ligne (nom, %, mémoire) et seules celles qui rentrent sont montrées. Sert aussi de contenu au panneau des petites formes.
 */
@SuppressLint("ViewConstructor")
class PerfAppsView(context: Context, withTitle: Boolean) : LinearLayout(context) {
    private val title = text(14f, R.attr.c_text_dim, R.attr.c_font_medium).apply {
        setText(R.string.perf_apps_title)
        visibility = if (withTitle) View.VISIBLE else View.GONE
    }
    private val banner = text(15f, R.attr.c_text, R.attr.c_font_medium).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(themeColor(context, R.attr.c_accent_soft))
        }
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val message = text(16f, R.attr.c_text_dim).apply { visibility = View.GONE }
    private val rows = List(MAX_ROWS) { Row(context) }
    private var compact = false
    private var apps: List<PcApp> = emptyList()

    /** Place reçue au dernier `fit` (px) ; hauteur 0 : pas de limite (panneau qui défile). */
    private var room = 0
    private var roomWidth = 0

    init {
        orientation = VERTICAL
        addView(title)
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        rows.forEach { addView(it.box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    /**
     * Nouvelle liste : `apps` null avec `note` (indisponible, compagnon à mettre à jour, erreur) ; `issue` : le souci du
     * moment, déjà écrit ; `stale` : dernière liste connue, grisée (pas d'alerte sur des valeurs anciennes).
     */
    fun bind(apps: List<PcApp>?, issue: String?, note: String?, stale: Boolean) {
        this.apps = apps.orEmpty()
        val shownIssue = issue?.takeIf { !stale }.orEmpty()
        banner.setWarned(shownIssue, shownIssue.isNotEmpty())
        banner.shown(shownIssue.isNotEmpty())
        message.set(note ?: if (apps != null && apps.isEmpty()) context.getString(R.string.perf_apps_empty) else "")
        message.shown(message.text.isNotEmpty())
        rows.forEachIndexed { i, row -> this.apps.getOrNull(i)?.let { row.bind(it, stale) } }
        layoutRows()
    }

    /** Place disponible (px) : choisit lignes détaillées ou compactes, et combien d'applis montrer. */
    fun fit(height: Int, width: Int) {
        room = height
        roomWidth = width
        layoutRows()
    }

    private fun layoutRows() {
        val count = apps.size.coerceAtMost(MAX_ROWS)
        val fixed = listOf(title, banner, message).filter { it.visibility == View.VISIBLE }.sumOf { natural(it) + context.dp(8) }
        val detailed = rows[0].height(false, roomWidth)
        val single = rows[0].height(true, roomWidth)
        val left = room - fixed
        compact = room > 0 && left < detailed * count
        val shown = if (room <= 0) count else (left / (if (compact) single else detailed)).coerceIn(if (count > 0) 1 else 0, count)
        rows.forEachIndexed { i, row ->
            row.compact(compact)
            row.box.shown(i < shown)
        }
    }

    /** Une appli : son nom (et ce qu'elle prend en trop), puis processeur et mémoire en barres ; ou tout sur une ligne. */
    private inner class Row(context: Context) {
        val name = text(18f, R.attr.c_text, R.attr.c_font_medium).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val warn = text(13f, R.attr.c_accent, R.attr.c_font_medium).apply { isSingleLine = true }
        val brief = text(15f, R.attr.c_text_dim).apply {
            isSingleLine = true
            fontFeatureSettings = "tnum"
        }
        // Piste sur la surface relevée : `c_stroke` est presque aussi foncé que le remplissage dans le thème Contraste.
        val cpuBar = PerfBar(context, themeColor(context, R.attr.c_surface_raised), themeColor(context, R.attr.c_text_dim))
        val ramBar = PerfBar(context, themeColor(context, R.attr.c_surface_raised), themeColor(context, R.attr.c_text_dim))
        val cpuValue = value()
        val ramValue = value()
        val cpuLine = meter(R.string.perf_cpu, cpuBar, cpuValue)
        val ramLine = meter(R.string.perf_ram, ramBar, ramValue)
        val head = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(warn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
            addView(brief, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12) })
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, context.dp(8), 0, context.dp(8))
            addView(head)
            addView(cpuLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
            addView(ramLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        private fun value() = text(14f, R.attr.c_text_dim).apply {
            gravity = Gravity.END
            isSingleLine = true
            fontFeatureSettings = "tnum"
        }

        private fun meter(label: Int, bar: PerfBar, value: TextView) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(14f, R.attr.c_text_dim).apply { setText(label) }, LayoutParams(context.dp(METER_LABEL), LayoutParams.WRAP_CONTENT))
            addView(bar, LayoutParams(0, context.dp(BAR_THICKNESS), 1f).apply { marginEnd = context.dp(12) })
            addView(value, LayoutParams(context.dp(METER_VALUE), LayoutParams.WRAP_CONTENT))
        }

        fun bind(app: PcApp, stale: Boolean) {
            val cpuWarn = app.warn == AppWarn.CPU && !stale
            val ramWarn = app.warn == AppWarn.RAM && !stale
            val cpu = context.getString(R.string.perf_app_cpu, app.cpu)
            val mem = memText(context, app.memMb)
            name.set(app.name)
            name.setTextColor(themeColor(context, if (stale) R.attr.c_text_faint else R.attr.c_text))
            val warnWord = when {
                cpuWarn -> context.getString(R.string.perf_app_warn_cpu)
                ramWarn -> context.getString(R.string.perf_app_warn_ram)
                else -> ""
            }
            warn.setWarned(warnWord, warnWord.isNotEmpty())
            warn.shown(warnWord.isNotEmpty())
            brief.set(context.getString(R.string.perf_app_brief, cpu, mem))
            cpuValue.set(cpu)
            ramValue.set(mem)
            val calm = themeColor(context, if (stale) R.attr.c_graphic else R.attr.c_text_dim)
            val alert = themeColor(context, R.attr.c_accent)
            cpuBar.progress = app.cpu / 100f
            cpuBar.color = if (cpuWarn) alert else calm
            ramBar.progress = app.memPercent / 100f
            ramBar.color = if (ramWarn) alert else calm
            cpuValue.setTextColor(if (cpuWarn) alert else themeColor(context, R.attr.c_text_dim))
            ramValue.setTextColor(if (ramWarn) alert else themeColor(context, R.attr.c_text_dim))
            val alertWord = warnWord.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.perf_app_warn_desc, it) }.orEmpty()
            box.contentDescription = listOf(app.name, alertWord, context.getString(R.string.perf_app_desc, app.cpu, mem))
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        }

        fun compact(on: Boolean) {
            brief.shown(on)
            cpuLine.shown(!on)
            ramLine.shown(!on)
        }

        /** Hauteur d'une ligne, compacte ou détaillée, pour `width` px de large. */
        fun height(compact: Boolean, width: Int): Int {
            val was = cpuLine.visibility == View.GONE
            compact(compact)
            box.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            compact(was)
            return box.measuredHeight
        }
    }

    private fun text(size: Float, colorId: Int, font: Int = R.attr.c_font_regular) = TextView(context).apply {
        textSize = size
        setTextColor(themeColor(context, colorId))
        typeface = themeFont(context, font)
    }

    private fun natural(view: View): Int {
        view.measure(MeasureSpec.makeMeasureSpec(roomWidth.coerceAtLeast(1), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
        return view.measuredHeight
    }

    private fun View.shown(on: Boolean) {
        val target = if (on) View.VISIBLE else View.GONE
        if (visibility != target) visibility = target
    }

    private fun TextView.set(value: String) {
        if (text.toString() != value) text = value
    }

    companion object {
        const val MAX_ROWS = 5
        private const val METER_LABEL = 44
        private const val METER_VALUE = 64
        private const val BAR_THICKNESS = 6

        /** « 614 Mo » ou « 3,1 Go ». */
        fun memText(context: Context, memMb: Int): String = perfMem(memMb).let {
            context.getString(if (it.giga) R.string.perf_mem_gb else R.string.perf_mem_mb, perfNumber(it.value))
        }
    }
}

/**
 * Écrit `value`, précédé de l'icône d'alerte (`ic_warning`, teinte `c_accent`, à la taille de la police) si `on`. L'icône
 * est dans le texte (`ImageSpan`) : elle suit les mots, même centrés ou coupés, et l'alerte n'est dite ni par un caractère
 * glissé dans le texte (anti-motif de la DA), ni par la seule couleur. Rien n'est réécrit si rien ne change.
 */
internal fun TextView.setWarned(value: String, on: Boolean) {
    val plain = if (on) "$ICON_SLOT $value" else value
    if (text.toString() == plain) return
    if (!on) {
        text = value
        return
    }
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_warning)?.mutate()?.apply {
        setTint(themeColor(context, R.attr.c_accent))
        val size = (textSize * ICON_SCALE).toInt()
        setBounds(0, 0, size, size)
    }
    text = SpannableString(plain).apply { icon?.let { setSpan(ImageSpan(it, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
}

/** Caractère remplacé par l'icône. */
private const val ICON_SLOT = "\uFFFC"

/** Icône un peu plus petite que le corps du texte, posée sur la ligne de base. */
private const val ICON_SCALE = 0.85f
