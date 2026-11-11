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
import app.mate.net.PcApp
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
 * Une mise en page par famille de formes ([perfLayout]) : un anneau (la mesure la plus chargée), des chiffres et la
 * courbe des 5 dernières minutes, des barres, ou trois anneaux.
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
        setOnClickListener { if (!host.editing && !sample && !notAvailable && appsView == null) showAppsPanel() }
        // Widget retiré ou Activity recréée : le panneau part avec lui, il ne reste pas figé à l'écran.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = closePanel()
        })
    }

    private val history = PerfHistory()
    private var last: PcPerf? = null

    // Applis qui consomment le plus : `apps` null tant qu'aucune liste n'est lue, ou si elle est illisible.
    private var apps: List<PcApp>? = null
    private var appsRead = false
    private var appsError: String? = null

    /** Compagnon d'avant la liste des applis (404) : on ne la redemande plus. */
    private var appsOutdated = false
    private var appsView: PerfAppsView? = null

    /** Contenu du panneau des petites formes et sa fenêtre, tant qu'il est ouvert. */
    private var panel: PerfAppsView? = null
    private var panelDialog: AlertDialog? = null
    private var tick = 0
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
    private var extrasKey = ""

    /** Vrai si la courbe a quelque chose à tracer (processeur, carte graphique ou mémoire coché). */
    private var curve = true
    private var spark: PerfSparkline? = null

    /** Infos cochées pour l'ordinateur actif, relues à chaque mesure (réglages changés, autre ordinateur). */
    private var choice: Set<PerfOption> = PerfChoice.DEFAULT

    /** Aperçu du catalogue : une mesure d'exemple, ni réseau ni boucle. */
    private var sample = false

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

    override fun showPreview(sample: WidgetSample) {
        if (sample !is PerfSample) return
        this.sample = true
        PerfSample.fill(history)
        last = PerfSample.perf
        apps = PerfSample.apps
        appsRead = true
        error = null
        fresh = true
        render()
    }

    override fun applySize() {
        title.text = context.getString(R.string.perf_title, Computers.active(context)?.name ?: context.getString(R.string.computer_generic))
        body.removeAllViews()
        ramGb = null
        extras = null
        extrasKey = ""
        spark = null
        pill = null
        appsView = null
        body.addView(when (perfLayout(placed.size)) {
            PerfLayout.PILL -> pill()
            PerfLayout.GAUGES -> gauges()
            PerfLayout.BARS -> bars()
            PerfLayout.FIGURES -> figures()
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        render()
    }

    /**
     * Active : une seule boucle de relevés ; inactive (autre page, écran éteint) : aucun relevé, la courbe est oubliée
     * et les dernières valeurs restent grisées jusqu'au relevé suivant.
     */
    override fun setActive(active: Boolean) {
        if (sample) return
        if (!active) {
            // Plus de relevés : le panneau fermé plutôt que laissé avec des valeurs figées.
            closePanel()
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
            history.add(perf.cpu, perf.gpu?.usage, perf.ramPercent)
            last = perf
            error = null
            fresh = true
        } catch (e: HttpException) {
            // 501 : cet ordinateur ne sait pas lire ses performances.
            if (e.code == 501) notAvailable = true else error = context.errorText(e)
        } catch (e: Exception) {
            error = context.errorText(e)
        }
        // Liste à l'écran : à chaque relevé ; sinon un relevé sur trois, pour l'alerte de l'en-tête (le compagnon suit
        // une appli qui abuse tant qu'on le relit au moins toutes les 10 s).
        if (error == null && !notAvailable && (appsView != null || panel != null || tick++ % APPS_EVERY == 0)) loadApps()
        render()
    }

    private suspend fun loadApps() {
        if (appsOutdated) return
        try {
            apps = BackendClient(context).system.perfApps()
            appsError = null
            appsRead = true
        } catch (e: HttpException) {
            if (e.code == 404) appsOutdated = true else appsError = context.errorText(e)
        } catch (e: Exception) {
            appsError = context.errorText(e)
        }
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
        // Libellés du ton de leur trait : ils servent de légende à la courbe.
        val cpuColumn = column(context.getString(R.string.perf_cpu), perfColorAttr(PerfSeries.CPU, PerfMark.LINE, PerfLevel.NORMAL))
        val gpuColumn = column(context.getString(R.string.perf_gpu), perfColorAttr(PerfSeries.GPU, PerfMark.LINE, PerfLevel.NORMAL))
        val ramColumn = column(context.getString(R.string.perf_ram), perfColorAttr(PerfSeries.RAM, PerfMark.LINE, PerfLevel.NORMAL))
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
            // Grand et Plein écran : les chiffres grandissent avec la place (la courbe garde le reste).
            val big = family == ShapeFamily.LARGE || family == ShapeFamily.FULL
            val size = minOf(sp(if (big) MAX_BIG_FIGURE_SP else MAX_FIGURE_SP), room * (if (big) BIG_FIGURE_SHARE else 1f) / figureLine, (columnWidth - nameWidth) / figureWidth)
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
        val figuresBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(row)
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(CURVE_GAP) })
            addView(sparkline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(CURVE_GAP) })
        }
        return if (perfShowsApps(placed.size)) withApps(figuresBox) else figuresBox
    }

    /**
     * Grand et Plein écran (maquette B) : « Ce qui consomme » à côté des chiffres et de la courbe sur toute la largeur de
     * l'Accueil, dessous sur une forme haute, séparés par un filet. Les chiffres se calent sur la place qui leur reste.
     */
    private fun withApps(figuresBox: View): View {
        val list = PerfAppsView(context, withTitle = true)
        appsView = list
        val beside = perfAppsBeside(placed.size)
        val rule = View(context).apply { setBackgroundColor(color(R.attr.c_stroke)) }
        val figuresFitter = fitter
        fitter = { height, width ->
            if (beside) {
                val left = (width * FIGURES_SHARE).toInt()
                figuresFitter(height, left)
                list.fit(height - headerHeight - dp(HEADER_GAP), width - left - dp(APPS_GAP) * 2)
            } else {
                val top = (height * FIGURES_SHARE).toInt()
                figuresFitter(top, width)
                list.fit(height - top - dp(APPS_GAP) * 2, width)
            }
        }
        return LinearLayout(context).apply {
            orientation = if (beside) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (beside) {
                addView(figuresBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, FIGURES_SHARE))
                addView(rule, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(APPS_GAP); marginEnd = dp(APPS_GAP) })
                addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1 - FIGURES_SHARE))
            } else {
                addView(figuresBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, FIGURES_SHARE))
                addView(rule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(APPS_GAP); bottomMargin = dp(APPS_GAP) })
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1 - FIGURES_SHARE))
            }
        }
    }

    /**
     * Petites formes : un appui ouvre « Ce qui consomme » dans un panneau. La boucle des relevés le remplit (applis relues à
     * chaque relevé tant qu'il est ouvert) : une seule requête toutes les 2 s, « Lecture des applis… » d'ici là.
     */
    private fun showAppsPanel() {
        if (panelDialog != null) return
        val list = PerfAppsView(context, withTitle = false).apply { setPadding(dp(24), dp(4), dp(24), dp(8)) }
        panel = list
        val name = Computers.active(context)?.name ?: context.getString(R.string.computer_generic)
        panelDialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.perf_apps_panel_title, name))
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener {
                panel = null
                panelDialog = null
            }
            .showImmersive()
        render()
    }

    private fun closePanel() {
        panelDialog?.dismiss()
        panelDialog = null
        panel = null
    }

    /** Le souci du moment en une phrase (« Navigateur prend 61 % du processeur depuis 2 min »), dite avec l'icône d'alerte. */
    private fun issueText(issue: PerfIssue): String = when (issue) {
        is PerfIssue.AppCpu -> perfDuration(issue.seconds).let {
            context.getString(if (it.minutes) R.string.perf_issue_app_cpu_min else R.string.perf_issue_app_cpu_s, issue.name, issue.cpu, it.value)
        }
        is PerfIssue.AppRam -> context.getString(R.string.perf_issue_app_ram, issue.name, PerfAppsView.memText(context, issue.memMb))
        is PerfIssue.DiskFull -> context.getString(R.string.perf_issue_disk, perfNumber(issue.freeGb))
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

    // ---- Pastille : un seul anneau ----

    /** La Pastille : son anneau, son chiffre et le nom de la mesure. */
    private class Pill(val ring: ProgressRing, val value: TextView, val name: TextView)

    private var pill: Pill? = null

    private fun pill(): View {
        val ring = ProgressRing(context).apply { trackColor = color(R.attr.c_stroke) }
        val value = figure(Gravity.CENTER)
        val name = label(PILL_NAME_SP, R.attr.c_text_dim).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        pill = Pill(ring, value, name)
        // Les trois relevés des autres formes ne sont pas montrés : la Pastille a son propre affichage (`showPill`).
        cpu = Reading(View(context), TextView(context), PerfSeries.CPU)
        gpu = Reading(View(context), TextView(context), PerfSeries.GPU)
        ram = Reading(View(context), TextView(context), PerfSeries.RAM)
        val minStroke = dp(4).toFloat()
        fitter = { height, width ->
            showHeader(false)
            val diameter = minOf(height, width).toFloat()
            val size = figureInCircle(diameter / 2 - ProgressRing.strokeFor(diameter, minStroke) - dp(RING_INSET), natural(name).toFloat())
            value.fontPx(minOf(sp(MAX_PILL_SP), size))
        }
        return FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * La mesure de la Pastille (variante B choisie par Ahmed, [pillReading]) : la plus chargée, son nom dessous. L'alerte
     * se dit aussi en mots (« RAM critique »), une erreur remplace le nom ; TalkBack lit le tout.
     */
    private fun showPill(parts: Pill, perf: PcPerf, stale: Boolean) {
        val reading = pillReading(perf, choice) ?: return
        val level = usageLevel(reading.percent)
        val name = context.getString(
            when (reading.series) {
                PerfSeries.CPU -> R.string.perf_cpu
                PerfSeries.GPU -> R.string.perf_gpu
                PerfSeries.RAM -> R.string.perf_ram
            },
        )
        val word = perfLevelWord(level)?.takeIf { !stale }?.let { "$name ${context.getString(it)}" } ?: name
        parts.value.set("${reading.percent}%")
        parts.value.setTextColor(color(perfColorAttr(reading.series, PerfMark.VALUE, level, stale)))
        // Une appli qui abuse : la mesure garde son nom (un nom d'appli ne tient pas dans l'anneau), avec l'icône
        // d'alerte et en rouge ; l'appli se lit dans le panneau, d'un appui.
        val hog = apps?.any { it.warn != null } == true && !stale && error == null
        parts.name.setWarned(error ?: word, hog)
        parts.name.setTextColor(color(if (hog) R.attr.c_accent else R.attr.c_text_dim))
        parts.ring.progress = reading.percent / 100f
        parts.ring.color = color(perfColorAttr(reading.series, PerfMark.FILL, level, stale))
        (parts.ring.parent as? View)?.contentDescription = context.getString(R.string.perf_pill_desc, word, reading.percent)
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
            } else if (family == ShapeFamily.COLUMN) {
                // Colonne : les infos sont dessous, et la barre garde au moins 48 dp à côté du chiffre.
                val below = if (line.text.isEmpty()) 0 else naturalHeight(line, width) + dp(CHIPS_GAP)
                minOf(
                    (height - headerHeight - dp(HEADER_GAP) - below).toFloat() / count * BAR_FIGURE_SHARE / figureLine,
                    (width - nameWidth - dp(24) - dp(BAR_MIN)) / figureWidth,
                )
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
        // Colonne : les infos en texte sous les barres (à côté, elles écraseraient la colonne).
        if (family == ShapeFamily.COLUMN) {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(CHIPS_GAP) })
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
        // Pastille : seules ses trois mesures comptent (une info en texte ne s'y affiche pas).
        val nothing = perf != null && if (pill != null) pillReading(perf, choice) == null else PerfOption.entries.none { it in choice && it.readable(perf) }
        unavailable.setText(if (notAvailable) R.string.perf_unavailable else R.string.perf_none_checked)
        unavailable.shown(notAvailable || nothing)
        // Rien à montrer avant la première mesure (des libellés sans chiffre).
        body.visibility = if (notAvailable || nothing) View.GONE else if (perf == null) View.INVISIBLE else View.VISIBLE
        note.set(error.orEmpty())
        // Pastille sans en-tête : l'erreur d'avant la première mesure s'écrit sous l'anneau, au lieu d'une case vide.
        pill?.takeIf { perf == null && error != null }?.let {
            body.visibility = View.VISIBLE
            it.value.set(context.getString(R.string.perf_pill_none))
            it.name.set(error.orEmpty())
        }
        if (perf == null) return
        // Dernières valeurs grisées tant que le relevé échoue, ou au retour sur la page avant le premier relevé.
        val stale = error != null || !fresh
        val cpuOn = PerfOption.CPU in choice
        val gpuOn = PerfOption.GPU in choice && perf.gpu != null
        val ramOn = PerfOption.RAM in choice
        val issue = perfIssue(perf, apps)?.takeIf { !stale }?.let { issueText(it) }
        val appsNote = when {
            appsOutdated -> context.getString(R.string.perf_apps_outdated)
            appsError != null -> appsError
            appsRead && apps == null -> context.getString(R.string.perf_apps_unavailable)
            else -> null
        }
        appsView?.bind(apps, issue, appsNote, stale)
        panel?.bind(apps, issue, appsNote ?: context.getString(R.string.perf_apps_loading).takeIf { !appsRead }, stale)
        // Sans la liste à l'écran, le souci du moment passe dans l'en-tête, en rouge d'alerte, avant les alertes des chiffres.
        val headerIssue = issue?.takeIf { error == null && appsView == null }
        note.setTextColor(color(if (headerIssue != null) R.attr.c_accent else R.attr.c_text_faint))
        // Alerte écrite dans l'en-tête en plus du rouge des chiffres (rien sur des valeurs grisées) ; une erreur prime.
        if (headerIssue != null) {
            note.setWarned(headerIssue, true)
        } else if (error == null) {
            val alerts = if (stale) "" else perfAlertText(
                listOfNotNull(
                    if (cpuOn) context.getString(R.string.perf_cpu) to usageLevel(perf.cpu) else null,
                    if (gpuOn) context.getString(R.string.perf_gpu) to usageLevel(perf.gpu!!.usage) else null,
                    if (ramOn) context.getString(R.string.perf_ram) to usageLevel(perf.ramPercent) else null,
                    if (PerfOption.CPU_TEMP in choice) perf.cpuTempC?.let { context.getString(R.string.perf_cpu_temp_alert) to tempLevel(it) } else null,
                    if (PerfOption.GPU_TEMP in choice) perf.gpu?.tempC?.let { context.getString(R.string.perf_gpu_temp_alert) to tempLevel(it) } else null,
                ),
                context::getString,
            )
            note.set(alerts)
        }
        val shownPill = pill
        if (shownPill != null) {
            showPill(shownPill, perf, stale)
        } else {
            show(cpu, perf.cpu.takeIf { cpuOn }, stale)
            show(ram, perf.ramPercent.takeIf { ramOn }, stale)
            show(gpu, perf.gpu?.usage?.takeIf { gpuOn }, stale)
        }
        ramGb?.set(context.getString(R.string.perf_ram_gb, perfNumber(perf.ramUsedGb), perfNumber(perf.ramTotalGb)))
        extras?.let { line ->
            val list = extras(perf, stale)
            // Seulement s'il change (texte ou ton) : un relevé toutes les 2 s ne doit pas refaire la mise en page.
            val key = list.joinToString("|") { it.text + it.tone }
            if (key != extrasKey) line.text = extrasText(list)
            extrasKey = key
        }
        curve = cpuOn || gpuOn || ramOn
        val gpuLevel = perf.gpu?.usage?.let { usageLevel(it) } ?: PerfLevel.NORMAL
        spark?.set(
            if (cpuOn) history.cpu else emptyList(),
            if (gpuOn) history.gpu else emptyList(),
            if (ramOn) history.ram else emptyList(),
            color(perfColorAttr(PerfSeries.CPU, PerfMark.LINE, usageLevel(perf.cpu), stale)),
            color(perfColorAttr(PerfSeries.GPU, PerfMark.LINE, gpuLevel, stale)),
            color(perfColorAttr(PerfSeries.RAM, PerfMark.LINE, usageLevel(perf.ramPercent), stale)),
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
    private fun extrasText(list: List<Extra>): CharSequence {
        // Barres d'une Ligne : les infos à droite, une par ligne.
        // Barres d'une Ligne (infos à droite) ou d'une Colonne (étroite) : une info par ligne.
        val separator = if (perfLayout(placed.size) == PerfLayout.BARS) "\n" else context.getString(R.string.perf_extras_separator)
        val out = SpannableStringBuilder()
        for (extra in list) {
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
        when (option) {
            PerfOption.CPU_FREQ -> Extra(context.getString(R.string.perf_cpu_freq_line, perfNumber(perf.cpuMhz!! / 1000.0)), calm)
            PerfOption.CPU_TEMP -> perf.cpuTempC!!.let { Extra(context.getString(R.string.perf_cpu_temp_line, it), perfColorAttr(PerfSeries.CPU, PerfMark.VALUE, tempLevel(it), stale)) }
            PerfOption.CPU_WATTS -> Extra(context.getString(R.string.perf_cpu_watts_line, perfNumber(perf.cpuWatts!!)), calm)
            PerfOption.GPU_WATTS -> Extra(context.getString(R.string.perf_gpu_watts_line, perfNumber(perf.gpuWatts!!)), calm)
            PerfOption.CPU_FAN -> Extra(context.getString(R.string.perf_cpu_fan_line, context.getString(R.string.perf_fan_rpm, RPM_FORMAT.format(perf.cpuFanRpm!!))), if (stale) R.attr.c_text_faint else R.attr.c_text)
            // Aucun jeu mesuré : « — », jamais 0.
            PerfOption.FPS -> Extra(perf.fps?.let { context.getString(R.string.perf_fps_line, it.toString()) } ?: context.getString(R.string.perf_fps_none), if (perf.fps == null) R.attr.c_text_faint else calm)
            PerfOption.GPU_TEMP -> perf.gpu!!.tempC!!.let { Extra(context.getString(R.string.perf_gpu_temp_line, it), perfColorAttr(PerfSeries.GPU, PerfMark.VALUE, tempLevel(it), stale)) }
            PerfOption.GPU_FAN -> perf.gpu!!.fanRpm!!.let {
                // « Arrêté » est un texte tertiaire ; le ventilateur n'est jamais en alerte.
                val text = if (it == 0) context.getString(R.string.perf_fan_stopped) else context.getString(R.string.perf_fan_rpm, RPM_FORMAT.format(it))
                Extra(context.getString(R.string.perf_fan_line, text), if (stale || it == 0) R.attr.c_text_faint else R.attr.c_text)
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
        val reading = host.scope.launch {
            val latest = try { BackendClient(context).system.perf() } catch (e: Exception) { return@launch }
            fillSettings(box, computerId, latest)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_perf)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { reading.cancel() }
            .showImmersive()
        fillSettings(box, computerId, last)
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
            val readable = perf?.let { option.readable(it) } ?: true
            val note = when {
                !readable && option == PerfOption.FPS -> context.getString(R.string.perf_option_fps_unreadable)
                !readable -> context.getString(R.string.perf_option_unreadable)
                else -> null
            }
            box.addView(CheckBox(context).apply {
                text = note?.let { context.getString(R.string.perf_option_note, context.getString(option.label), it) } ?: context.getString(option.label)
                typeface = themeFont(context, R.attr.c_font_regular)
                textSize = 16f
                setTextColor(color(if (readable) R.attr.c_text else R.attr.c_text_faint))
                isEnabled = readable
                minHeight = dp(48)
                // Une info illisible reste dans le choix (elle reviendra si l'ordinateur la lit) mais s'affiche décochée.
                isChecked = readable && option in PerfChoice.read(placed.config, computerId)
                setOnCheckedChangeListener { _, on ->
                    val now = PerfChoice.read(placed.config, computerId)
                    PerfChoice.write(placed.config, computerId, if (on) now + option else now - option)
                    host.save()
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

        /** Sans liste à l'écran, les applis sont relues un relevé sur trois (toutes les 6 s). */
        private const val APPS_EVERY = 3

        /** Part des chiffres et de la courbe quand « Ce qui consomme » est à côté ou dessous. */
        private const val FIGURES_SHARE = 0.55f
        private const val APPS_GAP = 20
        private val RPM_FORMAT: NumberFormat = NumberFormat.getIntegerInstance(Locale.FRANCE)

        /** Chiffre le plus large : la taille est bornée pour qu'un 100 % tienne. */
        private const val WIDEST = "100%"

        /** Taille « Valeurs » de la DA (44 à 52 sp) : plafond des chiffres du Moyen et des anneaux. */
        private const val MAX_FIGURE_SP = 52f

        /** Plus grand chiffre en Grand et Plein écran. */
        private const val MAX_BIG_FIGURE_SP = 96f

        /** Part de la hauteur que les chiffres prennent en grand : la courbe garde le reste. */
        private const val BIG_FIGURE_SHARE = 0.6f

        /** Nom de la mesure sous l'anneau de la Pastille. */
        private const val PILL_NAME_SP = 16f

        /** Plus grand chiffre de la Pastille. */
        private const val MAX_PILL_SP = 44f

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

/** Courbes des dernières minutes (processeur, carte graphique et mémoire), de 0 à 100 %, la plus récente à droite. */
@SuppressLint("ViewConstructor")
private class PerfSparkline(context: Context, private val capacity: Int) : View(context) {
    private val pad = 2 * resources.displayMetrics.density
    private val path = Path()
    private val cpuPaint = linePaint()
    private val gpuPaint = linePaint()
    private val ramPaint = linePaint()
    private var cpu: List<Int?> = emptyList()
    private var gpu: List<Int?> = emptyList()
    private var ram: List<Int?> = emptyList()

    private fun linePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Nouvelles mesures et tons des trois traits ; un seul redessin, sans animation. */
    fun set(cpu: List<Int>, gpu: List<Int?>, ram: List<Int>, cpuColor: Int, gpuColor: Int, ramColor: Int) {
        this.cpu = cpu
        this.gpu = gpu
        this.ram = ram
        cpuPaint.color = cpuColor
        gpuPaint.color = gpuColor
        ramPaint.color = ramColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        line(canvas, ram, ramPaint)
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

/** Barre de remplissage (0 à 1) sur sa piste, bouts arrondis (widget Performances et sa liste d'applis). */
@SuppressLint("ViewConstructor")
internal class PerfBar(context: Context, trackColor: Int, fillColor: Int) : View(context) {
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
