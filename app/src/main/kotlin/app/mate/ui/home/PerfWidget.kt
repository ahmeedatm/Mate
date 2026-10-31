package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HttpException
import app.mate.net.PcPerf
import app.mate.ui.ProgressRing
import app.mate.ui.errorText
import app.mate.ui.showImmersive
import app.mate.ui.themeFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Performances de l'ordinateur en direct : processeur, mémoire et carte graphique (avec sa température et son
 * ventilateur quand elle les donne), relevés toutes les 2 s tant que la page est à l'écran.
 * Moyen : quatre chiffres et la courbe de la dernière minute ; grand : trois anneaux ; bandeau : trois barres.
 * DA Console douce : chiffres fins et graphiques neutres, le rouge d'alerte seulement pour une alerte ; un relevé remplace les
 * valeurs sans animation (comme le chrono du Focus). Les tailles suivent la hauteur de la ligne de l'Accueil (lignes
 * inégales) : sur une ligne basse, le titre et la courbe laissent la place aux chiffres, rangés sur une seule ligne.
 */
class PerfWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_perf)
    private val note = label(14f, R.attr.c_text_faint).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
    }
    private val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title)
        addView(note, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }
    private val unavailable = label(16f, R.attr.c_text_dim).apply {
        setText(R.string.perf_unavailable)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val body = FrameLayout(context)
    override val view: LinearLayout = FittingCard(context) { height, width -> fitter(height, width) }.asCard().apply {
        orientation = LinearLayout.VERTICAL
        addView(header)
        addView(unavailable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(HEADER_GAP) })
    }

    private val history = PerfHistory()
    private var last: PcPerf? = null
    private var error: String? = null
    private var notAvailable = false
    private var job: Job? = null

    /** Faux depuis que la page a été quittée, jusqu'au premier relevé réussi : `last` date d'avant la pause. */
    private var fresh = false

    // Vues de la taille courante, refaites par applySize.
    private var cpu = Reading(View(context), TextView(context), PerfSeries.CPU)
    private var gpu = Reading(View(context), TextView(context), PerfSeries.GPU)
    private var ram = Reading(View(context), TextView(context), PerfSeries.RAM)
    private var ramGb: TextView? = null
    private var extras: TextView? = null

    /** Vrai si la courbe a quelque chose à tracer (processeur ou carte graphique coché). */
    private var curve = true
    private var spark: PerfSparkline? = null

    /** Infos cochées pour l'ordinateur actif, relues à chaque mesure (réglages changés, autre ordinateur). */
    private var choice: Set<PerfOption> = PerfChoice.DEFAULT

    /** Met la vue courante à la taille de la place intérieure de la tuile (hauteur, largeur), à chaque mesure. */
    private var fitter: (Int, Int) -> Unit = { _, _ -> }

    // Mesures d'un chiffre (Figtree 300 à chiffres tabulaires, sans marge de police), par pixel de corps.
    private val figurePaint = Paint().apply {
        typeface = themeFont(context, R.attr.c_font_light)
        textSize = 100f
        fontFeatureSettings = "tnum"
    }
    private val figureLine = figurePaint.fontMetrics.let { (it.descent - it.ascent) / 100f }
    private val figureWidth = figurePaint.measureText(WIDEST) / 100f
    private val headerHeight by lazy { natural(header) }
    private val captionHeight by lazy { natural(caption("CPU")) }

    /**
     * Un relevé affiché : sa boîte (masquée sans mesure), son chiffre, sa série (qui donne sa couleur) et, pour une jauge
     * ou une barre, de quoi la remplir.
     */
    private class Reading(val box: View, val value: TextView, val series: PerfSeries, val meter: ((Float, Int) -> Unit)? = null)

    /** Une info en texte (« GPU 81 °C ») : `tone` est l'attribut de couleur de son texte. */
    private class Extra(val text: String, val tone: Int)

    override val hasSettings get() = true

    override fun applySize() {
        title.text = context.getString(R.string.perf_title, Computers.active(context)?.name ?: context.getString(R.string.computer_generic))
        body.removeAllViews()
        ramGb = null
        extras = null
        spark = null
        body.addView(when (placed.size) {
            WidgetSize.L -> gauges()
            WidgetSize.W -> bars()
            else -> figures()
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        render()
    }

    /**
     * Active : une seule boucle de relevés ; inactive (autre page, écran éteint) : aucun relevé, la courbe est oubliée
     * et les dernières valeurs restent grisées jusqu'au relevé suivant.
     */
    override fun setActive(active: Boolean) {
        if (!active) {
            job?.cancel()
            job = null
            history.clear()
            fresh = false
            render()
        } else if (!notAvailable && job?.isActive != true) {
            job = host.scope.launch {
                while (isActive && !notAvailable) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    /** Relevé sur le thread principal (`host.scope`), le réseau passe par le thread d'entrées-sorties du client. */
    private suspend fun refresh() {
        try {
            val perf = BackendClient(context).system.perf()
            history.add(perf.cpu, perf.gpu?.usage)
            last = perf
            error = null
            fresh = true
        } catch (e: HttpException) {
            // 501 : cet ordinateur ne sait pas lire ses performances.
            if (e.code == 501) notAvailable = true else error = context.errorText(e)
        } catch (e: Exception) {
            error = context.errorText(e)
        }
        render()
    }

    // ---- Taille moyenne : quatre chiffres et la courbe ----

    /** Une colonne du Moyen : libellé au-dessus du chiffre, ou devant lui sur une ligne basse ; `legend` : ton du libellé. */
    private class Column(val box: LinearLayout, val name: TextView, val value: TextView, val legend: Int)

    private fun figures(): View {
        val row = LinearLayout(context)
        val columns = mutableListOf<Column>()
        fun column(name: String, legend: Int): Column {
            val caption = caption(name)
            val value = figure(Gravity.START)
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(caption)
                addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return Column(box, caption, value, legend).also { columns += it }
        }
        // Libellés CPU et GPU du ton de leur trait : ils servent de légende à la courbe.
        val cpuColumn = column(context.getString(R.string.perf_cpu), perfColorAttr(PerfSeries.CPU, PerfMark.LINE, PerfLevel.NORMAL))
        val gpuColumn = column(context.getString(R.string.perf_gpu), perfColorAttr(PerfSeries.GPU, PerfMark.LINE, PerfLevel.NORMAL))
        val ramColumn = column(context.getString(R.string.perf_ram), R.attr.c_text_dim)
        cpu = Reading(cpuColumn.box, cpuColumn.value, PerfSeries.CPU)
        gpu = Reading(gpuColumn.box, gpuColumn.value, PerfSeries.GPU)
        ram = Reading(ramColumn.box, ramColumn.value, PerfSeries.RAM)
        val line = extrasView(14f, maxLines = 1)
        extras = line
        val sparkline = PerfSparkline(context, history.capacity)
        spark = sparkline
        val nameGap = dp(8)
        fitter = { height, width ->
            val hasExtras = line.text.isNotEmpty()
            val extrasRoom = if (hasExtras) naturalHeight(line, width) + dp(CURVE_GAP) else 0
            val fixed = headerHeight + dp(HEADER_GAP) + captionHeight + dp(CURVE_GAP) + dp(CURVE_MIN) + extrasRoom
            val compact = height < fixed + figureLine * sp(MIN_FIGURE_SP)
            showHeader(!compact)
            sparkline.shown(!compact && curve)
            line.shown(hasExtras && !compact)
            val count = columns.count { it.box.visibility != View.GONE }.coerceAtLeast(1)
            val columnWidth = width / count - dp(COLUMN_GAP)
            val nameWidth = if (compact) columns.maxOf { naturalWidth(it.name) } + nameGap else 0
            val room = if (compact) height else height - fixed
            val size = minOf(sp(MAX_FIGURE_SP), room / figureLine, (columnWidth - nameWidth) / figureWidth)
            for (c in columns) {
                c.value.fontPx(size)
                // Sans courbe, plus de légende : tous les libellés au même ton.
                c.name.setTextColor(color(if (compact) R.attr.c_text_dim else c.legend))
                if ((c.box.orientation == LinearLayout.HORIZONTAL) != compact) {
                    c.box.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    c.name.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (compact) marginEnd = nameGap
                    }
                    c.value.layoutParams =
                        if (compact) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(row)
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(CURVE_GAP) })
            addView(sparkline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(CURVE_GAP) })
        }
    }

    // ---- Grande taille : trois anneaux, la mémoire en Go, puis température et ventilateur ----

    private fun gauges(): View {
        val rings = LinearLayout(context)
        val columns = mutableListOf<View>()
        val values = mutableListOf<TextView>()

        /** Une colonne : l'anneau, puis une ligne dessous (« 20,1 / 32 Go » pour la RAM, vide ailleurs pour aligner les anneaux). */
        fun gauge(name: Int, series: PerfSeries, under: TextView): Reading {
            val ring = ProgressRing(context).apply { trackColor = color(R.attr.c_stroke) }
            val value = figure(Gravity.CENTER)
            val box = FrameLayout(context).apply {
                addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(caption(context.getString(name)).apply { gravity = Gravity.CENTER })
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(under.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            rings.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            columns += column
            values += value
            return Reading(column, value, series) { fraction, tint -> ring.progress = fraction; ring.color = tint }
        }
        val gb = caption(" ", R.attr.c_text_faint)
        ramGb = gb
        cpu = gauge(R.string.perf_cpu, PerfSeries.CPU, caption(" "))
        gpu = gauge(R.string.perf_gpu, PerfSeries.GPU, caption(" "))
        ram = gauge(R.string.perf_ram, PerfSeries.RAM, gb)
        val line = extrasView(16f, maxLines = 3).apply { gravity = Gravity.CENTER }
        extras = line
        val minStroke = dp(4).toFloat()
        fitter = { height, width ->
            showHeader(true)
            // Pas d'info en texte : hauteur nulle, et plus d'écart.
            line.shown(line.text.isNotEmpty())
            val chipsHeight = if (line.text.isEmpty()) 0 else naturalHeight(line, width) + dp(CHIPS_GAP)
            val count = columns.count { it.visibility != View.GONE }.coerceAtLeast(1)
            val ringRoom = height - headerHeight - dp(HEADER_GAP) - dp(RING_TOP) - captionHeight - dp(4) - chipsHeight
            val diameter = minOf(width / count - dp(8), ringRoom).toFloat()
            val radius = diameter / 2 - ProgressRing.strokeFor(diameter, minStroke) - dp(RING_INSET)
            val size = minOf(sp(MAX_FIGURE_SP), figureInCircle(radius, captionHeight.toFloat()))
            values.forEach { it.fontPx(size) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(rings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(RING_TOP) })
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(CHIPS_GAP) })
        }
    }

    /**
     * Plus grand corps (px) d'un chiffre « 100% » posé sur un libellé de `below` px, le bloc centré dans un cercle de
     * rayon `radius` : ses coins du haut, à (largeur / 2, hauteur / 2) du centre, restent dans le cercle.
     */
    private fun figureInCircle(radius: Float, below: Float): Float {
        // (w·s / 2)² + ((l·s + below) / 2)² ≤ r², résolu en s.
        val a = (figureWidth * figureWidth + figureLine * figureLine) / 4
        val b = figureLine * below / 2
        val c = below * below / 4 - radius * radius
        val disc = b * b - 4 * a * c
        return if (disc <= 0f) 0f else (-b + sqrt(disc)) / (2 * a)
    }

    // ---- Bandeau : trois barres, température et ventilateur à droite ----

    private fun bars(): View {
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val names = mutableListOf<TextView>()
        val values = mutableListOf<TextView>()
        fun bar(name: Int, series: PerfSeries): Reading {
            val meter = PerfBar(context, color(R.attr.c_stroke), color(R.attr.c_text_dim))
            val caption = caption(context.getString(name))
            val value = figure(Gravity.END)
            val box = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(caption)
                addView(meter, LinearLayout.LayoutParams(0, dp(BAR_THICKNESS), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
                addView(value)
            }
            rows.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            names += caption
            values += value
            return Reading(box, value, series) { fraction, tint -> meter.progress = fraction; meter.color = tint }
        }
        cpu = bar(R.string.perf_cpu, PerfSeries.CPU)
        gpu = bar(R.string.perf_gpu, PerfSeries.GPU)
        ram = bar(R.string.perf_ram, PerfSeries.RAM)
        // Libellés de même largeur : les barres commencent toutes au même endroit.
        val nameWidth = names.maxOf { naturalWidth(it) }
        names.forEach { it.layoutParams = LinearLayout.LayoutParams(nameWidth, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val line = extrasView(14f, maxLines = 4)
        extras = line
        val boxes = listOf(cpu.box, gpu.box, ram.box)
        fitter = { height, width ->
            val compact = height < headerHeight + dp(HEADER_GAP) + boxes.size * maxOf(captionHeight.toFloat(), figureLine * sp(MIN_BAR_SP))
            showHeader(!compact)
            // Ligne basse : les trois barres côte à côte, les infos en texte sur deux lignes au plus.
            if ((rows.orientation == LinearLayout.HORIZONTAL) != compact) {
                rows.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                boxes.forEachIndexed { i, box ->
                    box.layoutParams =
                        if (compact) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(BAR_GROUP_GAP) }
                        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
            }
            line.maxLines = if (compact) 2 else 4
            line.shown(line.text.isNotEmpty())
            val count = boxes.count { it.visibility != View.GONE }.coerceAtLeast(1)
            val fitted = if (compact) {
                // Le chiffre laisse au moins 48 dp à sa barre.
                val group = (width - (if (line.text.isEmpty()) 0 else naturalWidth(line) + dp(CHIPS_START))) / count - dp(BAR_GROUP_GAP)
                minOf(height / figureLine, (group - nameWidth - dp(24) - dp(BAR_MIN)) / figureWidth)
            } else {
                (height - headerHeight - dp(HEADER_GAP)).toFloat() / count * BAR_FIGURE_SHARE / figureLine
            }
            val size = minOf(sp(MAX_BAR_SP), fitted)
            for (value in values) {
                value.fontPx(size)
                // Largeur fixe, celle de « 100% » : un nouveau chiffre ne refait pas la mise en page.
                val w = ceil(figureWidth * value.textSize).toInt()
                if (value.layoutParams?.width != w) value.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        return LinearLayout(context).apply {
            addView(rows, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(CHIPS_START) })
        }
    }

    /** Zone de texte des infos qui n'ont ni anneau ni barre (température, fréquence, disque…), remplie par `render`. */
    private fun extrasView(size: Float, maxLines: Int) = label(size, R.attr.c_text_dim).apply {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        fontFeatureSettings = "tnum"
        visibility = View.GONE
    }

    /** Titre (et message d'erreur) au-dessus du contenu, ou masqués sur une ligne basse. */
    private fun showHeader(on: Boolean) {
        header.shown(on)
        val params = body.layoutParams as ViewGroup.MarginLayoutParams
        val margin = if (on) dp(HEADER_GAP) else 0
        if (params.topMargin != margin) {
            params.topMargin = margin
            body.layoutParams = params
        }
    }

    // ---- Affichage de la dernière mesure ----

    private fun render() {
        choice = PerfChoice.read(placed.config, Computers.active(context)?.id.orEmpty())
        val perf = last
        // Rien de coché qui soit lisible : une phrase au lieu de libellés sans chiffre.
        val nothing = perf != null && PerfOption.entries.none { it in choice && it.readable(perf) }
        unavailable.setText(if (notAvailable) R.string.perf_unavailable else R.string.perf_none_checked)
        unavailable.shown(notAvailable || nothing)
        // Rien à montrer avant la première mesure (des libellés sans chiffre).
        body.visibility = if (notAvailable || nothing) View.GONE else if (perf == null) View.INVISIBLE else View.VISIBLE
        note.set(error.orEmpty())
        if (perf == null) return
        // Dernières valeurs grisées tant que le relevé échoue, ou au retour sur la page avant le premier relevé.
        val stale = error != null || !fresh
        val cpuOn = PerfOption.CPU in choice
        val gpuOn = PerfOption.GPU in choice && perf.gpu != null
        val ramOn = PerfOption.RAM in choice
        // Alerte écrite dans l'en-tête en plus du rouge des chiffres (rien sur des valeurs grisées) ; une erreur prime.
        if (error == null) {
            val alerts = if (stale) "" else perfAlertText(
                listOfNotNull(
                    if (cpuOn) context.getString(R.string.perf_cpu) to usageLevel(perf.cpu) else null,
                    if (gpuOn) context.getString(R.string.perf_gpu) to usageLevel(perf.gpu!!.usage) else null,
                    if (ramOn) context.getString(R.string.perf_ram) to usageLevel(perf.ramPercent) else null,
                    if (PerfOption.GPU_TEMP in choice) perf.gpu?.tempC?.let { context.getString(R.string.perf_gpu) + " °C" to tempLevel(it) } else null,
                ),
                context::getString,
            )
            note.set(alerts)
        }
        show(cpu, perf.cpu.takeIf { cpuOn }, stale)
        show(ram, perf.ramPercent.takeIf { ramOn }, stale)
        show(gpu, perf.gpu?.usage?.takeIf { gpuOn }, stale)
        ramGb?.set(context.getString(R.string.perf_ram_gb, perfNumber(perf.ramUsedGb), perfNumber(perf.ramTotalGb)))
        extras?.let { it.text = extrasText(perf, stale) }
        curve = cpuOn || gpuOn
        val gpuLevel = perf.gpu?.usage?.let { usageLevel(it) } ?: PerfLevel.NORMAL
        spark?.set(
            if (cpuOn) history.cpu else emptyList(),
            if (gpuOn) history.gpu else emptyList(),
            color(perfColorAttr(PerfSeries.CPU, PerfMark.LINE, usageLevel(perf.cpu), stale)),
            color(perfColorAttr(PerfSeries.GPU, PerfMark.LINE, gpuLevel, stale)),
        )
    }

    private fun show(reading: Reading, percent: Int?, stale: Boolean) {
        reading.box.shown(percent != null)
        if (percent == null) return
        val level = usageLevel(percent)
        reading.value.set("$percent%")
        reading.value.setTextColor(color(perfColorAttr(reading.series, PerfMark.VALUE, level, stale)))
        reading.meter?.invoke(percent / 100f, color(perfColorAttr(reading.series, PerfMark.FILL, level, stale)))
    }

    /** Les infos cochées et lisibles qui ne passent pas par un anneau ou une barre, sur une ligne (Bandeau : une par ligne). */
    private fun extrasText(perf: PcPerf, stale: Boolean): CharSequence {
        val separator = if (placed.size == WidgetSize.W) "\n" else "  ·  "
        val out = SpannableStringBuilder()
        for (extra in extras(perf, stale)) {
            if (out.isNotEmpty()) out.append(separator)
            val start = out.length
            out.append(extra.text)
            out.setSpan(ForegroundColorSpan(color(extra.tone)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun extras(perf: PcPerf, stale: Boolean): List<Extra> = PerfOption.entries.mapNotNull { option ->
        if (option !in choice || !option.readable(perf)) return@mapNotNull null
        val calm = if (stale) R.attr.c_text_faint else R.attr.c_text_dim
        val gpuName = context.getString(R.string.perf_gpu)
        when (option) {
            PerfOption.CPU_FREQ -> Extra(context.getString(R.string.perf_cpu) + " " + context.getString(R.string.perf_freq, perfNumber(perf.cpuMhz!! / 1000.0)), calm)
            PerfOption.GPU_TEMP -> perf.gpu!!.tempC!!.let { Extra("$gpuName $it °C", perfColorAttr(PerfSeries.GPU, PerfMark.VALUE, tempLevel(it), stale)) }
            PerfOption.GPU_FAN -> perf.gpu!!.fanRpm!!.let {
                // « Arrêté » est un texte tertiaire ; le ventilateur n'est jamais en alerte.
                val text = if (it == 0) context.getString(R.string.perf_fan_stopped) else context.getString(R.string.perf_fan_rpm, RPM_FORMAT.format(it))
                Extra(context.getString(R.string.perf_fan) + " " + text, if (stale || it == 0) R.attr.c_text_faint else R.attr.c_text)
            }
            PerfOption.GPU_VRAM -> Extra(context.getString(R.string.perf_vram, perfNumber(perf.gpu!!.vramUsedMb!! / 1024.0), perfNumber(perf.gpu.vramTotalMb!! / 1024.0)), calm)
            PerfOption.DISK_FREE -> Extra(context.getString(R.string.perf_disk_free, perfNumber(perf.disk!!.freeGb)), calm)
            PerfOption.DISK_ACTIVITY -> Extra(context.getString(R.string.perf_disk_activity, perf.disk!!.activity!!), calm)
            PerfOption.NET_UP -> Extra(context.getString(R.string.perf_net_up, rate(perf.net!!.upKBps)), calm)
            PerfOption.NET_DOWN -> Extra(context.getString(R.string.perf_net_down, rate(perf.net!!.downKBps)), calm)
            else -> null
        }
    }

    private fun rate(kBps: Int): String = perfRate(kBps).let {
        context.getString(if (it.mega) R.string.perf_rate_mb else R.string.perf_rate_kb, perfNumber(it.value))
    }

    // ---- Réglages : la liste à cocher ----

    /**
     * Liste des infos par groupe, cochées selon le choix de l'ordinateur actif. La mesure du moment (un relevé fait à
     * l'ouverture, la page pouvant être inactive en mode Modifier) dit lesquelles sont lisibles ; les autres sont grisées
     * avec la raison. Impossible de lire (ordinateur injoignable) : tout reste cochable.
     */
    override fun showSettings() {
        val computerId = Computers.active(context)?.id ?: return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_perf)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .showImmersive()
        fillSettings(box, computerId, last)
        host.scope.launch {
            val latest = try { BackendClient(context).system.perf() } catch (e: Exception) { return@launch }
            if (box.isAttachedToWindow) fillSettings(box, computerId, latest)
        }
    }

    private fun fillSettings(box: LinearLayout, computerId: String, perf: PcPerf?) {
        box.removeAllViews()
        box.addView(label(14f, R.attr.c_text_dim).apply { setText(R.string.perf_settings_hint) })
        var group: PerfGroup? = null
        for (option in PerfOption.entries) {
            if (option.group != group) {
                group = option.group
                box.addView(label(14f, R.attr.c_text_faint).apply {
                    setText(option.group.title)
                    setPadding(0, dp(12), 0, dp(2))
                })
            }
            val readable = perf?.let { option.readable(it) } ?: !option.soon
            val note = when {
                option.soon -> context.getString(R.string.perf_option_soon)
                !readable -> context.getString(R.string.perf_option_unreadable)
                else -> null
            }
            box.addView(CheckBox(context).apply {
                text = context.getString(option.label) + (note?.let { " ($it)" } ?: "")
                setTextColor(color(if (readable) R.attr.c_text else R.attr.c_text_faint))
                isEnabled = readable
                // Une info illisible reste dans le choix (elle reviendra si l'ordinateur la lit) mais s'affiche décochée.
                isChecked = readable && option in PerfChoice.read(placed.config, computerId)
                setOnCheckedChangeListener { _, on ->
                    val now = PerfChoice.read(placed.config, computerId)
                    PerfChoice.write(placed.config, computerId, if (on) now + option else now - option)
                    host.save()
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
    }

    // ---- Textes et mesures ----

    /** Libellé (« CPU », « Ventilateur ») : 14 sp, Figtree 400. */
    private fun caption(text: String, colorId: Int = R.attr.c_text_dim) = label(14f, colorId).apply {
        this.text = text
        isSingleLine = true
    }

    /** Grand chiffre : Figtree 300 à chiffres tabulaires, sans marge de police ; sa taille vient de `fitter`. */
    private fun figure(gravity: Int) = label(MAX_FIGURE_SP, light = true).apply {
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        isSingleLine = true
        this.gravity = gravity
    }

    /** Taille en pixels entiers : à taille égale, TextView ne refait rien. */
    private fun TextView.fontPx(px: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, floor(px.coerceAtLeast(sp(MIN_SP))))

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private fun natural(view: View): Int {
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return view.measuredHeight
    }

    /** Hauteur d'un texte qui peut passer à la ligne, pour une largeur donnée. */
    private fun naturalHeight(view: View, width: Int): Int {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        return view.measuredHeight
    }

    private fun naturalWidth(view: View): Int {
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return view.measuredWidth
    }

    private fun View.shown(on: Boolean) {
        val target = if (on) View.VISIBLE else View.GONE
        if (visibility != target) visibility = target
    }

    /** Texte réaffecté seulement s'il change : un relevé toutes les 2 s ne doit pas relancer une mise en page pour rien. */
    private fun TextView.set(text: String) {
        if (this.text.toString() != text) this.text = text
    }

    companion object {
        private const val POLL_MS = 2_000L
        private val RPM_FORMAT: NumberFormat = NumberFormat.getIntegerInstance(Locale.FRANCE)

        /** Chiffre le plus large : la taille est bornée pour qu'un 100 % tienne. */
        private const val WIDEST = "100%"

        /** Taille « Valeurs » de la DA (44 à 52 sp) : plafond des chiffres du Moyen et des anneaux. */
        private const val MAX_FIGURE_SP = 52f

        /** Plus petit chiffre du Moyen avec titre et courbe ; en dessous, il passe sur une ligne. */
        private const val MIN_FIGURE_SP = 28f
        private const val MAX_BAR_SP = 44f

        /** Plus petit chiffre du Bandeau sur trois lignes ; en dessous, les barres passent côte à côte. */
        private const val MIN_BAR_SP = 18f
        private const val MIN_SP = 12f

        /** Part de la hauteur d'une ligne du Bandeau occupée par son chiffre. */
        private const val BAR_FIGURE_SHARE = 0.8f

        // Espaces en dp.
        private const val HEADER_GAP = 8
        private const val CURVE_GAP = 8
        private const val CURVE_MIN = 24
        private const val COLUMN_GAP = 12
        private const val RING_TOP = 8
        private const val RING_INSET = 4
        private const val CHIPS_GAP = 8
        private const val CHIPS_START = 32
        private const val BAR_GROUP_GAP = 24
        private const val BAR_THICKNESS = 8
        private const val BAR_MIN = 48
    }
}

/**
 * Tuile qui met son contenu à la taille de la place reçue juste avant de se mesurer, comme HeroView : les lignes de
 * l'Accueil n'ont pas toutes la même hauteur. `fit` reçoit la hauteur et la largeur intérieures (sans la marge).
 */
@SuppressLint("ViewConstructor")
private class FittingCard(context: Context, private val fit: (Int, Int) -> Unit) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            fit(MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Courbes de la dernière minute (processeur et carte graphique), de 0 à 100 %, la plus récente à droite. */
@SuppressLint("ViewConstructor")
private class PerfSparkline(context: Context, private val capacity: Int) : View(context) {
    private val pad = 2 * resources.displayMetrics.density
    private val path = Path()
    private val cpuPaint = linePaint()
    private val gpuPaint = linePaint()
    private var cpu: List<Int?> = emptyList()
    private var gpu: List<Int?> = emptyList()

    private fun linePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Nouvelles mesures et tons des deux traits ; un seul redessin, sans animation. */
    fun set(cpu: List<Int>, gpu: List<Int?>, cpuColor: Int, gpuColor: Int) {
        this.cpu = cpu
        this.gpu = gpu
        cpuPaint.color = cpuColor
        gpuPaint.color = gpuColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        line(canvas, gpu, gpuPaint)
        line(canvas, cpu, cpuPaint)
    }

    /** Un trait par série ; un relevé manquant (null) coupe la ligne. */
    private fun line(canvas: Canvas, values: List<Int?>, paint: Paint) {
        val n = values.size
        if (n < 2) return
        val step = (width - 2 * pad) / (capacity - 1)
        path.reset()
        var open = false
        for (i in 0 until n) {
            val v = values[i]
            if (v == null) {
                open = false
                continue
            }
            val x = width - pad - (n - 1 - i) * step
            val y = pad + (height - 2 * pad) * (1f - v.coerceIn(0, 100) / 100f)
            if (open) path.lineTo(x, y) else path.moveTo(x, y)
            open = true
        }
        canvas.drawPath(path, paint)
    }
}

/** Barre de remplissage (0 à 1) sur sa piste, bouts arrondis. */
@SuppressLint("ViewConstructor")
private class PerfBar(context: Context, trackColor: Int, fillColor: Int) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val bounds = RectF()

    var progress = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) { field = clamped; invalidate() }
        }

    var color: Int
        get() = fill.color
        set(value) {
            if (value != fill.color) { fill.color = value; invalidate() }
        }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, track)
        if (progress > 0f) {
            bounds.right = maxOf(width * progress, height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, fill)
        }
    }
}
